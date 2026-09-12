// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Ported into Bannerlator's compositor from WinNative (GPL-3.0-or-later),
// whose LSFG port is credited to Camille LaVey / the Eden Emulator Project and
// follows upstream lsfg-vk. Only the Vulkan dispatch differs: Bannerlator
// resolves entry points through the renderer's own table (see lsfg_vkd.h).

#include "lsfg_shaders.hpp"
#include "lsfg_common.hpp"
#include "lsfg_dll.h"

#include <android/log.h>

#define LOG_TAG "LsfgShaders"
#define SHADER_LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define SHADER_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace lsfg {

LsfgShaders::LsfgShaders(const Device& device_, const std::string& cache_path,
                         uint32_t spirv_target)
    : device{device_.Handle()} {
    ModuleSet set;
    const DllStatus status = loadModules(cache_path, set);
    if (status != DllStatus::Ok) {
        SHADER_LOGE("Shader cache unusable (%s)", statusName(status));
        return;
    }

    // Vulkan 1.1/1.2 compat: the cache is always built at the translator's
    // native SPIR-V 1.6 (no device exists at import time), so lower each module
    // to what THIS device accepts. A no-op on 1.3+ devices. Only the
    // DXBC-translated chain is lowered: downgradeSpirv knows the capabilities
    // DXVK emits, not whatever a precompiled vendor SPIR-V variant may carry,
    // and relabelling one of those could hand the driver invalid SPIR-V.
    uint32_t lowered = 0;
    for (Module& module : set.modules) {
        if (module.words.size() > 1 && module.words[1] > spirv_target) {
            if (set.variant != Variant::DxbcTranslated) {
                SHADER_LOGE("shader %u: precompiled SPIR-V 0x%x is above this device's 0x%x; not lowering",
                            module.id, module.words[1], spirv_target);
                return;
            }
            if (!downgradeSpirv(module.words, spirv_target)) {
                SHADER_LOGE("shader %u: could not lower SPIR-V 0x%x to 0x%x",
                            module.id, module.words[1], spirv_target);
                return;
            }
            lowered++;
        }
    }
    if (lowered)
        SHADER_LOGI("Lowered %u modules to SPIR-V 0x%x for this device", lowered, spirv_target);

    for (const Module& module : set.modules) {
        VkShaderModuleCreateInfo module_ci{};
        module_ci.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
        module_ci.codeSize = module.words.size() * sizeof(uint32_t);
        module_ci.pCode = module.words.data();

        VkShaderModule handle = VK_NULL_HANDLE;
        if (vkd.CreateShaderModule(device, &module_ci, nullptr, &handle) != VK_SUCCESS) {
            SHADER_LOGE("vkCreateShaderModule failed for shader %u", module.id);
            Release();
            return;
        }
        modules.emplace(module.id, handle);
    }

    valid = modules.size() == kShaderCount;
    if (valid) {
        SHADER_LOGI("Created %zu LSFG shader modules, variant=%s", modules.size(),
                    variantName(set.variant));
    } else {
        SHADER_LOGE("Expected %u shader modules, got %zu", kShaderCount, modules.size());
        Release();
    }
}

LsfgShaders::~LsfgShaders() {
    Release();
}

void LsfgShaders::Release() {
    if (device != VK_NULL_HANDLE) {
        for (auto& [id, module] : modules) {
            vkd.DestroyShaderModule(device, module, nullptr);
        }
    }
    modules.clear();
    valid = false;
}

VkShaderModule LsfgShaders::Get(uint32_t shader_id) const {
    const auto it = modules.find(shader_id);
    return it == modules.end() ? VK_NULL_HANDLE : it->second;
}

}
