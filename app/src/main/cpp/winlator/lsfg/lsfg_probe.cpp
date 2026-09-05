// See lsfg_probe.h. Capability gate for native compositor-side LSFG.

#include "lsfg_probe.h"
#include "../VulkanRendererContext.h"

#include <cstdio>
#include <cstring>

namespace lsfg {

FeatureSupport queryFeatures(const VkTable& vk, VkPhysicalDevice pd) {
    FeatureSupport fs{};
    if (pd == VK_NULL_HANDLE || !vk.GetPhysicalDeviceProperties) return fs;

    VkPhysicalDeviceProperties props{};
    vk.GetPhysicalDeviceProperties(pd, &props);
    fs.deviceApiVersion = props.apiVersion;
    fs.apiAtLeast13 = props.apiVersion >= VK_API_VERSION_1_3;

    // Below 1.2 there is no VkPhysicalDeviceVulkan12Features to chain, and
    // below 1.3 the SPIR-V 1.6 modules will not load anyway — so don't touch
    // the device further. Device creation stays exactly as it is today.
    if (props.apiVersion < VK_API_VERSION_1_2 || !vk.GetPhysicalDeviceFeatures2) return fs;

    VkPhysicalDeviceVulkan12Features v12{};
    v12.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES;

    VkPhysicalDeviceFeatures2 f2{};
    f2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    f2.pNext = &v12;

    vk.GetPhysicalDeviceFeatures2(pd, &f2);

    fs.queried = true;
    fs.vulkanMemoryModel            = v12.vulkanMemoryModel == VK_TRUE;
    fs.vulkanMemoryModelDeviceScope = v12.vulkanMemoryModelDeviceScope == VK_TRUE;
    fs.storageImageWriteWithoutFormat = f2.features.shaderStorageImageWriteWithoutFormat == VK_TRUE;
    fs.storageImageExtendedFormats    = f2.features.shaderStorageImageExtendedFormats == VK_TRUE;
    return fs;
}

bool probeStorageFormat(const VkTable& vk, VkPhysicalDevice pd, VkFormat fmt) {
    if (pd == VK_NULL_HANDLE || fmt == VK_FORMAT_UNDEFINED
        || !vk.GetPhysicalDeviceFormatProperties) return false;

    VkFormatProperties fp{};
    vk.GetPhysicalDeviceFormatProperties(pd, fmt, &fp);

    // The composite target is COLOR_ATTACHMENT (effect chain writes it),
    // SAMPLED (next frame's LSFG input reads it), STORAGE (generate writes it)
    // and both blit ends (it is copied into the swapchain image).
    const VkFormatFeatureFlags need =
          VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT
        | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT
        | VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT
        | VK_FORMAT_FEATURE_BLIT_SRC_BIT
        | VK_FORMAT_FEATURE_BLIT_DST_BIT;

    return (fp.optimalTilingFeatures & need) == need;
}

void explain(Caps& caps) {
    const FeatureSupport& f = caps.features;
    const char* why = nullptr;

    if (!f.queried) {
        why = f.deviceApiVersion < VK_API_VERSION_1_2
            ? "device Vulkan version below 1.2"
            : "vkGetPhysicalDeviceFeatures2 unavailable";
    } else if (!f.apiAtLeast13) {
        why = "device Vulkan version below 1.3 (SPIR-V 1.6 will not load)";
    } else if (!f.vulkanMemoryModel) {
        why = "driver lacks vulkanMemoryModel";
    } else if (!f.storageImageWriteWithoutFormat) {
        why = "driver lacks shaderStorageImageWriteWithoutFormat";
    } else if (!f.storageImageExtendedFormats) {
        why = "driver lacks shaderStorageImageExtendedFormats";
    } else if (!caps.featuresEnabled) {
        why = "required features not enabled at device creation";
    } else if (!caps.storageOnSwapchainFormat) {
        why = "swapchain format is not storage-image capable";
    }

    if (why) {
        snprintf(caps.reason, sizeof(caps.reason), "unsupported: %s", why);
    } else {
        snprintf(caps.reason, sizeof(caps.reason),
                 "supported (device Vulkan %u.%u.%u, fmt %d)",
                 VK_VERSION_MAJOR(f.deviceApiVersion),
                 VK_VERSION_MINOR(f.deviceApiVersion),
                 VK_VERSION_PATCH(f.deviceApiVersion),
                 (int)caps.probedFormat);
    }
}

} // namespace lsfg
