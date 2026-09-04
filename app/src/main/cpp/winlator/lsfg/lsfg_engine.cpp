// See lsfg_engine.h.
//
// Ported from WinNative's vkr_lsfg.cpp (GPL-3.0-or-later), LSFG port credited
// to Camille LaVey / the Eden Emulator Project, following upstream lsfg-vk.

#include "lsfg_engine.h"

#include "lsfg_chain.hpp"
#include "lsfg_shaders.hpp"
#include "lsfg_vkd.h"

#include <algorithm>
#include <cmath>

#include <android/log.h>

#define LSFG_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "LsfgEngine", __VA_ARGS__)
#define LSFG_LOGW(...) __android_log_print(ANDROID_LOG_WARN, "LsfgEngine", __VA_ARGS__)

namespace lsfg {
namespace {

// The chain needs two real frames in its history before anything it produces
// is meaningful, and we require the plan to stay warm for a couple of frames
// so a single settled sample cannot start generation from garbage.
constexpr uint64_t kRequiredFrames    = 2;
constexpr uint32_t kRecurrenceFrames  = 2;
constexpr uint64_t kTelemetryInterval = 120;

constexpr float kFlowScaleMin   = 0.25f;
constexpr float kFlowScaleMax   = 1.0f;
constexpr float kFlowScaleSteps = 20.0f;

VkImageMemoryBarrier makeTransition(VkImage image, VkAccessFlags srcAccess,
                                    VkAccessFlags dstAccess, VkImageLayout oldLayout,
                                    VkImageLayout newLayout) {
    VkImageMemoryBarrier b{};
    b.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    b.srcAccessMask = srcAccess;
    b.dstAccessMask = dstAccess;
    b.oldLayout = oldLayout;
    b.newLayout = newLayout;
    b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = image;
    b.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    b.subresourceRange.levelCount = 1;
    b.subresourceRange.layerCount = 1;
    return b;
}

// Copy the just-composited frame into the chain's input ring. `source` is the
// composite target, which the composite render pass leaves in GENERAL.
void copyPresentedFrame(VkCommandBuffer cmd, VkImage source, LsfgImage& destination,
                        VkExtent2D extent) {
    const VkImageMemoryBarrier before[] = {
        makeTransition(source, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                       VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL),
        makeTransition(destination.Handle(), VK_ACCESS_SHADER_READ_BIT,
                       VK_ACCESS_TRANSFER_WRITE_BIT, destination.Layout(),
                       VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL),
    };
    vkd.CmdPipelineBarrier(cmd,
        VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 2, before);

    VkImageCopy region{};
    region.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    region.srcSubresource.layerCount = 1;
    region.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    region.dstSubresource.layerCount = 1;
    region.extent = {extent.width, extent.height, 1};
    vkd.CmdCopyImage(cmd, source, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, destination.Handle(),
                     VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);

    const VkImageMemoryBarrier after[] = {
        makeTransition(source, VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                       VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL),
        makeTransition(destination.Handle(), VK_ACCESS_TRANSFER_WRITE_BIT,
                       VK_ACCESS_SHADER_READ_BIT, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                       VK_IMAGE_LAYOUT_GENERAL),
    };
    vkd.CmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0, 0, nullptr, 0, nullptr, 2, after);

    destination.SetLayout(VK_IMAGE_LAYOUT_GENERAL);
}

} // namespace

Engine::Engine() = default;

Engine::~Engine() {
    chain_.reset();
    shaders_.reset();
}

bool Engine::init(VkDevice device, VkPhysicalDevice physicalDevice, const std::string& cachePath) {
    if (device == VK_NULL_HANDLE || physicalDevice == VK_NULL_HANDLE || cachePath.empty())
        return false;
    if (!lsfgVkdReady()) {
        LSFG_LOGW("dispatch not initialised; frame generation unavailable");
        return false;
    }

    device_ = Device(device, physicalDevice);
    cachePath_ = cachePath;

    shaders_ = std::make_unique<LsfgShaders>(device_, cachePath_);
    if (!shaders_->IsValid()) {
        LSFG_LOGW("shader cache at %s did not yield all modules", cachePath.c_str());
        shaders_.reset();
        return false;
    }
    LSFG_LOGI("frame generation shaders ready");
    return true;
}

void Engine::configure(uint32_t multiplier, uint32_t targetRate, float flowScale,
                       float refreshRate) {
    LsfgPacerConfig config = pacer_.Config();
    config.multiplier   = multiplier;
    config.target_rate  = targetRate;
    config.refresh_rate = refreshRate;
    pacer_.SetConfig(config);
    flowScale_ = std::clamp(flowScale, kFlowScaleMin, kFlowScaleMax);
}

void Engine::setRefreshRate(float refreshRate) {
    LsfgPacerConfig config = pacer_.Config();
    if (config.refresh_rate == refreshRate) return;
    config.refresh_rate = refreshRate;
    pacer_.SetConfig(config);
}

void Engine::setGuestExtent(uint32_t width, uint32_t height) {
    if (width == 0 || height == 0) return;
    peakGuestExtent_.width  = std::max(peakGuestExtent_.width, width);
    peakGuestExtent_.height = std::max(peakGuestExtent_.height, height);
}

// The flow pyramid is the expensive part, so its resolution is scaled relative
// to the largest guest surface seen: a game running well below panel
// resolution does not need a panel-resolution flow field.
float Engine::effectiveFlowScale(uint32_t width) const {
    if (width == 0 || peakGuestExtent_.width == 0) return flowScale_;
    const float ratio = (float)peakGuestExtent_.width / (float)width;
    const float stepped = std::ceil(ratio * kFlowScaleSteps) / kFlowScaleSteps;
    return std::clamp(std::min(stepped, flowScale_), kFlowScaleMin, kFlowScaleMax);
}

bool Engine::needsRebuild(uint32_t width, uint32_t height, VkFormat format) const {
    if (unavailable_) return false;
    return !chain_
        || builtExtent_.width != width || builtExtent_.height != height
        || builtFormat_ != format
        || builtFlowScale_ != effectiveFlowScale(width);
}

bool Engine::prepare(uint32_t width, uint32_t height, VkFormat format) {
    if (unavailable_ || !shaders_) return false;
    if (width == 0 || height == 0 || format == VK_FORMAT_UNDEFINED) return false;
    if (!needsRebuild(width, height, format)) return chain_ && chain_->Valid();

    const float scale = effectiveFlowScale(width);

    chain_.reset();
    chain_ = std::make_unique<LsfgChain>(device_, *shaders_, VkExtent2D{width, height}, format,
                                         scale);
    if (!chain_->Valid()) {
        // A device that cannot build the chain reports unsupported once and
        // stops trying, rather than thrashing allocations every frame.
        LSFG_LOGW("chain build failed at %ux%u; frame generation unavailable", width, height);
        chain_.reset();
        unavailable_ = true;
        return false;
    }

    builtExtent_    = VkExtent2D{width, height};
    builtFormat_    = format;
    builtFlowScale_ = scale;
    frameCount_ = 0;
    planCalls_  = 0;
    warmStreak_ = 0;
    warm_ = false;
    generating_ = false;
    pacer_.Reset();
    governor_.reset();
    LSFG_LOGI("chain built at %ux%u, flow %ux%u scale %.2f (preset %.2f, guest %ux%u)",
              width, height, (unsigned)(width * scale), (unsigned)(height * scale),
              (double)scale, (double)flowScale_,
              peakGuestExtent_.width, peakGuestExtent_.height);
    return true;
}

uint32_t Engine::plan(uint32_t capacity, uint64_t sourceFrames) {
    if (unavailable_ || !chain_) return 0;

    plan_ = pacer_.Plan(std::min<size_t>(capacity, kMaxGenerations), sourceFrames);

    // The pacer says how many frames FIT in the panel's budget. The governor
    // says how many this device can actually afford right now - on a handheld
    // the chain competes with the game for one GPU, so an extra generation has
    // to prove it improves total output without collapsing the real frame rate.
    if (governorEnabled_) {
        const LsfgPacerStats s = pacer_.Stats();
        governor_.configure((uint32_t)std::min<size_t>(pacer_.MaxGenerations(), kMaxGenerations));
        // presentedRate_, NOT the pacer's loop rate. The pacer samples its loop
        // once per SOURCE frame, so its "loop rate" is the guest rate by
        // construction and can never show that generation added anything - the
        // governor could not accept a probe on any hardware.
        plan_.generations = governor_.cap((uint32_t)plan_.generations, s.source_rate,
                                          presentedRate_);
    }

    warm_ = plan_.warm && frameCount_ + 1 >= kRequiredFrames;
    warmStreak_ = warm_ ? warmStreak_ + 1 : 0;
    generating_ = warm_ && warmStreak_ >= kRecurrenceFrames && plan_.generations > 0;

    if ((planCalls_++ % kTelemetryInterval) == 0) {
        const LsfgPacerStats stats = pacer_.Stats();
        const float wanted = stats.source_rate * (float)(plan_.generations + 1);
        LSFG_LOGI("presented=%.1f fps (measured at the swapchain)", (double)presentedRate_);
        LSFG_LOGI("pace gen=%zu max=%zu cap=%u guest=%.1f loop=%.1f refresh=%.1f target=%.0f "
                  "slots=%.2f drawn=%llu needs=%.1fHz%s%s",
                  plan_.generations, pacer_.MaxGenerations(), capacity,
                  (double)stats.source_rate, (double)stats.loop_rate, (double)stats.refresh_rate,
                  (double)stats.target_rate, (double)stats.slots,
                  (unsigned long long)stats.last_drawn, (double)wanted,
                  (stats.refresh_rate > 0.0f && wanted > stats.refresh_rate + 1.0f)
                      ? " PANEL-BOUND" : "",
                  stats.rates_settled ? (warm_ ? "" : " cold") : " sampling");
    }

    return generating_ ? (uint32_t)plan_.generations : 0;
}

void Engine::process(VkCommandBuffer cmd, VkImage source, uint32_t width, uint32_t height,
                     uint32_t generations) {
    if (!chain_ || !chain_->Valid()) return;

    const uint64_t count = frameCount_++;
    lastCount_ = count;
    lastGenerations_ = generations;

    // Seeding the input ring is a full-resolution image copy every frame, so it
    // is NOT free at 100 fps - it was costing real frame rate while producing
    // nothing. Copy only when generating, or while the governor is warming up
    // towards a probe, so the ring is populated by the time it is needed.
    const bool needHistory = generations > 0
        || (governorEnabled_ && governor_.wantsHistory());
    if (needHistory)
        copyPresentedFrame(cmd, source, chain_->Input(count), VkExtent2D{width, height});

    // The SHARED chain is 24 of the 25 shaders - the whole flow pyramid - and
    // only `generate` runs per generated frame. Running it while producing
    // nothing spends almost the entire cost of frame generation for no frames
    // at all, every frame. That is what pinned the GPU at 100% on device
    // regardless of the game's own settings, and it fed straight back into the
    // governor: the chain made the source rate collapse, the governor saw the
    // collapse and refused to generate, and refusing did not stop the chain.
    if (warm_ && generations > 0) chain_->DispatchShared(cmd, count);
}

void Engine::generateInto(VkCommandBuffer cmd, uint32_t generation, uint32_t targetIndex,
                          VkImage targetImage, VkImageView targetView,
                          uint32_t width, uint32_t height) {
    if (!chain_ || !chain_->Valid()) return;
    if (targetIndex >= LSFG_MAX_TARGETS) return;

    chain_->SetTarget(device_, lastGenerations_, generation, targetIndex, targetView);
    chain_->DispatchGeneration(cmd, lastCount_, lastGenerations_, generation, targetIndex,
                               targetImage, VkExtent2D{width, height});
}

float Engine::sourceRate() const { return pacer_.Stats().source_rate; }

void Engine::forgetTargets() {
    if (chain_) chain_->ForgetTargets();
}

void Engine::reset() {
    pacer_.Reset();
    governor_.reset();
    peakGuestExtent_ = VkExtent2D{};
    warmStreak_ = 0;
    warm_ = false;
    generating_ = false;
    plan_ = {};
}

} // namespace lsfg
