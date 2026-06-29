#pragma once
#include <vulkan/vulkan.h>
#include <list>
#include <vulkan/vulkan_android.h>
struct VkTable {

    PFN_vkCreateInstance CreateInstance;

    PFN_vkDestroyInstance DestroyInstance;
    PFN_vkEnumeratePhysicalDevices EnumeratePhysicalDevices;
    PFN_vkGetPhysicalDeviceProperties GetPhysicalDeviceProperties;
    PFN_vkGetPhysicalDeviceMemoryProperties GetPhysicalDeviceMemoryProperties;
    PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR GetPhysicalDeviceSurfaceCapabilitiesKHR;
    PFN_vkGetPhysicalDeviceSurfaceFormatsKHR GetPhysicalDeviceSurfaceFormatsKHR;
    PFN_vkGetPhysicalDeviceSurfacePresentModesKHR GetPhysicalDeviceSurfacePresentModesKHR;
    PFN_vkGetPhysicalDeviceQueueFamilyProperties GetPhysicalDeviceQueueFamilyProperties;
    PFN_vkGetPhysicalDeviceSurfaceSupportKHR GetPhysicalDeviceSurfaceSupportKHR;
    PFN_vkCreateDevice CreateDevice;
    PFN_vkDestroySurfaceKHR DestroySurfaceKHR;
    PFN_vkCreateAndroidSurfaceKHR CreateAndroidSurfaceKHR;

    PFN_vkGetDeviceProcAddr GetDeviceProcAddr;
    PFN_vkDestroyDevice DestroyDevice;
    PFN_vkGetDeviceQueue GetDeviceQueue;
    PFN_vkDeviceWaitIdle DeviceWaitIdle;
    PFN_vkCreateSwapchainKHR CreateSwapchainKHR;
    PFN_vkDestroySwapchainKHR DestroySwapchainKHR;
    PFN_vkGetSwapchainImagesKHR GetSwapchainImagesKHR;
    PFN_vkAcquireNextImageKHR AcquireNextImageKHR;
    PFN_vkQueuePresentKHR QueuePresentKHR;
    PFN_vkQueueSubmit QueueSubmit;
    PFN_vkCreateRenderPass CreateRenderPass;
    PFN_vkDestroyRenderPass DestroyRenderPass;
    PFN_vkCreateFramebuffer CreateFramebuffer;
    PFN_vkDestroyFramebuffer DestroyFramebuffer;
    PFN_vkCreateImageView CreateImageView;
    PFN_vkDestroyImageView DestroyImageView;
    PFN_vkCreateImage CreateImage;
    PFN_vkDestroyImage DestroyImage;
    PFN_vkCreateBuffer CreateBuffer;
    PFN_vkDestroyBuffer DestroyBuffer;
    PFN_vkAllocateMemory AllocateMemory;
    PFN_vkFreeMemory FreeMemory;
    PFN_vkMapMemory MapMemory;
    PFN_vkFlushMappedMemoryRanges FlushMappedMemoryRanges;
    PFN_vkBindBufferMemory BindBufferMemory;
    PFN_vkBindImageMemory BindImageMemory;
    PFN_vkGetBufferMemoryRequirements GetBufferMemoryRequirements;
    PFN_vkGetImageMemoryRequirements GetImageMemoryRequirements;
    PFN_vkCreateDescriptorSetLayout CreateDescriptorSetLayout;
    PFN_vkDestroyDescriptorSetLayout DestroyDescriptorSetLayout;
    PFN_vkCreateDescriptorPool CreateDescriptorPool;
    PFN_vkDestroyDescriptorPool DestroyDescriptorPool;
    PFN_vkAllocateDescriptorSets AllocateDescriptorSets;
    PFN_vkFreeDescriptorSets FreeDescriptorSets;
    PFN_vkUpdateDescriptorSets UpdateDescriptorSets;
    PFN_vkCreatePipelineLayout CreatePipelineLayout;
    PFN_vkDestroyPipelineLayout DestroyPipelineLayout;
    PFN_vkCreateShaderModule CreateShaderModule;
    PFN_vkDestroyShaderModule DestroyShaderModule;
    PFN_vkCreateGraphicsPipelines CreateGraphicsPipelines;
    PFN_vkDestroyPipeline DestroyPipeline;
    PFN_vkCreateCommandPool CreateCommandPool;
    PFN_vkDestroyCommandPool DestroyCommandPool;
    PFN_vkAllocateCommandBuffers AllocateCommandBuffers;
    PFN_vkFreeCommandBuffers FreeCommandBuffers;
    PFN_vkBeginCommandBuffer BeginCommandBuffer;
    PFN_vkEndCommandBuffer EndCommandBuffer;
    PFN_vkResetCommandBuffer ResetCommandBuffer;
    PFN_vkCmdBeginRenderPass CmdBeginRenderPass;
    PFN_vkCmdEndRenderPass CmdEndRenderPass;
    PFN_vkCmdBindPipeline CmdBindPipeline;
    PFN_vkCmdBindDescriptorSets CmdBindDescriptorSets;
    PFN_vkCmdDraw CmdDraw;
    PFN_vkCmdPushConstants CmdPushConstants;
    PFN_vkCmdSetViewport CmdSetViewport;
    PFN_vkCmdSetScissor CmdSetScissor;
    PFN_vkCmdPipelineBarrier CmdPipelineBarrier;
    PFN_vkCmdCopyImage CmdCopyImage;
    PFN_vkCmdCopyBufferToImage CmdCopyBufferToImage;
    PFN_vkCreateSampler CreateSampler;
    PFN_vkDestroySampler DestroySampler;
    PFN_vkCreateSemaphore CreateSemaphore;
    PFN_vkDestroySemaphore DestroySemaphore;
    PFN_vkCreateFence CreateFence;
    PFN_vkDestroyFence DestroyFence;
    PFN_vkWaitForFences WaitForFences;
    PFN_vkResetFences ResetFences;
    PFN_vkGetFenceStatus GetFenceStatus;

    PFN_vkGetAndroidHardwareBufferPropertiesANDROID GetAndroidHardwareBufferPropertiesANDROID;
};

#include <android/log.h>
#include <string>
#define WLOG_TAG "Winlator_Renderer"
#define RLOG(...) if(verboseLog) __android_log_print(ANDROID_LOG_DEBUG,WLOG_TAG,__VA_ARGS__)
#define RLOG_E(...) __android_log_print(ANDROID_LOG_ERROR,WLOG_TAG,__VA_ARGS__)
#define SCANOUT_LOG(...) __android_log_print(ANDROID_LOG_DEBUG,"Winlator_Scanout",__VA_ARGS__)

#include <vulkan/vulkan_android.h>
#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <vector>
#include <unordered_map>
#include <thread>
#include <atomic>
#include <mutex>
#include <shared_mutex>
#include <condition_variable>

// Renderer-neutral direct-scanout impl (owns the SurfaceControl/AHB state).
// Included after SCANOUT_LOG is defined above; its own definition is guarded.
#include "../scanout/ScanoutContext.h"

static constexpr uint32_t MAX_FRAMES_IN_FLIGHT = 2;

struct WindowPushConstants { float ndcX0, ndcY0, ndcX1, ndcY1; int useTexAlpha; };

// Push-constant layouts for the spatial-upscaler post passes. The leading vec4
// `ndc` matches upscale.vert; the remaining members are read only by the
// fragment stage. std430 offsets line up with these C structs.
struct SgsrPushConstants {                 // 36 bytes
    float ndc[4];
    float viewportInfo[4];                 // xy = 1/inputSize, zw = inputSize px
    float edgeSharpness;                   // SGSR EdgeSharpness, from the slider
};
struct NisPushConstants {                  // 36 bytes
    float ndc[4];
    float viewportInfo[4];                 // xy = 1/inputSize, zw = inputSize px
    float sharpness;                       // NIS sharpness [0..1], from the slider
};
struct EasuPushConstants {                 // 88 bytes
    float    ndc[4];
    uint32_t con0[4], con1[4], con2[4], con3[4];
    float    outW, outH;
};
struct RcasPushConstants {                 // 40 bytes
    float    ndc[4];
    uint32_t con[4];                       // con.x = bit-packed sharpness
    float    outW, outH;
};
struct DownscalePushConstants {            // 32 bytes
    float ndc[4];
    float srcW, srcH;                      // render (source) resolution
    float dstW, dstH;                      // display (output) resolution
};
// Composable post effects (AMD CAS sharpen + fake-HDR). Both lead with vec4 ndc
// (offset 0) like the upscaler PCs and stay well under the 88-byte PC range.
struct CasPushConstants {                  // 32 bytes
    float ndc[4];
    float resolution[2];                   // input texture size in px
    float sharpness;                       // CAS SHARPNESS term [0..1]
    float _pad;
};
struct HdrPushConstants {                  // 24 bytes
    float ndc[4];
    float resolution[2];                   // input texture size in px
};
// Phase 2 screen effects ported from the GL EffectComposer. Each leads with vec4
// ndc (offset 0) like the upscaler PCs and stays well under the 88-byte PC range.
struct FxaaPushConstants {                 // 24 bytes
    float ndc[4];
    float resolution[2];                   // input texture size in px
};
struct ToonPushConstants {                 // 24 bytes
    float ndc[4];
    float resolution[2];                   // input texture size in px
};
struct ColorPushConstants {                // 28 bytes
    float ndc[4];
    float brightness;                      // additive [-1,1]
    float contrast;                        // [0,2]
    float gamma;                           // [0.1,5]
};
struct NtscPushConstants {                 // 28 bytes
    float ndc[4];
    float resolution[2];                   // screen size in px (TextureSize/resolution)
    float frameCount;                      // animated chroma-phase counter
};
struct CrtPushConstants {                  // 24 bytes
    float ndc[4];
    float resolution[2];                   // input texture size in px (unused by shader)
};
struct DebandPushConstants {               // 28 bytes
    float ndc[4];
    float resolution[2];                   // input texture size in px (layout parity)
    float strength;                        // dither amplitude in LSBs (1.0 = +/-1/255)
};

class VulkanRendererContext {
public:
    VulkanRendererContext(ANativeWindow* window, int cWidth, int cHeight, void* adrenotoolsHandle = nullptr);
    ~VulkanRendererContext();

    void onSurfaceResized(int width, int height);
    void setTransform(float ox, float oy, float sx, float sy);
    void updatePointerPosition(short x, short y);
    void updateWindowContent(int64_t id, void* pixels, short w, short h, short stride, int x, int y);
    void updateWindowContentAHB(int64_t id, AHardwareBuffer* ahb, short w, short h, int x, int y);
    void updateCursorImage(void* pixels, short w, short h, short hotX, short hotY);
    void setCursorVisible(bool visible);
    void setRenderList(const int64_t* ids, const int* xs, const int* ys, int count);
    void removeWindow(int64_t id);
    void clearBackbuffer() {}
    void beginBatch() {}
    void endBatch() {}
    void initScanout();
    void destroyScanout();
    void applyScanoutBuffer();
    void initScanoutFromWindows(ANativeWindow* gameWin, ANativeWindow* cursorWin);
    void scanoutSetDst(int x, int y, int w, int h);
    void scanoutSetBuffer(AHardwareBuffer* ahb, int x, int y, int w, int h, int fenceFd = -1);
    void scanoutSetCursorImage(void* pixels, short w, short h, short stride);
    void scanoutSetCursorPos(short x, short y, short hotX, short hotY);
    // The renderer-neutral scanout implementation. The scanout methods above are
    // thin forwarders to this (see VulkanRendererScanout.cpp); the cursor render-
    // thread signalling stays in the forwarders, not in ScanoutContext.
    ScanoutContext scanout;
    // Bound to scanout's atomics so the existing JNI ABI (r->scanoutActive /
    // r->gameFrameDelivered direct member access) keeps compiling against a
    // single source of truth. Must be declared after `scanout`.
    std::atomic<bool>& scanoutActive = scanout.scanoutActive;
    std::atomic<bool>& gameFrameDelivered = scanout.gameFrameDelivered;
    std::atomic<bool> surfaceDetached{false};

    void detachSurface();
    bool reattachSurface(ANativeWindow* newWindow);

    bool verboseLog = true;
    void setVerboseLog(bool v) { verboseLog = v; scanout.setVerboseLog(v); }
    void dumpRendererInfo();

    std::string adrenoDriverPath;
    std::string adrenoDriverName;
    std::string adrenoNativeLibDir;
    void* vulkanHandle = nullptr;
    std::atomic<bool> scanoutBlackFrameDone{false};
    PFN_vkGetInstanceProcAddr gipa = nullptr;
    VkTable vk_ = {};
    void loadCustomDriver();
    void loadInstanceDispatch();
    void loadDeviceDispatch();

    void setFilterMode(int mode);
    void setUpscaler(int mode);
    void setHqDownscale(bool enabled);
    void setCas(bool enabled, int sharpness);
    void setHdr(bool enabled);
    void setDeband(bool enabled, int strength);
    void setUpscaleSharpness(int sharpness);
    void setFxaa(bool enabled);
    void setToon(bool enabled);
    void setCrt(bool enabled);
    void setNtsc(bool enabled);
    void setColorGrade(float brightness, float contrast, float gamma);
    void setSwapRB(bool enabled);
    void setPresentMode(VkPresentModeKHR mode);
    std::vector<int> getSupportedPresentModes() const;

private:
    struct WinTex {
        VkImage              img            = VK_NULL_HANDLE;
        VkDeviceMemory       mem            = VK_NULL_HANDLE;
        VkImageView          view           = VK_NULL_HANDLE;
        VkDescriptorSet      ds             = VK_NULL_HANDLE;
        VkBuffer             stg            = VK_NULL_HANDLE;
        VkDeviceMemory       stgMem         = VK_NULL_HANDLE;
        void*                mapped         = nullptr;
        VkDeviceSize         cap            = 0;
        int                  w              = 0;
        int                  h              = 0;
        bool                 dirty          = false;
        bool                 isAHB          = false;
        bool                 needsTransition = false;
        AHardwareBuffer*     ahb            = nullptr;
    };

    struct RenderEntry { int64_t id; int x, y; };
    struct DrawEntry {
        VkImage         img            = VK_NULL_HANDLE;
        VkDescriptorSet ds             = VK_NULL_HANDLE;
        VkBuffer        upload         = VK_NULL_HANDLE;
        int             x=0, y=0, w=0, h=0;
        bool            needsTransition = false;
        bool            isAHB          = false;
    };

    ANativeWindow* window;
    int surfaceWidth, surfaceHeight, containerWidth, containerHeight;
    void* adrenotoolsHandle = nullptr;
    int filterMode = 0;
    bool swapRB = false;
    float maxAnisotropy           = 1.0f;
    bool  cubicSupported          = false;
    VkPhysicalDeviceMemoryProperties memProperties{};
    VkPresentModeKHR requestedPresentMode = VK_PRESENT_MODE_FIFO_KHR;
    uint32_t graphicsQueueFamilyIndex = 0;
    std::vector<VkPresentModeKHR> availablePresentModes;

    std::unordered_map<int64_t, WinTex>         texMap;

    std::unordered_map<AHardwareBuffer*, WinTex>              ahbImportCache;
    std::unordered_map<int64_t, std::vector<AHardwareBuffer*>> windowAhbs;

    std::vector<WinTex>    deleteQueue;
    std::vector<RenderEntry> renderList;

    std::vector<DrawEntry>             frameDraws;
    std::vector<VkImageMemoryBarrier>  frameAhbTransitions;
    std::vector<VkImageMemoryBarrier>  framePreUpload;
    std::vector<VkImageMemoryBarrier>  framePostUpload;

    // [P0] All SurfaceControl / AHardwareBuffer scanout state moved to
    // ScanoutContext (member `scanout` above). Dead members (lastDst*,
    // ScanoutPending/scanoutPending*, fnSTSetBackPressure) were dropped.

    std::atomic<int>  pointerX{0}, pointerY{0};
    float sceneOffsetX=0.f, sceneOffsetY=0.f, sceneScaleX=1.f, sceneScaleY=1.f;

    std::atomic<bool> cursorVisible{false};
    short  cursorHotX=0, cursorHotY=0, cursorTexW=0, cursorTexH=0;
    std::vector<uint32_t>  cursorPixels;
    std::atomic<bool> isCursorImageDirty{false};
    std::atomic<bool> cursorMoved{false};

    VkImage         cursorImg   = VK_NULL_HANDLE;
    VkDeviceMemory  cursorMem   = VK_NULL_HANDLE;
    VkImageView     cursorView  = VK_NULL_HANDLE;
    VkDescriptorSet  cursorDS   = VK_NULL_HANDLE;
    VkBuffer         cursorStg  = VK_NULL_HANDLE;
    VkDeviceMemory   cursorStgM = VK_NULL_HANDLE;
    void*            cursorStgP = nullptr;
    VkDeviceSize     cursorStgC = 0;
    VkDeviceSize     cursorUploadSize = 0;

    VkInstance       instance;
    VkSurfaceKHR     surface;
    VkPhysicalDevice physicalDevice;
    VkDevice         device;
    VkQueue          graphicsQueue;
    VkSwapchainKHR   swapchain   = VK_NULL_HANDLE;
    VkFormat         swapchainFmt;
    VkExtent2D       swapchainExt;

    std::vector<VkImage>       swapchainImages;
    std::vector<VkImageView>   swapchainViews;
    std::vector<VkFramebuffer> swapchainFBs;

    VkRenderPass          renderPass  = VK_NULL_HANDLE;
    VkDescriptorSetLayout dsLayout    = VK_NULL_HANDLE;
    VkPipelineLayout      pipeLayout  = VK_NULL_HANDLE;

    VkPipeline            pipeline    = VK_NULL_HANDLE;

    // ---- Spatial upscaler (SGSR / FSR1) ----
    // upscalerMode enum (mirrored by JNI nativeSetUpscaler):
    //   0 = none     (passthrough; current sampler governs scaling)
    //   1 = linear   (rebuild base sampler LINEAR; no shader pass)
    //   2 = nearest  (rebuild base sampler NEAREST; no shader pass)
    //   3 = sgsr     (Snapdragon GSR 1.0; single pass; aspect-fit / letterbox)
    //   4 = fsr      (AMD FSR1 EASU+RCAS; two passes; fill / stretch)
    //   5 = fsr_fit  (AMD FSR1 EASU+RCAS; two passes; aspect-fit / letterbox)
    //   6 = sharpen  (RCAS-only; any resolution; aspect-fit / letterbox)
    //   7 = nis      (NVIDIA Image Scaling NVScaler; single pass; aspect-fit)
    // Shader upscaling only engages for modes 3-7 AND when the game render
    // resolution (container) is smaller than the swapchain. Otherwise the
    // existing direct-to-swapchain path is used unchanged.
    int               upscalerMode      = 0;
    // Independent of upscalerMode: high-quality Lanczos downscale for the
    // supersampling case (game render res > display res). When enabled and the
    // render res exceeds the swapchain, replaces the bilinear minify with a
    // proper downscale pass. Supersampling and the upscale modes are mutually
    // exclusive in practice (one is render>display, the other render<display).
    bool              hqDownscale       = false;

    // Composable post effects, independent of the scaling mode. CAS layers a sharpen
    // on top of any mode (incl. native res); HDR is a binary fake-HDR. The upscaler
    // sharpness (RCAS lobe scale / SGSR EdgeSharpness) is driven by its own slider as a
    // linear 0..1 value: 0 = neutral (no sharpening; the upscale still runs), 1 = max.
    bool              casEnabled        = false;
    int               casSharpness      = 60;     // slider 0..100 -> CAS SHARPNESS
    bool              hdrEnabled        = false;
    float             upscaleSharpness01 = 0.75f; // slider/100; RCAS lobe scale, SGSR edge derived
    // Debanding: terminal TPDF/IGN dither pass before the 8-bit swapchain quantize.
    bool              debandEnabled     = false;
    int               debandStrength    = 100;    // slider 0..200 -> strength/100 LSBs

    // Phase 2 screen effects (GL EffectComposer parity). FXAA/Toon/CRT/NTSC are
    // binary; Color is the brightness/contrast/gamma grade (always-applied via the
    // sliders, no toggle) and is "enabled" only when not at neutral (0,0,1).
    bool              fxaaEnabled       = false;
    bool              toonEnabled       = false;
    bool              crtEnabled        = false;
    bool              ntscEnabled       = false;
    bool              colorEnabled      = false;   // derived: !neutral grade
    float             colorBrightness   = 0.0f;    // [-1,1] (slider/100, clamped)
    float             colorContrast     = 0.0f;    // [0,2]  (slider/100, clamped)
    float             colorGamma        = 1.0f;    // [0.1,5]
    uint32_t          ntscFrameCounter  = 0;       // animates NTSC chroma phase

    VkSampler         upscaleSampler    = VK_NULL_HANDLE; // linear clamp; offscreen/mid input
    VkFormat          offscreenFmt      = VK_FORMAT_R8G8B8A8_UNORM;
    VkRenderPass      offscreenRenderPass = VK_NULL_HANDLE; // CLEAR -> SHADER_READ_ONLY
    VkPipelineLayout  postPipeLayout    = VK_NULL_HANDLE;
    VkPipeline        sgsrPipeline      = VK_NULL_HANDLE;
    VkPipeline        nisPipeline       = VK_NULL_HANDLE; // NVIDIA Image Scaling (mode 7)
    VkPipeline        easuPipeline      = VK_NULL_HANDLE;
    VkPipeline        rcasPipeline      = VK_NULL_HANDLE;
    VkPipeline        downscalePipeline = VK_NULL_HANDLE;
    // CAS/HDR can be either a non-final pass (writes an fx target via
    // offscreenRenderPass) or the final pass (writes the swapchain via renderPass);
    // a pipeline is render-pass-specific, so keep one variant of each.
    VkPipeline        casPipelineOff    = VK_NULL_HANDLE; // -> fx target (offscreenRenderPass)
    VkPipeline        casPipelineSwap   = VK_NULL_HANDLE; // -> swapchain (renderPass)
    VkPipeline        hdrPipelineOff    = VK_NULL_HANDLE;
    VkPipeline        hdrPipelineSwap   = VK_NULL_HANDLE;
    // Phase 2 effects: same Off (-> fx target) / Swap (-> swapchain) variant pair.
    VkPipeline        fxaaPipelineOff   = VK_NULL_HANDLE;
    VkPipeline        fxaaPipelineSwap  = VK_NULL_HANDLE;
    VkPipeline        toonPipelineOff   = VK_NULL_HANDLE;
    VkPipeline        toonPipelineSwap  = VK_NULL_HANDLE;
    VkPipeline        colorPipelineOff  = VK_NULL_HANDLE;
    VkPipeline        colorPipelineSwap = VK_NULL_HANDLE;
    VkPipeline        ntscPipelineOff   = VK_NULL_HANDLE;
    VkPipeline        ntscPipelineSwap  = VK_NULL_HANDLE;
    VkPipeline        crtPipelineOff    = VK_NULL_HANDLE;
    VkPipeline        crtPipelineSwap   = VK_NULL_HANDLE;
    // Debanding is always the LAST effect (terminal) -> only a swapchain variant.
    VkPipeline        debandPipelineSwap = VK_NULL_HANDLE;

    // offscreen composite target @ game/container resolution
    VkImage           offscreenImg  = VK_NULL_HANDLE;
    VkDeviceMemory    offscreenMem  = VK_NULL_HANDLE;
    VkImageView       offscreenView = VK_NULL_HANDLE;
    VkFramebuffer     offscreenFB   = VK_NULL_HANDLE;
    VkDescriptorSet   offscreenDS   = VK_NULL_HANDLE;
    int               offscreenW = 0, offscreenH = 0;

    // FSR intermediate (EASU output) @ upscale output resolution
    VkImage           midImg  = VK_NULL_HANDLE;
    VkDeviceMemory    midMem  = VK_NULL_HANDLE;
    VkImageView       midView = VK_NULL_HANDLE;
    VkFramebuffer     midFB   = VK_NULL_HANDLE;
    VkDescriptorSet   midDS   = VK_NULL_HANDLE;
    int               midW = 0, midH = 0;

    // Effect-chain intermediates @ swapchain resolution. fx1 holds the scaled (or
    // composited) image fed into CAS/HDR; fx2 ping-pongs when both effects run.
    VkImage           fx1Img  = VK_NULL_HANDLE;
    VkDeviceMemory    fx1Mem  = VK_NULL_HANDLE;
    VkImageView       fx1View = VK_NULL_HANDLE;
    VkFramebuffer     fx1FB   = VK_NULL_HANDLE;
    VkDescriptorSet   fx1DS   = VK_NULL_HANDLE;
    int               fx1W = 0, fx1H = 0;

    VkImage           fx2Img  = VK_NULL_HANDLE;
    VkDeviceMemory    fx2Mem  = VK_NULL_HANDLE;
    VkImageView       fx2View = VK_NULL_HANDLE;
    VkFramebuffer     fx2FB   = VK_NULL_HANDLE;
    VkDescriptorSet   fx2DS   = VK_NULL_HANDLE;
    int               fx2W = 0, fx2H = 0;

    // Per-frame upscale plan, computed in renderFrame, consumed by recordCmdBuf.
    // upFrame.mode reuses the upscalerMode enum (3=sgsr,4=fsr,5=fsr_fit,6=sharpen)
    // plus an internal sentinel (UPMODE_DOWNSCALE) for the supersampling path.
    struct UpscaleFrame {
        bool active = false;
        int  mode = 0;
        int  outX = 0, outY = 0, outW = 0, outH = 0;
        bool cas = false;   // run CAS this frame (snapshot of casEnabled)
        bool hdr = false;   // run HDR this frame (snapshot of hdrEnabled)
        bool fxaa = false;  // snapshot of fxaaEnabled
        bool toon = false;  // snapshot of toonEnabled
        bool color = false; // snapshot of colorEnabled (non-neutral grade)
        bool ntsc = false;  // snapshot of ntscEnabled
        bool crt = false;   // snapshot of crtEnabled
        bool deband = false;// snapshot of debandEnabled (terminal dither)
        SgsrPushConstants      sgsrPC{};
        NisPushConstants       nisPC{};
        EasuPushConstants      easuPC{};
        RcasPushConstants      rcasPC{};
        DownscalePushConstants dsPC{};
        CasPushConstants       casPC{};
        HdrPushConstants       hdrPC{};
        FxaaPushConstants      fxaaPC{};
        ToonPushConstants      toonPC{};
        ColorPushConstants     colorPC{};
        NtscPushConstants      ntscPC{};
        CrtPushConstants       crtPC{};
        DebandPushConstants    debandPC{};
    } upFrame;

    VkCommandPool                cmdPool = VK_NULL_HANDLE;
    std::vector<VkCommandBuffer> cmdBufs;

    std::vector<VkSemaphore> imgAvailSems;
    std::vector<VkSemaphore> renderDoneSems;
    std::vector<VkFence>     inFlightFences;
    std::vector<VkFence>     imgInFlight;
    uint32_t                 currentFrame = 0;

    VkSampler        sampler    = VK_NULL_HANDLE;
    VkDescriptorPool winTexPool = VK_NULL_HANDLE;

    std::atomic<bool> needsRender{false};
    std::thread       renderThread;
    std::atomic<bool> isRunning{false};
    std::atomic<bool> fbResized{false};
    std::mutex        renderMutex;
    std::mutex        dirtyMutex;
    std::condition_variable dirtyCV;
    std::shared_mutex frameMutex;

    void createInstance();
    void createSurface();
    void pickPhysicalDevice();
    void createLogicalDevice();
    void createSwapchain();
    void createRenderPass();
    void createDSLayout();
    void createPipeline(bool blend, VkPipeline& out);
    void createFramebuffers();
    void createCmdPool();
    void createSampler();
    void createUpscaleSampler();
    void createOffscreenRenderPass();
    void createPostPipelines();
    VkPipeline createPostPipeline(const uint32_t* fragCode, size_t fragSz, VkRenderPass rp);
    bool createColorTarget(int w, int h, VkImage& img, VkDeviceMemory& mem,
                           VkImageView& view, VkFramebuffer& fb, VkDescriptorSet& ds);
    void destroyColorTarget(VkImage& img, VkDeviceMemory& mem, VkImageView& view,
                            VkFramebuffer& fb, VkDescriptorSet& ds);
    bool ensureOffscreen(int w, int h);
    bool ensureMid(int w, int h);
    bool ensureFx1(int w, int h);
    bool ensureFx2(int w, int h);
    void recordUpscalePasses(VkCommandBuffer cb, uint32_t imgIdx,
                             const std::vector<DrawEntry>& draws, bool cursorDrawn,
                             short ptrX, short ptrY, short curHotX, short curHotY,
                             short curW, short curH,
                             float ox, float oy, float sx, float sy,
                             float cw, float ch);
    void planUpscaleFrame();
    void createWinTexPool();
    void createCursorPipeline();
    void createCursorDS();
    void createCmdBufs();
    void createSyncObjects();
    void cleanupSwapchain();

    bool  createWinTexResources(WinTex& wt, int w, int h);
    bool  importAHBToWinTex(WinTex& wt, AHardwareBuffer* ahb);
    void  cleanupAllAHBCache();
    void  flushDeleteQueue();
    void  destroyWinTex(WinTex& wt);
    void  ensureCursorTex(short w, short h);
    void  cleanupCursorTex();
    void  ensureCursorStaging(VkDeviceSize sz);

    void recordCmdBuf(VkCommandBuffer cb, uint32_t imgIdx,
        const std::vector<DrawEntry>& draws,
        std::vector<VkImageMemoryBarrier>& ahbTransitions,
        std::vector<VkImageMemoryBarrier>& preUpload,
        std::vector<VkImageMemoryBarrier>& postUpload,
        VkBuffer cursorUpload, bool hasCursorUpload,
        float ox, float oy, float sx, float sy, float cw, float ch,
        short ptrX, short ptrY, short curHotX, short curHotY,
        short curW, short curH, bool curVis);
    void renderLoop();
    void renderFrame();

    uint32_t        findMemType(uint32_t filter, VkMemoryPropertyFlags props);
    void            createBuffer(VkDeviceSize sz, VkBufferUsageFlags usage,
                                 VkMemoryPropertyFlags props, VkBuffer& buf, VkDeviceMemory& mem);
    VkCommandBuffer beginOneTime();
    void            endOneTime(VkCommandBuffer cmd);
    void            transition(VkCommandBuffer cmd, VkImage img,
                               VkImageLayout oldL, VkImageLayout newL,
                               VkAccessFlags srcA, VkAccessFlags dstA,
                               VkPipelineStageFlags srcS, VkPipelineStageFlags dstS);
    VkShaderModule  makeShader(const uint32_t* code, size_t sz);
};
