// See lsfg_vkd.h. Fills the chain's dispatch from the renderer's own table.

#include "lsfg_vkd.h"
#include "../VulkanRendererContext.h"

#include <android/log.h>

LsfgVkDispatch vkd;

namespace {
bool g_ready = false;
}

bool lsfgVkdInit(const VkTable& table) {
    bool ok = true;
#define COPY(fn)                                                                   \
    do {                                                                           \
        vkd.fn = table.fn;                                                         \
        if (!vkd.fn) {                                                             \
            __android_log_print(ANDROID_LOG_ERROR, "LsfgVkd",                      \
                                "entry point vk" #fn " did not resolve");          \
            ok = false;                                                            \
        }                                                                          \
    } while (0)

    COPY(AllocateDescriptorSets);
    COPY(AllocateMemory);
    COPY(BindBufferMemory);
    COPY(BindImageMemory);
    COPY(CmdBindDescriptorSets);
    COPY(CmdBindPipeline);
    COPY(CmdCopyImage);
    COPY(CmdDispatch);
    COPY(CmdPipelineBarrier);
    COPY(CreateBuffer);
    COPY(CreateComputePipelines);
    COPY(CreateDescriptorPool);
    COPY(CreateDescriptorSetLayout);
    COPY(CreateImage);
    COPY(CreateImageView);
    COPY(CreatePipelineLayout);
    COPY(CreateSampler);
    COPY(CreateShaderModule);
    COPY(DestroyBuffer);
    COPY(DestroyDescriptorPool);
    COPY(DestroyDescriptorSetLayout);
    COPY(DestroyImage);
    COPY(DestroyImageView);
    COPY(DestroyPipeline);
    COPY(DestroyPipelineLayout);
    COPY(DestroySampler);
    COPY(DestroyShaderModule);
    COPY(FreeMemory);
    COPY(GetBufferMemoryRequirements);
    COPY(GetImageMemoryRequirements);
    COPY(GetPhysicalDeviceMemoryProperties);
    COPY(MapMemory);
    COPY(UnmapMemory);
    COPY(UpdateDescriptorSets);
#undef COPY

    g_ready = ok;
    return ok;
}

bool lsfgVkdReady() { return g_ready; }
