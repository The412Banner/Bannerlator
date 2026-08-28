// Ported from WinNative PR #697 "Feature/lsfg frame gen" (GPL-3.0-or-later).
// Adapted for Bannerlator: self-contained host device-feature probe that opens
// the system Vulkan driver directly (no JNI driver-open helper) and reports each
// check individually for the LSFG-HOST Phase-0 diagnostic verdict.
#pragma once

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Per-check results for the host device-feature probe. All bool checks are the
// requirements the DXBC-translated (SPIR-V 1.6) LSFG shaders impose on the host
// Vulkan device. `supported` is the AND of every requirement.
typedef struct LsfgProbeResult {
    bool     driver_opened;                  // libvulkan.so opened + vkGetInstanceProcAddr found
    bool     instance_created;               // throwaway VkInstance created
    bool     have_device;                    // at least one physical device enumerated
    char     device_name[256];               // reported device (best candidate)
    uint32_t api_major;
    uint32_t api_minor;
    bool     vulkan_1_3;                     // apiVersion >= 1.3 (SPIR-V 1.6 needs it)
    bool     compute_queue;                  // a queue family with VK_QUEUE_COMPUTE_BIT
    bool     memory_model;                   // vulkanMemoryModel feature
    bool     storage_write_without_format;   // shaderStorageImageWriteWithoutFormat
    bool     storage_extended_formats;       // shaderStorageImageExtendedFormats
    bool     required_formats;               // R8G8B8A8/R8/R16G16B16A16 storage+sampled
    bool     supported;                      // all of the above
} LsfgProbeResult;

// Self-contained host device-feature probe. Opens the system Vulkan driver
// (libvulkan.so), creates a throwaway VkInstance, and checks the enumerated
// physical device(s) for the features the translated LSFG shaders require.
// Fills *out with per-check results and logs each PASS/FAIL. Returns
// out->supported. Never touches the compositor's device or lifecycle.
bool lsfg_probe_host(LsfgProbeResult* out);

#ifdef __cplusplus
}
#endif
