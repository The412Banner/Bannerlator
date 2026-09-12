// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Ported into Bannerlator's compositor from WinNative (GPL-3.0-or-later),
// whose LSFG port is credited to Camille LaVey / the Eden Emulator Project and
// follows upstream lsfg-vk. Only the Vulkan dispatch differs: Bannerlator
// resolves entry points through the renderer's own table (see lsfg_vkd.h).

#pragma once

#include <cstdint>
#include <map>
#include <string>
#include "lsfg_vkd.h"

namespace lsfg {

class Device;

class LsfgShaders {
public:
    LsfgShaders() = default;
    LsfgShaders(const Device& device, const std::string& cache_path);
    ~LsfgShaders();

    LsfgShaders(const LsfgShaders&) = delete;
    LsfgShaders& operator=(const LsfgShaders&) = delete;

    [[nodiscard]] bool IsValid() const {
        return valid;
    }

    [[nodiscard]] VkShaderModule Get(uint32_t shader_id) const;

private:
    void Release();

    VkDevice device{VK_NULL_HANDLE};
    std::map<uint32_t, VkShaderModule> modules;
    bool valid{};
};

}
