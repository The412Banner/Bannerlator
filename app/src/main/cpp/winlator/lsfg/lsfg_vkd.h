#pragma once
// ============================================================================
// lsfg_vkd — the Vulkan entry points the LSFG chain calls, as a single global.
//
// Two dispatch styles meet here. Bannerlator's compositor resolves Vulkan
// through a per-context VkTable (`vk_`), because an adrenotools-loaded driver
// lives in an isolated linker namespace and shares no global symbols with the
// system loader. The LSFG chain, ported from eden by way of WinNative, calls
// through a file-scope `vkd`. Rather than rewrite ~2,300 lines of proven chain
// code to thread a table through every call, this shim exposes exactly the 34
// entry points the chain uses and is filled from the renderer's own table when
// the chain is created.
//
// That keeps the ported files close to their upstream form, which matters:
// they are the part of this feature that is hardest to review by eye and
// easiest to break with a well-meaning edit.
//
// Single-renderer assumption: there is one Vulkan compositor per process, so
// one global table is correct. lsfgVkdInit() is idempotent and simply
// overwrites; lsfgVkdReady() reports whether every pointer resolved.
// ============================================================================

#include <vulkan/vulkan.h>

struct VkTable;

struct LsfgVkDispatch {
    PFN_vkAllocateDescriptorSets      AllocateDescriptorSets      = nullptr;
    PFN_vkAllocateMemory              AllocateMemory              = nullptr;
    PFN_vkBindBufferMemory            BindBufferMemory            = nullptr;
    PFN_vkBindImageMemory             BindImageMemory             = nullptr;
    PFN_vkCmdBindDescriptorSets       CmdBindDescriptorSets       = nullptr;
    PFN_vkCmdBindPipeline             CmdBindPipeline             = nullptr;
    PFN_vkCmdCopyImage                CmdCopyImage                = nullptr;
    PFN_vkCmdDispatch                 CmdDispatch                 = nullptr;
    PFN_vkCmdPipelineBarrier          CmdPipelineBarrier          = nullptr;
    PFN_vkCreateBuffer                CreateBuffer                = nullptr;
    PFN_vkCreateComputePipelines      CreateComputePipelines      = nullptr;
    PFN_vkCreateDescriptorPool        CreateDescriptorPool        = nullptr;
    PFN_vkCreateDescriptorSetLayout   CreateDescriptorSetLayout   = nullptr;
    PFN_vkCreateImage                 CreateImage                 = nullptr;
    PFN_vkCreateImageView             CreateImageView             = nullptr;
    PFN_vkCreatePipelineLayout        CreatePipelineLayout        = nullptr;
    PFN_vkCreateSampler               CreateSampler               = nullptr;
    PFN_vkCreateShaderModule          CreateShaderModule          = nullptr;
    PFN_vkDestroyBuffer               DestroyBuffer               = nullptr;
    PFN_vkDestroyDescriptorPool       DestroyDescriptorPool       = nullptr;
    PFN_vkDestroyDescriptorSetLayout  DestroyDescriptorSetLayout  = nullptr;
    PFN_vkDestroyImage                DestroyImage                = nullptr;
    PFN_vkDestroyImageView            DestroyImageView            = nullptr;
    PFN_vkDestroyPipeline             DestroyPipeline             = nullptr;
    PFN_vkDestroyPipelineLayout       DestroyPipelineLayout       = nullptr;
    PFN_vkDestroySampler              DestroySampler              = nullptr;
    PFN_vkDestroyShaderModule         DestroyShaderModule         = nullptr;
    PFN_vkFreeMemory                  FreeMemory                  = nullptr;
    PFN_vkGetBufferMemoryRequirements GetBufferMemoryRequirements = nullptr;
    PFN_vkGetImageMemoryRequirements  GetImageMemoryRequirements  = nullptr;
    PFN_vkGetPhysicalDeviceMemoryProperties GetPhysicalDeviceMemoryProperties = nullptr;
    PFN_vkMapMemory                   MapMemory                   = nullptr;
    PFN_vkUnmapMemory                 UnmapMemory                 = nullptr;
    PFN_vkUpdateDescriptorSets        UpdateDescriptorSets        = nullptr;
};

extern LsfgVkDispatch vkd;

// Fill `vkd` from the renderer's table. Returns false (and leaves the chain
// unusable) if any entry point failed to resolve — better to report the engine
// unsupported than to call through a null pointer mid-frame.
bool lsfgVkdInit(const VkTable& table);
bool lsfgVkdReady();
