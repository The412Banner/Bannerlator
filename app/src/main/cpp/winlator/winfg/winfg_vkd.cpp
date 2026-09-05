// See winfg_vkd.h.
#include "winfg_vkd.h"
#include "../VulkanRendererContext.h"
#include <android/log.h>

bool winfgVkdInit(const VkTable& table, winfg::DeviceDispatch& dd, winfg::InstanceDispatch& id) {
    bool ok = true;
#define COPY(dst, fn)                                                                     \
    do {                                                                                  \
        dst.fn = table.fn;                                                                \
        if (!dst.fn) {                                                                    \
            __android_log_print(ANDROID_LOG_ERROR, "WinFgVkd",                            \
                                "entry point vk" #fn " did not resolve");                 \
            ok = false;                                                                   \
        }                                                                                 \
    } while (0)
    COPY(dd, AllocateDescriptorSets);
    COPY(dd, AllocateMemory);
    COPY(dd, BindBufferMemory);
    COPY(dd, BindImageMemory);
    COPY(dd, CmdBindDescriptorSets);
    COPY(dd, CmdBindPipeline);
    COPY(dd, CmdClearColorImage);
    COPY(dd, CmdCopyImage);
    COPY(dd, CmdDispatch);
    COPY(dd, CmdPipelineBarrier);
    COPY(dd, CreateBuffer);
    COPY(dd, CreateComputePipelines);
    COPY(dd, CreateDescriptorPool);
    COPY(dd, CreateDescriptorSetLayout);
    COPY(dd, CreateImage);
    COPY(dd, CreateImageView);
    COPY(dd, CreatePipelineLayout);
    COPY(dd, CreateSampler);
    COPY(dd, CreateShaderModule);
    COPY(dd, DestroyBuffer);
    COPY(dd, DestroyDescriptorPool);
    COPY(dd, DestroyDescriptorSetLayout);
    COPY(dd, DestroyImage);
    COPY(dd, DestroyImageView);
    COPY(dd, DestroyPipeline);
    COPY(dd, DestroyPipelineLayout);
    COPY(dd, DestroySampler);
    COPY(dd, DestroyShaderModule);
    COPY(dd, DeviceWaitIdle);
    COPY(dd, FreeMemory);
    COPY(dd, GetBufferMemoryRequirements);
    COPY(dd, GetImageMemoryRequirements);
    COPY(dd, MapMemory);
    COPY(dd, ResetDescriptorPool);
    COPY(dd, UnmapMemory);
    COPY(dd, UpdateDescriptorSets);
    COPY(id, GetPhysicalDeviceMemoryProperties);
#undef COPY
    return ok;
}
