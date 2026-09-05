// See winfg_engine.h.
#include "winfg_engine.h"
#include "winfg_vkd.h"
#include "log.hpp"
#include <algorithm>
#include <cstring>

namespace winfg {
namespace {

// 2x: the guest layer synthesises at 0.35, not 0.5 - biased towards prev to
// cut the double-exposure ghost on HUD/text ("iterative bring-up knob" in
// layer.cpp). Kept for parity, since that is the tuning that was proven.
// 3x/4x: evenly spaced; never exercised by the guest layer, so this is the
// new ground native opens up and the first thing to judge by eye.
float alphaFor(uint32_t k, uint32_t n) {
    if (n <= 1) return 0.35f;
    return (float)(k + 1) / (float)(n + 1);
}

VkImageMemoryBarrier bar(VkImage img, VkImageLayout from, VkImageLayout to,
                         VkAccessFlags src, VkAccessFlags dst) {
    VkImageMemoryBarrier b{};
    b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    b.oldLayout = from; b.newLayout = to;
    b.srcAccessMask = src; b.dstAccessMask = dst;
    b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = img;
    b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    return b;
}

} // namespace

Engine::Engine() = default;
Engine::~Engine() { destroyRing(); fg_.destroy(); }

bool Engine::init(const VkTable& table, VkPhysicalDevice phys, VkDevice dev,
                  uint32_t queueFamily, VkQueue queue) {
    if (!winfgVkdInit(table, dd_, id_)) {
        WFG_LOGE("native: dispatch incomplete; frame generation unavailable");
        return false;
    }
    phys_ = phys; dev_ = dev; qf_ = queueFamily; queue_ = queue;
    id_.GetPhysicalDeviceMemoryProperties(phys_, &memProps_);
    if (!fg_.init(&dd_, &id_, phys_, dev_, qf_, queue_)) {
        WFG_LOGE("native: FrameGen::init failed");
        unavailable_ = true;
        return false;
    }
    WFG_LOGI("native: chain ready (10 pipelines, embedded shaders)");
    return true;
}

void Engine::configure(uint32_t multiplier, int model, int perfPreset, float flowScale) {
    cfg_.enabled    = true;
    cfg_.multiplier = (int)std::max<uint32_t>(2, multiplier);
    cfg_.model      = (model == 3) ? 3 : 4;
    cfg_.perfPreset = std::clamp(perfPreset, 0, 2);
    cfg_.flowScale  = std::clamp(flowScale, 0.25f, 1.0f);
    fg_.configure(cfg_);
}

bool Engine::prepare(uint32_t w, uint32_t h, VkFormat fmt) {
    if (unavailable_ || w == 0 || h == 0 || fmt == VK_FORMAT_UNDEFINED) return false;
    if (builtW_ == w && builtH_ == h && builtFmt_ == fmt && fg_.valid()) return true;

    // onResize rebuilds the pyramids/scratch; our own input ring follows it.
    if (!fg_.onResize(VkExtent2D{w, h}, fmt, /*force=*/false)) {
        WFG_LOGE("native: onResize %ux%u failed; frame generation unavailable", w, h);
        unavailable_ = true;
        return false;
    }
    destroyRing();
    for (Slot& s : ring_) {
        if (!makeSlot(s, w, h, fmt)) { destroyRing(); unavailable_ = true; return false; }
    }
    builtW_ = w; builtH_ = h; builtFmt_ = fmt;
    history_ = 0; curr_ = 0; frames_ = 0;
    WFG_LOGI("native: built %ux%u fmt=%d model=%d preset=%d flow=%.2f",
             w, h, (int)fmt, cfg_.model, cfg_.perfPreset, (double)cfg_.flowScale);
    return true;
}

uint32_t Engine::plan(uint32_t capacity) const {
    if (!valid() || history_ < 2) return 0;           // need prev AND curr
    const uint32_t want = (uint32_t)std::max(cfg_.multiplier - 1, 0);
    return std::min({want, capacity, kMaxGenerations});
}

void Engine::process(VkCommandBuffer cmd, VkImage source, uint32_t w, uint32_t h,
                     uint32_t /*generations*/) {
    if (!valid()) return;
    // Rotate: the slot we are about to overwrite is the OLD prev; after the
    // copy it is the new curr and the other slot is prev.
    curr_ = 1 - curr_;
    Slot& dst = ring_[curr_];

    // source is GENERAL (composite render pass leaves it there); the slot may
    // be in any layout from last use - UNDEFINED is legal as "discard".
    VkImageMemoryBarrier pre[2] = {
        bar(source, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT),
        bar(dst.img, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            0, VK_ACCESS_TRANSFER_WRITE_BIT),
    };
    dd_.CmdPipelineBarrier(cmd,
        VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 2, pre);

    VkImageCopy region{};
    region.srcSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    region.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    region.extent = {w, h, 1};
    dd_.CmdCopyImage(cmd, source, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                     dst.img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);

    // source back to GENERAL for the real-frame copy that follows; the new
    // curr becomes SHADER_READ_ONLY, which is what the chain samples it as.
    VkImageMemoryBarrier post[2] = {
        bar(source, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
            VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT),
        bar(dst.img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT),
    };
    dd_.CmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
        0, 0, nullptr, 0, nullptr, 2, post);
    dst.valid = true;
    if (history_ < 2) history_++;
    frames_++;
}

void Engine::generateInto(VkCommandBuffer cmd, uint32_t generation, uint32_t count,
                          VkImage targetImage, VkImageView targetView, uint32_t w, uint32_t h) {
    if (!valid() || history_ < 2) return;
    const Slot& prev = ring_[1 - curr_];
    const Slot& curr = ring_[curr_];
    if (!prev.valid || !curr.valid) return;

    // The chain writes the output through a STORAGE descriptor in GENERAL.
    // The composite ring already keeps its targets in GENERAL; make the
    // prior contents' writes visible and take ownership for the compute write.
    VkImageMemoryBarrier toWrite = bar(targetImage, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
        VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT);
    dd_.CmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &toWrite);

    // gmSlot indexes the C1 global-motion SSBO read back on the CPU next
    // frame; kGmSlots (3) >= our frames in flight (2), so rotating per source
    // frame keeps a slot from being read while its reduce is still in flight.
    fg_.record(cmd, prev.view, curr.view, targetView, alphaFor(generation, count), gmSlot_);
    if (generation + 1 == count) gmSlot_ = (gmSlot_ + 1) % (uint32_t)FrameGen::kGmSlots;

    (void)w; (void)h;
}

void Engine::reset() { history_ = 0; frames_ = 0; }

// ---- input ring --------------------------------------------------------------

uint32_t Engine::memType(uint32_t bits, VkMemoryPropertyFlags props) const {
    for (uint32_t i = 0; i < memProps_.memoryTypeCount; i++)
        if ((bits & (1u << i)) && (memProps_.memoryTypes[i].propertyFlags & props) == props) return i;
    return 0;
}

bool Engine::makeSlot(Slot& s, uint32_t w, uint32_t h, VkFormat fmt) {
    VkImageCreateInfo ii{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ii.imageType = VK_IMAGE_TYPE_2D; ii.extent = {w, h, 1};
    ii.mipLevels = 1; ii.arrayLayers = 1; ii.format = fmt;
    ii.tiling = VK_IMAGE_TILING_OPTIMAL; ii.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    ii.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    ii.samples = VK_SAMPLE_COUNT_1_BIT; ii.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (dd_.CreateImage(dev_, &ii, nullptr, &s.img) != VK_SUCCESS) return false;
    VkMemoryRequirements req; dd_.GetImageMemoryRequirements(dev_, s.img, &req);
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    ai.allocationSize = req.size;
    ai.memoryTypeIndex = memType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (dd_.AllocateMemory(dev_, &ai, nullptr, &s.mem) != VK_SUCCESS) { destroySlot(s); return false; }
    dd_.BindImageMemory(dev_, s.img, s.mem, 0);
    VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vi.image = s.img; vi.viewType = VK_IMAGE_VIEW_TYPE_2D; vi.format = fmt;
    vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    if (dd_.CreateImageView(dev_, &vi, nullptr, &s.view) != VK_SUCCESS) { destroySlot(s); return false; }
    s.valid = false;
    return true;
}

void Engine::destroySlot(Slot& s) {
    if (s.view) dd_.DestroyImageView(dev_, s.view, nullptr);
    if (s.img)  dd_.DestroyImage(dev_, s.img, nullptr);
    if (s.mem)  dd_.FreeMemory(dev_, s.mem, nullptr);
    s = Slot{};
}

void Engine::destroyRing() {
    if (dev_ == VK_NULL_HANDLE || !dd_.DeviceWaitIdle) return;
    if (ring_[0].img || ring_[1].img) dd_.DeviceWaitIdle(dev_);
    destroySlot(ring_[0]); destroySlot(ring_[1]);
    history_ = 0;
}

} // namespace winfg
