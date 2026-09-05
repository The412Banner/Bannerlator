// win-fg — compute frame-generation engine implementation.
#include "framegen.hpp"
#include "embedded_shaders.hpp"
#include "log.hpp"
#include <cmath>
#include <cstdio>

namespace winfg {

#define VKOK(x) do { if ((x) != VK_SUCCESS) { WFG_LOGE("vk fail: %s", #x); return false; } } while (0)

uint32_t FrameGen::findMemType(uint32_t bits, VkMemoryPropertyFlags props) const {
    for (uint32_t i = 0; i < memProps_.memoryTypeCount; ++i)
        if ((bits & (1u << i)) && (memProps_.memoryTypes[i].propertyFlags & props) == props)
            return i;
    return 0;
}

bool FrameGen::makeImage(Img& out, VkExtent2D ext, VkFormat fmt, VkImageUsageFlags usage) {
    out.extent = ext; out.fmt = fmt;
    VkImageCreateInfo ci{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ci.imageType = VK_IMAGE_TYPE_2D;
    ci.format = fmt;
    ci.extent = {ext.width, ext.height, 1};
    ci.mipLevels = 1; ci.arrayLayers = 1;
    ci.samples = VK_SAMPLE_COUNT_1_BIT;
    ci.tiling = VK_IMAGE_TILING_OPTIMAL;
    ci.usage = usage;
    ci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ci.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    VKOK(dd_->CreateImage(device_, &ci, nullptr, &out.image));
    VkMemoryRequirements mr; dd_->GetImageMemoryRequirements(device_, out.image, &mr);
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    ai.allocationSize = mr.size;
    ai.memoryTypeIndex = findMemType(mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    VKOK(dd_->AllocateMemory(device_, &ai, nullptr, &out.mem));
    VKOK(dd_->BindImageMemory(device_, out.image, out.mem, 0));
    VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vi.image = out.image; vi.viewType = VK_IMAGE_VIEW_TYPE_2D; vi.format = fmt;
    vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    VKOK(dd_->CreateImageView(device_, &vi, nullptr, &out.view));
    return true;
}

void FrameGen::destroyImage(Img& i) {
    if (i.view) dd_->DestroyImageView(device_, i.view, nullptr);
    if (i.image) dd_->DestroyImage(device_, i.image, nullptr);
    if (i.mem) dd_->FreeMemory(device_, i.mem, nullptr);
    i = Img{};
}

// C1: allocate the per-slot host-visible SSBOs the LK reduce writes partials into.
// Host-visible + coherent so the CPU can read them back after the owning frame's
// fence signals (no staging copy needed; the buffers are tiny — kGmThreads*kGmStride
// floats each, ~64 KB). Persistently mapped.
bool FrameGen::makeGmBuffers() {
    const VkDeviceSize sz = (VkDeviceSize)kGmThreads * kGmStride * sizeof(float);
    const VkMemoryPropertyFlags want = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
    for (int s = 0; s < kGmSlots; ++s) {
        VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        bi.size = sz; bi.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT; bi.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        VKOK(dd_->CreateBuffer(device_, &bi, nullptr, &gmSsbo_[s]));
        VkMemoryRequirements mr; dd_->GetBufferMemoryRequirements(device_, gmSsbo_[s], &mr);
        VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        ai.allocationSize = mr.size; ai.memoryTypeIndex = findMemType(mr.memoryTypeBits, want);
        VKOK(dd_->AllocateMemory(device_, &ai, nullptr, &gmSsboMem_[s]));
        VKOK(dd_->BindBufferMemory(device_, gmSsbo_[s], gmSsboMem_[s], 0));
        VKOK(dd_->MapMemory(device_, gmSsboMem_[s], 0, sz, 0, &gmSsboPtr_[s]));
        std::memset(gmSsboPtr_[s], 0, (size_t)sz);
        gmSlotValid_[s] = false;
    }
    gmBuffersReady_ = true;
    return true;
}

bool FrameGen::makePipe(Pipe& p, int shaderId,
                        const std::vector<VkDescriptorType>&) {
    // binding layout is derived per shader below in init(); here we only build
    // the module + a generic layout supplied by caller via setLayout already set.
    embedded::Blob b = embedded::blob((embedded::Shader)shaderId);
    VkShaderModuleCreateInfo mi{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    mi.codeSize = b.words * 4; mi.pCode = b.code;
    VKOK(dd_->CreateShaderModule(device_, &mi, nullptr, &p.module));
    VkPipelineLayoutCreateInfo li{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    li.setLayoutCount = 1; li.pSetLayouts = &p.setLayout;
    VKOK(dd_->CreatePipelineLayout(device_, &li, nullptr, &p.layout));
    VkComputePipelineCreateInfo pi{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
    pi.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    pi.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pi.stage.module = p.module; pi.stage.pName = "main";
    pi.layout = p.layout;
    VKOK(dd_->CreateComputePipelines(device_, VK_NULL_HANDLE, 1, &pi, nullptr, &p.pipeline));
    return true;
}

void FrameGen::destroyPipe(Pipe& p) {
    if (p.pipeline) dd_->DestroyPipeline(device_, p.pipeline, nullptr);
    if (p.layout) dd_->DestroyPipelineLayout(device_, p.layout, nullptr);
    if (p.setLayout) dd_->DestroyDescriptorSetLayout(device_, p.setLayout, nullptr);
    if (p.module) dd_->DestroyShaderModule(device_, p.module, nullptr);
    p = Pipe{};
}

// Build a descriptor-set layout from an explicit (binding, type) list.
static bool buildSetLayout(const DeviceDispatch* dd, VkDevice dev,
                           const std::vector<std::pair<uint32_t, VkDescriptorType>>& binds,
                           VkDescriptorSetLayout& out) {
    std::vector<VkDescriptorSetLayoutBinding> b;
    for (auto& kv : binds)
        b.push_back({kv.first, kv.second, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr});
    VkDescriptorSetLayoutCreateInfo ci{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    ci.bindingCount = (uint32_t)b.size(); ci.pBindings = b.data();
    return dd->CreateDescriptorSetLayout(dev, &ci, nullptr, &out) == VK_SUCCESS;
}

bool FrameGen::init(const DeviceDispatch* dd, const InstanceDispatch* id,
                    VkPhysicalDevice phys, VkDevice dev, uint32_t queueFamily, VkQueue queue) {
    dd_ = dd; id_ = id; phys_ = phys; device_ = dev; queueFamily_ = queueFamily; queue_ = queue;
    id_->GetPhysicalDeviceMemoryProperties(phys_, &memProps_);

    VkSamplerCreateInfo si{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    si.magFilter = si.minFilter = VK_FILTER_LINEAR;
    si.addressModeU = si.addressModeV = si.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    VKOK(dd_->CreateSampler(device_, &si, nullptr, &linear_));

    // descriptor pool sized generously for our per-frame sets.
    VkDescriptorPoolSize sizes[] = {
        {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 256},
        {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 256},
        {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 64},
        {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 16},   // C1 LK-reduce SSBOs
    };
    VkDescriptorPoolCreateInfo pci{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    pci.flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
    pci.maxSets = 256; pci.poolSizeCount = 4; pci.pPoolSizes = sizes;
    VKOK(dd_->CreateDescriptorPool(device_, &pci, nullptr, &descPool_));

    using B = std::pair<uint32_t, VkDescriptorType>;
    const auto S = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    const auto W = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    const auto U = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    const auto SB = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;

    // per-shader binding sets (match the GLSL layout(binding=...) declarations)
    if (!buildSetLayout(dd_, dev, {B{32,S},B{48,W}}, pLuma_.setLayout)) return false;
    if (!buildSetLayout(dd_, dev, {B{32,S},B{48,W}}, pDown_.setLayout)) return false;
    if (!buildSetLayout(dd_, dev, {B{0,U},B{32,S},B{33,S},B{34,S},B{48,W}}, pFlow_.setLayout)) return false;
    if (!buildSetLayout(dd_, dev, {B{0,U},B{32,S},B{33,S},B{34,S},B{48,W}}, pFlowM4_.setLayout)) return false;
    // C1: expand sets gain a second UBO (binding 1 = GMUBO) for the global-motion add-back.
    if (!buildSetLayout(dd_, dev, {B{0,U},B{1,U},B{32,S},B{48,W},B{49,W}}, pExpand_.setLayout)) return false;
    if (!buildSetLayout(dd_, dev, {B{0,U},B{1,U},B{32,S},B{48,W},B{49,W}}, pExpandM4_.setLayout)) return false;
    if (!buildSetLayout(dd_, dev, {B{0,U},B{32,S},B{33,S},B{34,S},B{35,S},B{48,W}}, pSynth_.setLayout)) return false;
    // C1: LK-reduce (UBO, SSBO, prev, curr) and curr-luma stabilization prewarp (UBO, src, dst).
    if (!buildSetLayout(dd_, dev, {B{1,U},B{16,SB},B{32,S},B{33,S}}, pGmReduce_.setLayout)) return false;
    if (!buildSetLayout(dd_, dev, {B{1,U},B{32,S},B{48,W}}, pGmPrewarp_.setLayout)) return false;
    // C2: flow-reg (UBO, u_in, f0_obs, luma, out).
    if (!buildSetLayout(dd_, dev, {B{0,U},B{32,S},B{33,S},B{34,S},B{48,W}}, pFlowReg_.setLayout)) return false;

    std::vector<VkDescriptorType> unused;
    if (!makePipe(pLuma_,     embedded::OF3_LUMA, unused)) return false;
    if (!makePipe(pDown_,     embedded::OF3_DOWNSAMPLE, unused)) return false;
    if (!makePipe(pFlow_,     embedded::OF3_FLOW, unused)) return false;
    if (!makePipe(pFlowM4_,   embedded::OF3_FLOW_M4, unused)) return false;
    if (!makePipe(pExpand_,   embedded::OF3_EXPAND, unused)) return false;
    if (!makePipe(pExpandM4_, embedded::OF3_EXPAND_M4, unused)) return false;
    if (!makePipe(pGmReduce_,  embedded::OF3_GM_REDUCE,  unused)) return false;
    if (!makePipe(pGmPrewarp_, embedded::OF3_GM_PREWARP, unused)) return false;
    if (!makePipe(pFlowReg_,  embedded::OF3_FLOWREG, unused)) return false;
    if (!makePipe(pSynth_,    embedded::WFG_SYNTH, unused)) return false;
    if (!makeGmBuffers()) { WFG_LOGE("framegen: GM SSBO alloc failed — C1 disabled"); }
    WFG_LOGI("framegen init ok (queueFamily=%u, 10 pipelines, model=%d, C1 gm=%d, C2 flow_reg=%d iters=%d)",
             queueFamily_, cfg_.model, cfg_.gmMode, cfg_.frMode, cfg_.frIters);
    return true;
}

static VkExtent2D levelExtent(VkExtent2D e, int l) {
    return { e.width >> l ? e.width >> l : 1u, e.height >> l ? e.height >> l : 1u };
}

bool FrameGen::onResize(VkExtent2D extent, VkFormat /*colorFormat*/, bool force) {
    // force skips the same-extent early-out so a perf_preset change (which alters
    // flowFinest_ and therefore the per-size flow-image resolutions) rebuilds them.
    if (!force && ready_ && extent.width == extent_.width && extent.height == extent_.height) return true;
    // tear down previous size
    for (auto& i : pyrA_) destroyImage(i); pyrA_.clear();
    for (auto& i : pyrB_) destroyImage(i); pyrB_.clear();
    for (auto& i : pyrAs_) destroyImage(i); pyrAs_.clear();
    for (auto& i : flowLvl_) destroyImage(i); flowLvl_.clear();
    destroyImage(flowExpA_); destroyImage(flowExpB_);
    destroyImage(flowRegA_); destroyImage(flowRegB_);   // C2
    extent_ = extent; ready_ = false;

    const VkImageUsageFlags lumaUsage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT;
    // flowLvl_ is cleared every frame (per-frame reset) and C2 copies the cleaned
    // finest level back into it — both need TRANSFER_DST. The C2 ping-pong images
    // are the copy SOURCE (TRANSFER_SRC).
    const VkImageUsageFlags flowUsage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    const VkImageUsageFlags regUsage  = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    pyrA_.resize(kLevels); pyrB_.resize(kLevels); flowLvl_.resize(kLevels);
    for (int l = 0; l < kLevels; ++l) {
        VkExtent2D e = levelExtent(extent, l);
        if (!makeImage(pyrA_[l], e, VK_FORMAT_R32_SFLOAT, lumaUsage)) return false;
        if (!makeImage(pyrB_[l], e, VK_FORMAT_R32_SFLOAT, lumaUsage)) return false;
        if (!makeImage(flowLvl_[l], e, VK_FORMAT_R16G16B16A16_SFLOAT, flowUsage)) return false;
    }
    // C1: stabilized curr luma — only the levels the dense flow reads (finest..coarsest).
    pyrAs_.resize(kLevels);
    for (int l = flowFinest_; l < kLevels; ++l)
        if (!makeImage(pyrAs_[l], levelExtent(extent, l), VK_FORMAT_R32_SFLOAT, lumaUsage)) return false;
    if (!makeImage(flowExpA_, extent, VK_FORMAT_R16G16B16A16_SFLOAT, flowUsage)) return false;
    if (!makeImage(flowExpB_, extent, VK_FORMAT_R16G16B16A16_SFLOAT, flowUsage)) return false;
    // C2: TV-L1 ping-pong scratch at the finest solved flow level (perf_preset-driven res).
    if (!makeImage(flowRegA_, levelExtent(extent, flowFinest_), VK_FORMAT_R16G16B16A16_SFLOAT, regUsage)) return false;
    if (!makeImage(flowRegB_, levelExtent(extent, flowFinest_), VK_FORMAT_R16G16B16A16_SFLOAT, regUsage)) return false;
    ready_ = true;
    WFG_LOGI("framegen resized to %ux%u (%d pyramid levels, kFlowFinest=%d perf_preset=%d)",
             extent.width, extent.height, kLevels, flowFinest_, cfg_.perfPreset);
    return true;
}

void FrameGen::barrier(VkCommandBuffer cmd, VkImage img, VkImageLayout from, VkImageLayout to) {
    VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    b.oldLayout = from; b.newLayout = to;
    b.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    b.image = img; b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    dd_->CmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);
}

// NOTE: descriptor-set allocation/update per record() and the exact per-level
// flow wiring are implemented in record_impl.inc for readability; the graph is:
//   luma(curr)->pyrA[0], luma(prev)->pyrB[0]
//   downsample pyrA/pyrB to kLevels
//   coarse->fine flow (per level) into flowLvl
//   expand flowLvl[0] -> flowExpA/flowExpB
//   synth(prev,curr,flowExpA,flowExpB, alpha) -> out

void FrameGen::destroy() {
    if (!device_) return;
    dd_->DeviceWaitIdle(device_);
    for (auto& i : pyrA_) destroyImage(i); pyrA_.clear();
    for (auto& i : pyrB_) destroyImage(i); pyrB_.clear();
    for (auto& i : pyrAs_) destroyImage(i); pyrAs_.clear();
    for (auto& i : flowLvl_) destroyImage(i); flowLvl_.clear();
    destroyImage(flowExpA_); destroyImage(flowExpB_);
    destroyImage(flowRegA_); destroyImage(flowRegB_);   // C2
    destroyPipe(pLuma_); destroyPipe(pDown_); destroyPipe(pFlow_); destroyPipe(pFlowM4_);
    destroyPipe(pExpand_); destroyPipe(pExpandM4_); destroyPipe(pSynth_);
    destroyPipe(pGmReduce_); destroyPipe(pGmPrewarp_);
    destroyPipe(pFlowReg_);   // C2
    for (int s = 0; s < kGmSlots; ++s) {
        if (gmSsboMem_[s]) dd_->UnmapMemory(device_, gmSsboMem_[s]);
        if (gmSsbo_[s]) dd_->DestroyBuffer(device_, gmSsbo_[s], nullptr);
        if (gmSsboMem_[s]) dd_->FreeMemory(device_, gmSsboMem_[s], nullptr);
        gmSsbo_[s] = VK_NULL_HANDLE; gmSsboMem_[s] = VK_NULL_HANDLE; gmSsboPtr_[s] = nullptr;
    }
    gmBuffersReady_ = false;
    if (descPool_) dd_->DestroyDescriptorPool(device_, descPool_, nullptr);
    if (linear_) dd_->DestroySampler(device_, linear_, nullptr);
    descPool_ = VK_NULL_HANDLE; linear_ = VK_NULL_HANDLE; device_ = VK_NULL_HANDLE; ready_ = false;
}

} // namespace winfg

// record() + its scratch helpers reopen namespace winfg themselves, so include
// this OUTSIDE the namespace block above (keeps FrameGen:: members attached).
#include "record_impl.inc"
