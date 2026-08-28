// Ported from WinNative PR #697 "Feature/lsfg frame gen" (GPL-3.0-or-later).
// Adapted for Bannerlator: self-contained host device-feature probe. WinNative's
// original resolved the driver through a JNI helper (winlator_open_vulkan); here
// we open the system Vulkan loader directly and record each requirement result so
// the LSFG-HOST diagnostic can print a per-check verdict. The checks themselves
// (Vulkan 1.3, vulkanMemoryModel, shaderStorageImageWriteWithoutFormat,
// shaderStorageImageExtendedFormats, compute queue, storage/sampled formats) are
// unchanged — they are what the DXBC-translated SPIR-V 1.6 shaders require.
#include "lsfg_probe.h"

#include <android/log.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <vulkan/vulkan.h>

#define LOG_TAG "LSFG-HOST"
#define PROBE_LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define PROBE_LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

#define REQUIRED_FORMAT_FEATURES \
    (VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT)

#define MAX_PROBE_DEVICES 8
#define MAX_PROBE_QUEUE_FAMILIES 16

typedef struct ProbeApi {
    PFN_vkGetInstanceProcAddr GetInstanceProcAddr;
    PFN_vkCreateInstance CreateInstance;
    PFN_vkDestroyInstance DestroyInstance;
    PFN_vkEnumeratePhysicalDevices EnumeratePhysicalDevices;
    PFN_vkGetPhysicalDeviceProperties GetPhysicalDeviceProperties;
    PFN_vkGetPhysicalDeviceFormatProperties GetPhysicalDeviceFormatProperties;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties GetPhysicalDeviceQueueFamilyProperties;
    PFN_vkGetPhysicalDeviceFeatures2 GetPhysicalDeviceFeatures2;
} ProbeApi;

static const VkFormat kRequiredFormats[] = {
    VK_FORMAT_R8G8B8A8_UNORM,
    VK_FORMAT_R8_UNORM,
    VK_FORMAT_R16G16B16A16_SFLOAT,
};

static bool load_instance_api(ProbeApi* api, VkInstance instance) {
    api->DestroyInstance =
        (PFN_vkDestroyInstance)api->GetInstanceProcAddr(instance, "vkDestroyInstance");
    api->EnumeratePhysicalDevices = (PFN_vkEnumeratePhysicalDevices)api->GetInstanceProcAddr(
        instance, "vkEnumeratePhysicalDevices");
    api->GetPhysicalDeviceProperties = (PFN_vkGetPhysicalDeviceProperties)api->GetInstanceProcAddr(
        instance, "vkGetPhysicalDeviceProperties");
    api->GetPhysicalDeviceFormatProperties =
        (PFN_vkGetPhysicalDeviceFormatProperties)api->GetInstanceProcAddr(
            instance, "vkGetPhysicalDeviceFormatProperties");
    api->GetPhysicalDeviceQueueFamilyProperties =
        (PFN_vkGetPhysicalDeviceQueueFamilyProperties)api->GetInstanceProcAddr(
            instance, "vkGetPhysicalDeviceQueueFamilyProperties");
    api->GetPhysicalDeviceFeatures2 = (PFN_vkGetPhysicalDeviceFeatures2)api->GetInstanceProcAddr(
        instance, "vkGetPhysicalDeviceFeatures2");

    return api->DestroyInstance && api->EnumeratePhysicalDevices &&
           api->GetPhysicalDeviceProperties && api->GetPhysicalDeviceFormatProperties &&
           api->GetPhysicalDeviceQueueFamilyProperties && api->GetPhysicalDeviceFeatures2;
}

static bool has_compute_queue(const ProbeApi* api, VkPhysicalDevice device) {
    uint32_t count = 0;
    api->GetPhysicalDeviceQueueFamilyProperties(device, &count, NULL);
    if (count == 0) return false;
    if (count > MAX_PROBE_QUEUE_FAMILIES) count = MAX_PROBE_QUEUE_FAMILIES;

    VkQueueFamilyProperties families[MAX_PROBE_QUEUE_FAMILIES];
    api->GetPhysicalDeviceQueueFamilyProperties(device, &count, families);

    for (uint32_t i = 0; i < count; i++) {
        if (families[i].queueCount > 0 && (families[i].queueFlags & VK_QUEUE_COMPUTE_BIT)) {
            return true;
        }
    }
    return false;
}

static bool has_required_formats(const ProbeApi* api, VkPhysicalDevice device) {
    const size_t format_count = sizeof(kRequiredFormats) / sizeof(kRequiredFormats[0]);
    for (size_t i = 0; i < format_count; i++) {
        VkFormatProperties properties;
        memset(&properties, 0, sizeof(properties));
        api->GetPhysicalDeviceFormatProperties(device, kRequiredFormats[i], &properties);
        if ((properties.optimalTilingFeatures & REQUIRED_FORMAT_FEATURES) !=
            REQUIRED_FORMAT_FEATURES) {
            return false;
        }
    }
    return true;
}

// Fill *r for one physical device. Returns r->supported.
static bool probe_device(const ProbeApi* api, VkPhysicalDevice device, LsfgProbeResult* r) {
    r->have_device = true;

    VkPhysicalDeviceProperties props;
    memset(&props, 0, sizeof(props));
    api->GetPhysicalDeviceProperties(device, &props);
    snprintf(r->device_name, sizeof(r->device_name), "%s", props.deviceName);
    r->api_major = VK_API_VERSION_MAJOR(props.apiVersion);
    r->api_minor = VK_API_VERSION_MINOR(props.apiVersion);
    r->vulkan_1_3 = props.apiVersion >= VK_API_VERSION_1_3;

    r->compute_queue = has_compute_queue(api, device);
    r->required_formats = has_required_formats(api, device);

    VkPhysicalDeviceVulkanMemoryModelFeatures memory_model;
    memset(&memory_model, 0, sizeof(memory_model));
    memory_model.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_MEMORY_MODEL_FEATURES;
    VkPhysicalDeviceFeatures2 features;
    memset(&features, 0, sizeof(features));
    features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    features.pNext = &memory_model;
    api->GetPhysicalDeviceFeatures2(device, &features);

    r->memory_model = memory_model.vulkanMemoryModel != VK_FALSE;
    r->storage_write_without_format =
        features.features.shaderStorageImageWriteWithoutFormat != VK_FALSE;
    r->storage_extended_formats =
        features.features.shaderStorageImageExtendedFormats != VK_FALSE;

    r->supported = r->vulkan_1_3 && r->compute_queue && r->required_formats && r->memory_model &&
                   r->storage_write_without_format && r->storage_extended_formats;
    return r->supported;
}

bool lsfg_probe_host(LsfgProbeResult* out) {
    if (!out) return false;
    memset(out, 0, sizeof(*out));

    void* library = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!library) {
        PROBE_LOGW("probe: dlopen(libvulkan.so) failed: %s", dlerror());
        return false;
    }
    out->driver_opened = true;

    ProbeApi api;
    memset(&api, 0, sizeof(api));
    api.GetInstanceProcAddr = (PFN_vkGetInstanceProcAddr)dlsym(library, "vkGetInstanceProcAddr");
    if (!api.GetInstanceProcAddr) {
        PROBE_LOGW("probe: vkGetInstanceProcAddr not found in libvulkan.so");
        dlclose(library);
        return false;
    }
    api.CreateInstance =
        (PFN_vkCreateInstance)api.GetInstanceProcAddr(VK_NULL_HANDLE, "vkCreateInstance");
    if (!api.CreateInstance) {
        PROBE_LOGW("probe: vkCreateInstance unresolved");
        dlclose(library);
        return false;
    }

    VkApplicationInfo app_info;
    memset(&app_info, 0, sizeof(app_info));
    app_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app_info.pApplicationName = "Bannerlator";
    app_info.apiVersion = VK_API_VERSION_1_3;

    VkInstanceCreateInfo create_info;
    memset(&create_info, 0, sizeof(create_info));
    create_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    create_info.pApplicationInfo = &app_info;

    VkInstance instance = VK_NULL_HANDLE;
    if (api.CreateInstance(&create_info, NULL, &instance) != VK_SUCCESS) {
        app_info.apiVersion = VK_API_VERSION_1_1;
        if (api.CreateInstance(&create_info, NULL, &instance) != VK_SUCCESS) {
            PROBE_LOGW("probe: vkCreateInstance failed");
            dlclose(library);
            return false;
        }
    }
    out->instance_created = true;

    if (load_instance_api(&api, instance)) {
        uint32_t device_count = 0;
        if (api.EnumeratePhysicalDevices(instance, &device_count, NULL) == VK_SUCCESS &&
            device_count > 0) {
            if (device_count > MAX_PROBE_DEVICES) device_count = MAX_PROBE_DEVICES;
            VkPhysicalDevice devices[MAX_PROBE_DEVICES];
            if (api.EnumeratePhysicalDevices(instance, &device_count, devices) == VK_SUCCESS) {
                LsfgProbeResult best;
                bool have_best = false;
                for (uint32_t i = 0; i < device_count; i++) {
                    LsfgProbeResult r;
                    memset(&r, 0, sizeof(r));
                    r.driver_opened = out->driver_opened;
                    r.instance_created = out->instance_created;
                    const bool ok = probe_device(&api, devices[i], &r);
                    if (!have_best || ok) {
                        best = r;
                        have_best = true;
                        if (ok) break;  // first fully-supported device wins
                    }
                }
                if (have_best) *out = best;
            }
        } else {
            PROBE_LOGW("probe: no physical devices enumerated");
        }
    } else {
        PROBE_LOGW("probe: instance-level entry points unavailable");
    }

    if (api.DestroyInstance) api.DestroyInstance(instance, NULL);
    dlclose(library);
    return out->supported;
}
