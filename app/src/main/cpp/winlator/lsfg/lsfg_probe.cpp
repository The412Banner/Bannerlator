// See lsfg_probe.h. Capability gate for native compositor-side LSFG.

#include "lsfg_probe.h"
#include "../VulkanRendererContext.h"

#include <cstdio>
#include <cstring>

namespace lsfg {

namespace {

bool hasExtension(const std::vector<VkExtensionProperties>& exts, const char* name) {
    for (const auto& e : exts)
        if (strcmp(e.extensionName, name) == 0) return true;
    return false;
}

} // namespace

FeatureSupport queryFeatures(const VkTable& vk, VkPhysicalDevice pd,
                             const std::vector<VkExtensionProperties>& deviceExtensions,
                             bool allowVk11) {
    FeatureSupport fs{};
    if (pd == VK_NULL_HANDLE || !vk.GetPhysicalDeviceProperties) return fs;

    VkPhysicalDeviceProperties props{};
    vk.GetPhysicalDeviceProperties(pd, &props);
    fs.deviceApiVersion = props.apiVersion;
    fs.apiAtLeast13 = props.apiVersion >= VK_API_VERSION_1_3;

    if (!vk.GetPhysicalDeviceFeatures2) return fs;

    const bool below12 = props.apiVersion < VK_API_VERSION_1_2;
    if (below12) {
        // Below 1.2 there is no VkPhysicalDeviceVulkan12Features to chain, and
        // the SPIR-V 1.6 modules will not load anyway. Unless the experimental
        // compat path is on AND the driver offers the extensions that make the
        // chain loadable as SPIR-V 1.4, don't touch the device further:
        // device creation stays exactly as it is today.
        if (!allowVk11) return fs;
        fs.hasSpirv14 = hasExtension(deviceExtensions, VK_KHR_SPIRV_1_4_EXTENSION_NAME)
                     && hasExtension(deviceExtensions, VK_KHR_SHADER_FLOAT_CONTROLS_EXTENSION_NAME);
        fs.hasMemoryModelExt = hasExtension(deviceExtensions, VK_KHR_VULKAN_MEMORY_MODEL_EXTENSION_NAME);
        if (!fs.hasSpirv14 || !fs.hasMemoryModelExt) {
            // Report what was looked at so explain() can name the gap.
            fs.queried = true;
            return fs;
        }
        fs.extensionPath = true;
        fs.spirvTarget = kSpirv14;
    } else if (!fs.apiAtLeast13 && allowVk11) {
        // 1.2: SPIR-V 1.5 is core, VulkanMemoryModel is core. Nothing to
        // enable beyond the features; the modules only need their header
        // lowered from 1.6 to 1.5. Same experimental gate as the 1.1 path so
        // a production build keeps rejecting 1.2 exactly as before.
        fs.extensionPath = true;
        fs.spirvTarget = kSpirv15;
    }

    // On 1.2+ the Vulkan12Features struct carries the memory model; below
    // that the KHR struct does (same sType value, same layout).
    VkPhysicalDeviceVulkan12Features v12{};
    v12.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES;
    VkPhysicalDeviceVulkanMemoryModelFeaturesKHR mm{};
    mm.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_MEMORY_MODEL_FEATURES_KHR;

    VkPhysicalDeviceFeatures2 f2{};
    f2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    f2.pNext = below12 ? (void*)&mm : (void*)&v12;

    vk.GetPhysicalDeviceFeatures2(pd, &f2);

    fs.queried = true;
    if (below12) {
        fs.vulkanMemoryModel            = mm.vulkanMemoryModel == VK_TRUE;
        fs.vulkanMemoryModelDeviceScope = mm.vulkanMemoryModelDeviceScope == VK_TRUE;
    } else {
        fs.vulkanMemoryModel            = v12.vulkanMemoryModel == VK_TRUE;
        fs.vulkanMemoryModelDeviceScope = v12.vulkanMemoryModelDeviceScope == VK_TRUE;
    }
    fs.storageImageWriteWithoutFormat = f2.features.shaderStorageImageWriteWithoutFormat == VK_TRUE;
    fs.storageImageExtendedFormats    = f2.features.shaderStorageImageExtendedFormats == VK_TRUE;
    return fs;
}

std::vector<const char*> extensionNames(const FeatureSupport& f) {
    std::vector<const char*> out;
    if (!f.extensionPath || f.deviceApiVersion >= VK_API_VERSION_1_2) return out;
    out.push_back(VK_KHR_SHADER_FLOAT_CONTROLS_EXTENSION_NAME);   // dependency of spirv_1_4
    out.push_back(VK_KHR_SPIRV_1_4_EXTENSION_NAME);
    out.push_back(VK_KHR_VULKAN_MEMORY_MODEL_EXTENSION_NAME);
    return out;
}

bool probeStorageFormat(const VkTable& vk, VkPhysicalDevice pd, VkFormat fmt) {
    if (pd == VK_NULL_HANDLE || fmt == VK_FORMAT_UNDEFINED
        || !vk.GetPhysicalDeviceFormatProperties) return false;

    VkFormatProperties fp{};
    vk.GetPhysicalDeviceFormatProperties(pd, fmt, &fp);

    // The composite target is COLOR_ATTACHMENT (effect chain writes it),
    // SAMPLED (next frame's LSFG input reads it), STORAGE (generate writes it)
    // and both blit ends (it is copied — or, below panel resolution, blitted —
    // into the swapchain image).
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
    } else if (!f.apiAtLeast13 && !f.extensionPath) {
        if (f.deviceApiVersion < VK_API_VERSION_1_2) {
            why = !f.hasSpirv14
                ? "Vulkan 1.1 driver lacks VK_KHR_spirv_1_4"
                : "Vulkan 1.1 driver lacks VK_KHR_vulkan_memory_model";
        } else {
            why = "device Vulkan version below 1.3 (SPIR-V 1.6 will not load)";
        }
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
                 "supported (device Vulkan %u.%u.%u%s, fmt %d)",
                 VK_VERSION_MAJOR(f.deviceApiVersion),
                 VK_VERSION_MINOR(f.deviceApiVersion),
                 VK_VERSION_PATCH(f.deviceApiVersion),
                 f.extensionPath ? " via extensions, SPIR-V lowered" : "",
                 (int)caps.probedFormat);
    }
}

} // namespace lsfg
