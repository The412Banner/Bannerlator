#pragma once
// ============================================================================
// lsfg_probe — capability gate for native (compositor-side) LSFG frame
// generation.
//
// The Lossless Scaling chain is 25 compute shaders that DXVK's DXBC translator
// emits as SPIR-V 1.6 with OpCapability VulkanMemoryModel and
// StorageImageWriteWithoutFormat. Three consequences, all checked here:
//
//   * SPIR-V 1.6 will not load on a Vulkan 1.1 device, so the DEVICE (not just
//     the instance) must report 1.3+ ... OR, on the experimental Vulkan 1.1
//     compat path, offer VK_KHR_spirv_1_4 + VK_KHR_vulkan_memory_model so the
//     same modules can be handed over as SPIR-V 1.4 (see downgradeSpirv in
//     lsfg_dll.h). The stock Qualcomm driver on Adreno 7xx phones reports
//     1.1.128 yet carries every one of those extensions (measured on an
//     Adreno 710 / HyperOS device with `cmd gpu vkjson`).
//   * vulkanMemoryModel, shaderStorageImageWriteWithoutFormat and
//     shaderStorageImageExtendedFormats must be ENABLED at device creation.
//     Today the renderer enables no features at all, so all three are off.
//   * `generate` writes into a storage image, and Android swapchain formats
//     are frequently not storage-capable — so the format is probed separately
//     once the swapchain has picked one.
//
// A device failing any gate reports unsupported UP FRONT, with a reason, so
// the UI can grey the engine out instead of failing later inside
// vkCreateShaderModule or vkCreateComputePipelines.
// ============================================================================

#include <vulkan/vulkan.h>
#include <cstdint>
#include <vector>

struct VkTable;

namespace lsfg {

// SPIR-V version words the engine may hand to vkCreateShaderModule.
constexpr uint32_t kSpirv14 = 0x00010400u;
constexpr uint32_t kSpirv15 = 0x00010500u;
constexpr uint32_t kSpirv16 = 0x00010600u;

// Which of the three required features the physical device OFFERS. Queried
// before vkCreateDevice; what we actually enable is recorded in Caps below.
struct FeatureSupport {
    bool queried                     = false;  // vkGetPhysicalDeviceFeatures2 resolved
    bool apiAtLeast13                = false;
    bool vulkanMemoryModel           = false;
    bool vulkanMemoryModelDeviceScope= false;
    bool storageImageWriteWithoutFormat = false;
    bool storageImageExtendedFormats = false;
    uint32_t deviceApiVersion        = 0;

    // Experimental Vulkan 1.1 compat: the device is below 1.3 but offers the
    // extensions that make the chain loadable. When set, the renderer must
    // enable those extensions at device creation (see extensionNames) and the
    // modules are downgraded to `spirvTarget` at load.
    bool     extensionPath   = false;
    bool     hasSpirv14      = false;   // VK_KHR_spirv_1_4 (+ VK_KHR_shader_float_controls)
    bool     hasMemoryModelExt = false; // VK_KHR_vulkan_memory_model
    uint32_t spirvTarget     = kSpirv16;

    // Every hard device-level gate passes (format is checked separately).
    bool deviceGatesPass() const {
        return queried && (apiAtLeast13 || extensionPath) && vulkanMemoryModel
            && storageImageWriteWithoutFormat && storageImageExtendedFormats;
    }
};

// What was actually enabled + the running verdict. Owned by the renderer.
struct Caps {
    FeatureSupport features;
    bool featuresEnabled   = false;  // the chain was passed to vkCreateDevice
    bool storageOnSwapchainFormat = false;
    VkFormat probedFormat  = VK_FORMAT_UNDEFINED;
    char reason[160]       = "not probed";

    // The single question the UI and the render path ask.
    bool supported() const {
        return featuresEnabled && features.deviceGatesPass() && storageOnSwapchainFormat;
    }
};

// Ask the physical device which of the required features it offers.
// Safe on any driver: if vkGetPhysicalDeviceFeatures2 cannot be resolved, or
// the device reports below Vulkan 1.2 and `allowVk11` is false, nothing is
// chained and `queried` is left false — the caller then creates the device
// exactly as it always has. With `allowVk11`, a 1.1 device is accepted when
// `deviceExtensions` lists VK_KHR_spirv_1_4, VK_KHR_shader_float_controls and
// VK_KHR_vulkan_memory_model, and the memory-model features are queried
// through the KHR struct instead of VkPhysicalDeviceVulkan12Features.
FeatureSupport queryFeatures(const VkTable& vk, VkPhysicalDevice pd,
                             const std::vector<VkExtensionProperties>& deviceExtensions,
                             bool allowVk11);

// The device extensions the extension path needs enabled, in the order they
// should be pushed onto VkDeviceCreateInfo. Empty unless extensionPath.
std::vector<const char*> extensionNames(const FeatureSupport& f);

// Probe VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT on the live swapchain format.
// `generate` writes into an image of this format via a compute dispatch.
bool probeStorageFormat(const VkTable& vk, VkPhysicalDevice pd, VkFormat fmt);

// Fill caps.reason with the FIRST gate that failed (or "supported").
void explain(Caps& caps);

} // namespace lsfg
