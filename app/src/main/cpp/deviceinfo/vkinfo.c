/*
 * The phone's own Vulkan driver, as the device details pop-up shows it. Ported from DroidDeck
 * (Droid-Deck/DroidDeck app/src/main/cpp/deviceinfo/vkinfo.c, GPL-3.0; device details by
 * The412Banner #295, extended by Kurt Himebauch, Danbro007, ASEbrahim, openMali, MaxsTechReview).
 */
#include <dlfcn.h>
#include <jni.h>
#include <stdio.h>
#include <string.h>
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>

#define LINE(...) do { if (used < sizeof(out)) used += snprintf(out + used, sizeof(out) - used, __VA_ARGS__); } while (0)

static const char *const EXTENSIONS[] = {
    "VK_EXT_robustness2", "VK_EXT_transform_feedback", "VK_EXT_custom_border_color",
    "VK_KHR_dynamic_rendering", "VK_EXT_extended_dynamic_state3", "VK_EXT_descriptor_indexing",
    "VK_KHR_timeline_semaphore", "VK_EXT_mesh_shader", "VK_KHR_ray_query",
};

static const char *type_name(VkPhysicalDeviceType type) {
    switch (type) {
        case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU: return "integrated GPU";
        case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU: return "discrete GPU";
        case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU: return "virtual GPU";
        case VK_PHYSICAL_DEVICE_TYPE_CPU: return "CPU";
        default: return "other";
    }
}

JNIEXPORT jstring JNICALL
Java_com_winlator_star_deviceinfo_VulkanInfo_nativeQuery(JNIEnv *env, jclass clazz) {
    static char out[4096];
    size_t used = 0;
    out[0] = 0;
    void *lib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib) { LINE("error=no Vulkan loader\n"); return (*env)->NewStringUTF(env, out); }
    PFN_vkGetInstanceProcAddr gipa = (PFN_vkGetInstanceProcAddr) dlsym(lib, "vkGetInstanceProcAddr");
    PFN_vkCreateInstance create = gipa ? (PFN_vkCreateInstance) gipa(NULL, "vkCreateInstance") : NULL;
    PFN_vkEnumerateInstanceVersion instanceVersion = gipa ? (PFN_vkEnumerateInstanceVersion) gipa(NULL, "vkEnumerateInstanceVersion") : NULL;
    if (!create) { LINE("error=no vkCreateInstance\n"); dlclose(lib); return (*env)->NewStringUTF(env, out); }
    uint32_t loader = VK_API_VERSION_1_0;
    if (instanceVersion) instanceVersion(&loader);
    LINE("loader=%u.%u.%u\n", VK_API_VERSION_MAJOR(loader), VK_API_VERSION_MINOR(loader), VK_API_VERSION_PATCH(loader));

    VkApplicationInfo app = { .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO, .pApplicationName = "Bannerlator device info",
                              .apiVersion = loader >= VK_API_VERSION_1_1 ? VK_API_VERSION_1_1 : VK_API_VERSION_1_0 };
    VkInstanceCreateInfo info = { .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pApplicationInfo = &app };
    VkInstance instance = VK_NULL_HANDLE;
    if (create(&info, NULL, &instance) != VK_SUCCESS) { LINE("error=vkCreateInstance failed\n"); dlclose(lib); return (*env)->NewStringUTF(env, out); }

#define GET(name) PFN_##name name = (PFN_##name) gipa(instance, #name)
    GET(vkDestroyInstance); GET(vkEnumeratePhysicalDevices); GET(vkGetPhysicalDeviceProperties);
    GET(vkGetPhysicalDeviceProperties2); GET(vkGetPhysicalDeviceMemoryProperties);
    GET(vkEnumerateDeviceExtensionProperties); GET(vkGetPhysicalDeviceFeatures);
    uint32_t count = 1;
    VkPhysicalDevice device = VK_NULL_HANDLE;
    VkResult listed = vkEnumeratePhysicalDevices ? vkEnumeratePhysicalDevices(instance, &count, &device) : VK_ERROR_INITIALIZATION_FAILED;
    if ((listed != VK_SUCCESS && listed != VK_INCOMPLETE) || count == 0 || !device || !vkGetPhysicalDeviceProperties) {
        LINE("error=no Vulkan device\n");
    } else {
        VkPhysicalDeviceProperties p;
        vkGetPhysicalDeviceProperties(device, &p);
        LINE("device=%s\n", p.deviceName);
        LINE("type=%s\n", type_name(p.deviceType));
        LINE("api=%u.%u.%u\n", VK_API_VERSION_MAJOR(p.apiVersion), VK_API_VERSION_MINOR(p.apiVersion), VK_API_VERSION_PATCH(p.apiVersion));
        LINE("driver_version=%u.%u.%u\n", p.driverVersion >> 22, (p.driverVersion >> 12) & 0x3ff, p.driverVersion & 0xfff);
        LINE("vendor_id=0x%04x\ndevice_id=0x%08x\n", p.vendorID, p.deviceID);
        LINE("max_image_2d=%u\n", p.limits.maxImageDimension2D);
        LINE("max_push_constants=%u\n", p.limits.maxPushConstantsSize);

        uint32_t extCount = 0;
        if (vkEnumerateDeviceExtensionProperties) vkEnumerateDeviceExtensionProperties(device, NULL, &extCount, NULL);
        static VkExtensionProperties exts[512];
        if (extCount > 512) extCount = 512;
        if (extCount && vkEnumerateDeviceExtensionProperties) vkEnumerateDeviceExtensionProperties(device, NULL, &extCount, exts);
        int driverProps = p.apiVersion >= VK_API_VERSION_1_2;
        for (uint32_t i = 0; i < extCount; i++)
            if (!strcmp(exts[i].extensionName, "VK_KHR_driver_properties")) driverProps = 1;
        if (driverProps && vkGetPhysicalDeviceProperties2) {
            VkPhysicalDeviceDriverProperties driver = { .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES };
            VkPhysicalDeviceProperties2 p2 = { .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2, .pNext = &driver };
            vkGetPhysicalDeviceProperties2(device, &p2);
            LINE("driver_name=%s\n", driver.driverName);
            LINE("driver_info=%s\n", driver.driverInfo);
            LINE("conformance=%u.%u.%u.%u\n", driver.conformanceVersion.major, driver.conformanceVersion.minor,
                 driver.conformanceVersion.subminor, driver.conformanceVersion.patch);
        }
        LINE("extension_count=%u\n", extCount);
        for (size_t e = 0; e < sizeof(EXTENSIONS) / sizeof(*EXTENSIONS); e++) {
            int has = 0;
            for (uint32_t i = 0; i < extCount && !has; i++) has = !strcmp(exts[i].extensionName, EXTENSIONS[e]);
            LINE("ext.%s=%s\n", EXTENSIONS[e], has ? "yes" : "no");
        }
        if (vkGetPhysicalDeviceFeatures) {
            VkPhysicalDeviceFeatures f;
            vkGetPhysicalDeviceFeatures(device, &f);
            LINE("geometry_shader=%s\ntessellation_shader=%s\ntexture_bc=%s\n",
                 f.geometryShader ? "yes" : "no", f.tessellationShader ? "yes" : "no", f.textureCompressionBC ? "yes" : "no");
            LINE("shader_int64=%s\n", f.shaderInt64 ? "yes" : "no");
        }
        if (vkGetPhysicalDeviceMemoryProperties) {
            VkPhysicalDeviceMemoryProperties m;
            vkGetPhysicalDeviceMemoryProperties(device, &m);
            VkDeviceSize largest = 0;
            for (uint32_t i = 0; i < m.memoryHeapCount; i++)
                if ((m.memoryHeaps[i].flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) && m.memoryHeaps[i].size > largest) largest = m.memoryHeaps[i].size;
            LINE("device_heap_mb=%llu\n", (unsigned long long) (largest >> 20));
        }
    }
    if (vkDestroyInstance) vkDestroyInstance(instance, NULL);
    dlclose(lib);
    return (*env)->NewStringUTF(env, out);
}
