// win-fg — compute frame-generation engine.
// Owns the compute pipelines (our of3_* optical flow + wfg_synth) and the
// per-resolution intermediate images, and records the flow+synth graph that
// turns (prev, curr) into an interpolated frame. Written for win-fg; no code
// from bionic-fg / lsfg / GameScope.
#pragma once
#include "vk_dispatch.hpp"
#include "config.hpp"
#include <vector>

namespace winfg {

class FrameGen {
public:
    bool init(const DeviceDispatch* dd, const InstanceDispatch* id,
              VkPhysicalDevice phys, VkDevice dev, uint32_t queueFamily, VkQueue queue);
    // Applies a new config. Recomputes the active finest flow level from
    // cfg_.perfPreset; if it changed while resources are live, safely rebuilds the
    // affected per-size flow images (like a resize) and resets the flow predictor,
    // so a perf_preset change is FULLY LIVE — no FG toggle needed. Defined in
    // record_impl.inc (needs the scratch-state teardown). See flowFinestForPreset.
    void configure(const Config& c);
    // (Re)build per-resolution resources for a swapchain of the given size/format.
    // force=true rebuilds even when the extent is unchanged (used by configure()
    // when a perf_preset change alters the finest solved flow level).
    bool onResize(VkExtent2D extent, VkFormat colorFormat, bool force = false);
    // Record the whole flow+synth graph into cmd. prevView/currView are SAMPLED
    // views of the two real frames; outView is a STORAGE view of the target
    // image the generated frame is written into. alpha in (0,1).
    // gmSlot selects which C1 global-motion SSBO to reduce into / read back; the
    // caller MUST pass the ring-slot index of the FrameCtx it just fence-waited
    // (that guarantees this slot's PREVIOUS reduce has completed and is safe to
    // read on the host — see the decoupled-LK note in record_impl.inc).
    void record(VkCommandBuffer cmd, VkImageView prevView, VkImageView currView,
                VkImageView outView, float alpha, uint32_t gmSlot = 0);
    void destroy();
    bool valid() const { return device_ != VK_NULL_HANDLE && ready_; }
    const Config& config() const { return cfg_; }

    // public so the record_impl.inc free helpers can reference them
    struct Img { VkImage image = VK_NULL_HANDLE; VkDeviceMemory mem = VK_NULL_HANDLE;
                 VkImageView view = VK_NULL_HANDLE; VkExtent2D extent{}; VkFormat fmt{}; };
    struct Pipe { VkPipeline pipeline = VK_NULL_HANDLE; VkPipelineLayout layout = VK_NULL_HANDLE;
                  VkDescriptorSetLayout setLayout = VK_NULL_HANDLE; VkShaderModule module = VK_NULL_HANDLE; };

    // ── C1 GLOBAL-MOTION PRE-WARP constants (public so record_impl.inc's free
    // helpers can size/index the SSBO pool). See of3_gm_reduce/of3_gm_prewarp. ──
    static constexpr int kGmSlots   = 3;   // >= present ring size; one SSBO per in-flight ctx
    static constexpr int kGmLevel   = 3;   // 1/8-res luma pyramid level for the LK estimate
    static constexpr int kGmThreads = 512; // fixed reduce threads (8 groups * local_size_x 64)
    static constexpr int kGmStride  = 32;  // floats/thread: 21 H + 6 b + coverage + resid + pad
    // Finest pyramid level the dense flow is solved at (0 = full res). This is now
    // RUNTIME-SELECTABLE via conf.toml perf_preset (0 Quality=1 / 1 Balanced=2 /
    // 2 Performance=3). kFlowFinestDefault is the Balanced value (today's behaviour);
    // the ACTIVE level lives in flowFinest_ and is read through flowFinest(). onResize
    // sizes the stabilized-curr pyramid + C2 scratch from it, and record_impl.inc
    // wires the flow/expand inputs to it — both must agree, so they all read
    // flowFinest()/flowFinest_ (never a hard-coded 2). (Was a static constexpr = 2.)
    static constexpr int kFlowFinestDefault = 2;   // Balanced (perf_preset=1)
    // Map perf_preset -> finest solved level. Out-of-range -> Balanced. A higher
    // level = coarser (cheaper) flow = smaller base-FPS drop. configure() clamps the
    // result against kLevels so at least two solved levels always remain.
    static int flowFinestForPreset(int preset) {
        switch (preset) {
            case 0: return 1;   // Quality     ~1/2-res
            case 2: return 3;   // Performance ~1/8-res
            case 1: default: return kFlowFinestDefault;  // Balanced ~1/4-res (today)
        }
    }
    // The ACTIVE finest solved flow level (set by configure() from perf_preset).
    int flowFinest() const { return flowFinest_; }

private:
    uint32_t findMemType(uint32_t bits, VkMemoryPropertyFlags props) const;
    bool makeImage(Img& out, VkExtent2D ext, VkFormat fmt, VkImageUsageFlags usage);
    void destroyImage(Img& i);
    bool makeGmBuffers();          // allocate the kGmSlots host-visible LK-reduce SSBOs
    // Read back one reduce SSBO, solve the 6x6 inverse-compositional LK step, and
    // update the running/applied global affine. Returns true if it engaged this
    // frame. Implemented in record_impl.inc (has the ScratchState/UBO context).
    bool updateGlobalMotion(uint32_t gmSlot, VkExtent2D fullExtent);
    bool makePipe(Pipe& p, int shaderId,
                  const std::vector<VkDescriptorType>& bindings);
    void destroyPipe(Pipe& p);
    void barrier(VkCommandBuffer cmd, VkImage img, VkImageLayout from, VkImageLayout to);

    const DeviceDispatch*   dd_ = nullptr;
    const InstanceDispatch*  id_ = nullptr;
    VkPhysicalDevice phys_ = VK_NULL_HANDLE;
    VkDevice         device_ = VK_NULL_HANDLE;
    VkQueue          queue_ = VK_NULL_HANDLE;
    uint32_t         queueFamily_ = 0;
    VkPhysicalDeviceMemoryProperties memProps_{};
    VkSampler        linear_ = VK_NULL_HANDLE;
    VkDescriptorPool descPool_ = VK_NULL_HANDLE;

    Config    cfg_;
    VkExtent2D extent_{};
    bool ready_ = false;
    // ACTIVE finest solved flow level (perf_preset). Default = Balanced (today's 2)
    // so an unconfigured engine and an unset conf.toml are byte-identical to before.
    int  flowFinest_ = kFlowFinestDefault;

    // pipelines
    Pipe pLuma_, pDown_, pFlow_, pFlowM4_, pExpand_, pExpandM4_, pSynth_;
    // C1: LK normal-equation reduce + curr-luma stabilization pre-warp
    Pipe pGmReduce_, pGmPrewarp_;
    // C2: TV-L1 flow-regularization iteration (one dispatch per iteration)
    Pipe pFlowReg_;

    // per-resolution resources
    // 7 levels (was 5): the coarsest levels set the max motion the solver can track.
    // At 5 levels the reach was only ~±84px/frame at 720p, so fast camera motion
    // (150-300px/frame at the periphery) saturated the search -> incoherent vectors
    // -> oil-paint melt. Two more (tiny) coarse levels push the reach to ~±300px so
    // large global motion is captured coherently instead of smeared. Near-zero cost:
    // the extra levels are 40x22 and 20x11 images.
    static const int kLevels = 7;
    std::vector<Img> pyrA_, pyrB_;   // luma pyramids for curr / prev
    std::vector<Img> pyrAs_;         // C1: STABILIZED curr luma (flow levels only)
    std::vector<Img> flowLvl_;       // per-level flow scratch
    Img flowExpA_, flowExpB_;        // fwd / bwd expanded flow (+conf)
    // C2: TV-L1 ping-pong scratch at kFlowFinest res. flowLvl_[kFlowFinest] holds
    // the fixed observation f0 (never written during the iterations); u ping-pongs
    // between these two; the final iterate is copied back into flowLvl_[kFlowFinest]
    // so of3_expand consumes the cleaned field with no binding change (⇒ skipping
    // C2 leaves flowLvl_ byte-identical to pre-C2).
    Img flowRegA_, flowRegB_;

    // ── C1 global-motion state ────────────────────────────────────────────────
    // Host-visible SSBOs the reduce shader writes per-thread partials into; the
    // CPU sums + solves them. One per ring slot so a readback never races a live
    // submission (the caller fence-waited its slot before calling record()).
    VkBuffer       gmSsbo_[kGmSlots]   {};
    VkDeviceMemory gmSsboMem_[kGmSlots]{};
    void*          gmSsboPtr_[kGmSlots]{};
    bool           gmBuffersReady_ = false;
    // running affine params p1..p6 in centered-UV space (see GMUBO in config.hpp);
    // identity = all zero. gmLinAt_ records the linearization params in effect when
    // each slot's reduce was dispatched (the correct IC-update base for that slot).
    double gmRun_[6]              {};
    double gmLinAt_[kGmSlots][6] {};
    bool   gmSlotValid_[kGmSlots]{};
    bool   gmHaveEst_      = false;  // at least one good solve so far
    int    gmStableCount_  = 0;      // consecutive good solves (auto-engage threshold)
    bool   gmEngaged_      = false;  // is the affine being APPLIED this frame
    float  gmLastTransPx_  = 0.0f;   // estimated global translation magnitude (full-res px), for logging
    unsigned long long gmFrames_ = 0;
};

} // namespace winfg
