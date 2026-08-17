#include <vulkan/vulkan.h>

#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <sys/stat.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <android/api-level.h>
#include <signal.h>
#include <setjmp.h>
#include "../adrenotools/include/adrenotools/driver.h"

#define LOG_TAG "System.out"
#define printf(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

VkInstance instance;
VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
PFN_vkGetPhysicalDeviceProperties getPhysicalDeviceProperties;
PFN_vkEnumerateDeviceExtensionProperties enumerateDeviceExtensionProperties;
PFN_vkEnumeratePhysicalDevices enumeratePhysicalDevices;
PFN_vkDestroyInstance destroyInstance;

static void *vulkan_handle = NULL;

// Set by init_vulkan when a real, installed Adrenotools custom driver was selected but
// could NOT be loaded on this GPU, so we fell back to the system Vulkan ICD instead of
// crashing. Read by GPUInformation.driverLoadedFellBack() to surface a UI note. Never
// set for wrapper/turnip probes — those legitimately probe against the system ICD.
static int last_driver_fell_back = 0;

static sigjmp_buf sigill_jmp_buf;
static struct sigaction saved_sigill_action;
static struct sigaction saved_sigsegv_action;
static struct sigaction saved_sigbus_action;
static struct sigaction saved_sigabrt_action;

// A bad/mismatched vendor ICD can fault DEEP inside Qualcomm code during instance
// creation (e.g. libadreno_app_profiles.so's per-app profile lookup), which is a
// SIGSEGV/SIGBUS, not a SIGILL. Trapping only SIGILL let those hard-crash the whole app
// (reporter's a6xx, Aug 2026). Catch all three around the probe and siglongjmp back so a
// driver that won't load on this GPU degrades to the system ICD instead of killing us.
static void probe_signal_handler(int sig) {
    siglongjmp(sigill_jmp_buf, 1);
}

static void install_probe_signal_guard() {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = probe_signal_handler;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGILL, &sa, &saved_sigill_action);
    sigaction(SIGSEGV, &sa, &saved_sigsegv_action);
    sigaction(SIGBUS, &sa, &saved_sigbus_action);
    // Backstop only: a proprietary Adreno blob probed in-process can abort() with a
    // -fstack-protector stack smash (SIGABRT) inside the vendor app-profile/log path on some
    // a6xx (hotice77's Redmi Note 11, Aug 2026) — this sails past the SEGV/BUS traps. We now
    // gate proprietary blobs out of the probe in Kotlin, so this should never fire; keep it as
    // a weak net for any future rogue wrapper. (Stack is already corrupt here, so recovery is
    // best-effort; do NOT rely on it for heap-corruption aborts, which happen at dlopen time.)
    sigaction(SIGABRT, &sa, &saved_sigabrt_action);
}

static void restore_probe_signal_guard() {
    sigaction(SIGABRT, &saved_sigabrt_action, NULL);
    sigaction(SIGILL, &saved_sigill_action, NULL);
    sigaction(SIGSEGV, &saved_sigsegv_action, NULL);
    sigaction(SIGBUS, &saved_sigbus_action, NULL);
}

static void cleanup_vulkan() {
    if (vulkan_handle) {
        dlclose(vulkan_handle);
        vulkan_handle = NULL;
    }
}


static char *get_native_library_dir(JNIEnv *env, jobject context) {
    char *native_libdir = NULL;

    if (context != NULL) {
        jclass class_ = (*env)->FindClass(env,"com/winlator/star/core/AppUtils");
        jmethodID getNativeLibraryDir = (*env)->GetStaticMethodID(env, class_, "getNativeLibDir",
                                                               "(Landroid/content/Context;)Ljava/lang/String;");
        jstring nativeLibDir = (jstring)(*env)->CallStaticObjectMethod(env, class_,
                                                                     getNativeLibraryDir,
                                                                     context);
        if (nativeLibDir)
            native_libdir = (char *)(*env)->GetStringUTFChars(env, nativeLibDir, NULL);
    }

    return native_libdir;
}

static char *get_driver_path(JNIEnv *env, jobject context, const char *driver_name) {
    char *driver_path = NULL;
    char *absolute_path;

    jclass contextWrapperClass = (*env)->FindClass(env, "android/content/ContextWrapper");
    jmethodID  getFilesDir = (*env)->GetMethodID(env, contextWrapperClass, "getFilesDir", "()Ljava/io/File;");
    jobject  filesDirObj = (*env)->CallObjectMethod(env, context, getFilesDir);
    jclass fileClass = (*env)->GetObjectClass(env, filesDirObj);
    jmethodID getAbsolutePath = (*env)->GetMethodID(env, fileClass, "getAbsolutePath", "()Ljava/lang/String;");
    jstring absolutePath = (jstring)(*env)->CallObjectMethod(env,filesDirObj,
                                                             getAbsolutePath);

    if (absolutePath) {
        absolute_path = (char *)(*env)->GetStringUTFChars(env,absolutePath, NULL);
        asprintf(&driver_path, "%s/contents/adrenotools/%s/", absolute_path, driver_name);
        (*env)->ReleaseStringUTFChars(env,absolutePath, absolute_path);
    }

    return driver_path;
}

static char *get_library_name(JNIEnv *env, jobject context, const char *driver_name) {
    char *library_name = NULL;

    jclass adrenotoolsManager = (*env)->FindClass(env, "com/winlator/star/contents/AdrenotoolsManager");
    jmethodID constructor = (*env)->GetMethodID(env, adrenotoolsManager, "<init>", "(Landroid/content/Context;)V");
    jobject  adrenotoolsManagerObj = (*env)->NewObject(env, adrenotoolsManager, constructor, context);
    jmethodID getLibraryName = (*env)->GetMethodID(env, adrenotoolsManager, "getLibraryName","(Ljava/lang/String;)Ljava/lang/String;");
    jstring driverName = (*env)->NewStringUTF(env, driver_name);
    jstring libraryName = (jstring)(*env)->CallObjectMethod(env, adrenotoolsManagerObj,getLibraryName, driverName);

    if (libraryName)
        library_name = (char *)(*env)->GetStringUTFChars(env, libraryName, NULL);

    return library_name;
}

static void init_original_vulkan() {
    vulkan_handle = dlopen("/system/lib64/libvulkan.so", RTLD_LOCAL | RTLD_NOW);
}

// Vendor Vulkan ICD dependencies (especially newer Qualcomm blobs, e.g. Adrenotools
// custom drivers) are resolved lazily by the loader; make them globally visible BEFORE
// the custom driver is dlopen'd, or the driver's init can fail to resolve them and fault
// (a native SIGSEGV the app-scoped logcat never captures). Ported from WinNative, which
// loads the same drivers on the same GPUs without crashing (both apps are GPL-3.0).
static void preload_first_existing(const char **candidates) {
    for (int i = 0; candidates[i]; i++) {
        if (dlopen(candidates[i], RTLD_GLOBAL | RTLD_NOW))
            return;
    }
}

static void preload_vendor_icd_deps() {
    const char *jpeg_candidates[] = {
        "/system/lib64/libjpeg.so",
        "/system_ext/lib64/libjpeg.so",
        "libjpeg.so",
        NULL,
    };
    preload_first_existing(jpeg_candidates);

    const char *crypto_candidates[] = {
        "libcrypto.so",
        NULL,
    };
    preload_first_existing(crypto_candidates);
}

static void init_vulkan(JNIEnv  *env, jobject context, const char *driver_name) {
    preload_vendor_icd_deps();

    const char *driver_path = get_driver_path(env, context, driver_name);
    // Not an installed Adrenotools custom driver (e.g. a wrapper/turnip version name) —
    // probe against the system ICD, exactly as before. This is the expected path, NOT a
    // failure, so we leave last_driver_fell_back at 0.
    if (!driver_path || access(driver_path, F_OK) != 0) {
        init_original_vulkan();
        return;
    }

    char *library_name = get_library_name(env, context, driver_name);
    char *native_library_dir = get_native_library_dir(env, context);
    if (!library_name || !native_library_dir) {
        init_original_vulkan();
        return;
    }

    // NOTE: adrenotools retains these strings (tmpdir/dirs/name) internally, so they must
    // NOT be freed here — freeing would be a use-after-free (matches WinNative).
    char *tmpdir = NULL;
    asprintf(&tmpdir, "%s%s", driver_path, "temp");
    mkdir(tmpdir, S_IRWXU | S_IRWXG);

    vulkan_handle = adrenotools_open_libvulkan(RTLD_LOCAL | RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM, tmpdir, native_library_dir, driver_path, library_name, NULL, NULL);

    // A real, installed custom driver that wouldn't load on this GPU: fall back to the
    // system ICD so the app never crashes, and flag it so the UI can say what happened.
    if (!vulkan_handle) {
        last_driver_fell_back = 1;
        init_original_vulkan();
    }
}

JNIEXPORT jboolean JNICALL
Java_com_winlator_star_core_GPUInformation_driverLoadedFellBack(JNIEnv *env, jclass obj) {
    return last_driver_fell_back ? JNI_TRUE : JNI_FALSE;
}

static VkResult create_instance(jstring driverName, JNIEnv *env, jobject context) {
    VkResult result;
    VkInstanceCreateInfo create_info = {};
    char *driver_name = NULL;

    // Reset once per probe; init_vulkan raises it only when a real installed custom
    // driver was chosen but couldn't load (System/wrapper paths leave it at 0).
    last_driver_fell_back = 0;

    if (driverName != NULL)
        driver_name = (char *)(*env)->GetStringUTFChars(env, driverName, NULL);

    if (driver_name && strcmp(driver_name, "System"))
        init_vulkan(env, context, driver_name);
    else
        init_original_vulkan();

    if (!vulkan_handle)
        return VK_ERROR_INITIALIZATION_FAILED;

    PFN_vkGetInstanceProcAddr gip = (PFN_vkGetInstanceProcAddr)dlsym(vulkan_handle, "vkGetInstanceProcAddr");
    PFN_vkCreateInstance createInstance = (PFN_vkCreateInstance)dlsym(vulkan_handle, "vkCreateInstance");
    PFN_vkEnumerateInstanceVersion enumerateInstanceVersion = (PFN_vkEnumerateInstanceVersion)dlsym(vulkan_handle, "vkEnumerateInstanceVersion");

    if (!gip || !createInstance || !enumerateInstanceVersion)
        return VK_ERROR_INITIALIZATION_FAILED;

    int apiLevel = android_get_device_api_level();

    VkApplicationInfo app_info = {};
    app_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app_info.pApplicationName = "Bannerlator";
    app_info.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    app_info.pEngineName = "Bannerlator";
    app_info.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    if (apiLevel > 32)
        app_info.apiVersion = VK_API_VERSION_1_0;
    else
        enumerateInstanceVersion(&app_info.apiVersion);

    create_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    create_info.pNext = NULL;
    create_info.flags = 0;
    create_info.pApplicationInfo = &app_info;
    create_info.enabledLayerCount = 0;
    create_info.enabledExtensionCount = 0;

    result = createInstance(&create_info, NULL, &instance);

    if (result != VK_SUCCESS)
        return result;

    getPhysicalDeviceProperties = (PFN_vkGetPhysicalDeviceProperties)gip(instance, "vkGetPhysicalDeviceProperties");
    destroyInstance = (PFN_vkDestroyInstance)gip(instance, "vkDestroyInstance");
    enumerateDeviceExtensionProperties = (PFN_vkEnumerateDeviceExtensionProperties)gip(instance, "vkEnumerateDeviceExtensionProperties");
    enumeratePhysicalDevices = (PFN_vkEnumeratePhysicalDevices)gip(instance, "vkEnumeratePhysicalDevices");

    if (!getPhysicalDeviceProperties || !destroyInstance || !enumerateDeviceExtensionProperties || !enumeratePhysicalDevices)
        return VK_ERROR_INITIALIZATION_FAILED;

    return VK_SUCCESS;
}

static VkResult enumerate_physical_devices() {
    VkResult result;
    uint32_t deviceCount;

    result = enumeratePhysicalDevices(instance, &deviceCount, NULL);

    if (result != VK_SUCCESS)
        return result;

    if (deviceCount < 1)
        return VK_ERROR_INITIALIZATION_FAILED;

    VkPhysicalDevice *pdevices = malloc(sizeof(VkPhysicalDevice) * deviceCount);
    if (!pdevices)
        return VK_ERROR_OUT_OF_HOST_MEMORY;

    result = enumeratePhysicalDevices(instance, &deviceCount, pdevices);

    if (result != VK_SUCCESS)
        return result;

    physicalDevice = pdevices[0];

    if (physicalDevice == VK_NULL_HANDLE)
        return VK_ERROR_INITIALIZATION_FAILED;

    return VK_SUCCESS;
}

JNIEXPORT jstring JNICALL
Java_com_winlator_star_core_GPUInformation_getVulkanVersion(JNIEnv *env, jclass obj, jstring driverName, jobject context) {
    install_probe_signal_guard();

    if (sigsetjmp(sigill_jmp_buf, 1) == 0) {
        VkPhysicalDeviceProperties props = {};
        char *driverVersion;

        if (create_instance(driverName, env, context) != VK_SUCCESS) {
            printf("Failed to create instance");
            restore_probe_signal_guard();
            cleanup_vulkan();
            return (*env)->NewStringUTF(env, "Unknown");
        }

        if (enumerate_physical_devices() != VK_SUCCESS) {
            printf("Failed to query physical devices");
            restore_probe_signal_guard();
            cleanup_vulkan();
            return (*env)->NewStringUTF(env, "Unknown");
        }

        getPhysicalDeviceProperties(physicalDevice, &props);
        uint32_t api_version_major = VK_VERSION_MAJOR(props.apiVersion);
        uint32_t api_version_minor = VK_VERSION_MINOR(props.apiVersion);
        uint32_t api_version_patch = VK_VERSION_PATCH(props.apiVersion);
        asprintf(&driverVersion, "%d.%d.%d", api_version_major, api_version_minor, api_version_patch);

        destroyInstance(instance, NULL);

        restore_probe_signal_guard();
        cleanup_vulkan();
        return (*env)->NewStringUTF(env, driverVersion);
    }

    printf("Probe signal caught: driver incompatible");
    restore_probe_signal_guard();
    cleanup_vulkan();
    return (*env)->NewStringUTF(env, "Unknown");
}

JNIEXPORT jint JNICALL
Java_com_winlator_star_core_GPUInformation_getVendorID(JNIEnv *env, jclass obj, jstring driverName, jobject context) {
    install_probe_signal_guard();

    if (sigsetjmp(sigill_jmp_buf, 1) == 0) {
        VkPhysicalDeviceProperties props = {};
        uint32_t vendorID;

        if (create_instance(driverName, env, context) != VK_SUCCESS) {
            printf("Failed to create instance");
            restore_probe_signal_guard();
            cleanup_vulkan();
            return 0;
        }

        if (enumerate_physical_devices() != VK_SUCCESS) {
            printf("Failed to query physical devices");
            restore_probe_signal_guard();
            cleanup_vulkan();
            return 0;
        }

        getPhysicalDeviceProperties(physicalDevice, &props);
        vendorID = props.vendorID;

        destroyInstance(instance, NULL);

        restore_probe_signal_guard();
        cleanup_vulkan();
        return vendorID;
    }

    printf("Probe signal caught: driver incompatible");
    restore_probe_signal_guard();
    cleanup_vulkan();
    return 0;
}


JNIEXPORT jstring JNICALL
Java_com_winlator_star_core_GPUInformation_getRenderer(JNIEnv *env, jclass obj, jstring driverName, jobject context) {
    install_probe_signal_guard();

    if (sigsetjmp(sigill_jmp_buf, 1) == 0) {
        VkPhysicalDeviceProperties props = {};
        char *renderer;

        if (create_instance(driverName, env, context) != VK_SUCCESS) {
            printf("Failed to create instance");
            restore_probe_signal_guard();
            cleanup_vulkan();
            return (*env)->NewStringUTF(env, "Unknown");
        }

        if (enumerate_physical_devices() != VK_SUCCESS) {
            printf("Failed to query physical devices");
            restore_probe_signal_guard();
            cleanup_vulkan();
            return (*env)->NewStringUTF(env, "Unknown");
        }

        getPhysicalDeviceProperties(physicalDevice, &props);
        asprintf(&renderer, "%s", props.deviceName);

        destroyInstance(instance, NULL);

        restore_probe_signal_guard();
        cleanup_vulkan();
        return (*env)->NewStringUTF(env, renderer);
    }

    printf("Probe signal caught: driver incompatible");
    restore_probe_signal_guard();
    cleanup_vulkan();
    return (*env)->NewStringUTF(env, "Unknown");
}

JNIEXPORT jobjectArray JNICALL
Java_com_winlator_star_core_GPUInformation_enumerateExtensions(JNIEnv *env, jclass obj, jstring driverName, jobject context) {
    install_probe_signal_guard();

    if (sigsetjmp(sigill_jmp_buf, 1) == 0) {
        jobjectArray extensions;
        VkResult result;
        uint32_t extensionCount;

        if (create_instance(driverName, env, context) != VK_SUCCESS) {
            printf("Failed to create instance");
            restore_probe_signal_guard();
            cleanup_vulkan();
            return (*env)->NewObjectArray(env, 0, (*env)->FindClass(env, "java/lang/String"), NULL);
        }

        if (enumerate_physical_devices() != VK_SUCCESS) {
            printf("Failed to query physical devices");
            restore_probe_signal_guard();
            cleanup_vulkan();
            return (*env)->NewObjectArray(env, 0, (*env)->FindClass(env, "java/lang/String"), NULL);
        }

        result = enumerateDeviceExtensionProperties(physicalDevice, NULL, &extensionCount, NULL);

        if (result != VK_SUCCESS || extensionCount < 1) {
            printf("Failed to query extension count");
            restore_probe_signal_guard();
            cleanup_vulkan();
            return (*env)->NewObjectArray(env, 0, (*env)->FindClass(env, "java/lang/String"), NULL);
        }

        VkExtensionProperties *extensionProperties = malloc(sizeof(VkExtensionProperties) * extensionCount);
        enumerateDeviceExtensionProperties(physicalDevice, NULL, &extensionCount,
                                           extensionProperties);

        if (result != VK_SUCCESS) {
            printf("Failed to query extensions");
            restore_probe_signal_guard();
            cleanup_vulkan();
            if (extensionProperties) free(extensionProperties);
            return (*env)->NewObjectArray(env, 0, (*env)->FindClass(env, "java/lang/String"), NULL);
        }

        extensions = (jobjectArray) (*env)->NewObjectArray(env, extensionCount,
                                                           (*env)->FindClass(env, "java/lang/String"),
                                                           NULL);
        for (int i = 0; i < extensionCount; i++) {
            (*env)->SetObjectArrayElement(env, extensions, i,
                                          (*env)->NewStringUTF(env, extensionProperties[i].extensionName));
        }

        if (extensionProperties) free(extensionProperties);
        destroyInstance(instance, NULL);

        restore_probe_signal_guard();
        cleanup_vulkan();
        return extensions;
    }

    // A real, installed custom driver that faults during probe (e.g. Adreno per-app
    // profile null-deref) is caught here. It "loaded" but is unusable on this GPU, so
    // mark it fell-back — otherwise the UI shows a bare "0/0 extensions" with no reason.
    printf("Probe signal caught: driver incompatible");
    last_driver_fell_back = 1;
    restore_probe_signal_guard();
    cleanup_vulkan();
    return (*env)->NewObjectArray(env, 0, (*env)->FindClass(env, "java/lang/String"), NULL);
}
