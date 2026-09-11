#pragma once
// ============================================================================
// lsfg_dll — extract the Lossless Scaling frame-generation shader chain from
// the user's own Lossless.dll, and cache it on device as SPIR-V.
//
// The 25 compute shaders that make up LSFG 3.1 are NOT redistributable. They
// ship as RCDATA resources inside Lossless.dll from the user's own paid copy
// of Lossless Scaling (Steam app 993090). Every implementation — upstream
// lsfg-vk, LSFG-Android, eden, WinNative — requires the user to supply their
// own DLL and extracts the resources on device. Bannerlator does the same, and
// bundles nothing.
//
// The DLL is mmap'd READ-ONLY and parsed as data. It is never loaded as a
// library and never executed.
//
// Resource layout, measured against real installs:
//   * The base chain lives at RCDATA ids 255, 256 and 280-302 (25 modules),
//     and is DXBC. It has always been DXBC.
//   * Lossless Scaling MAY additionally carry precompiled SPIR-V copies at
//     base+49 (native fp16) and base+98 (native fp32). No build currently
//     downloadable from Steam carries them — public buildId 19655272 was
//     measured with zero SPIR-V anywhere in the 311 MB install — so the DXBC
//     path is the normal path and the translator is not optional. The SPIR-V
//     path is kept because it costs nothing and a future build may ship them.
//
// Both producers fill the same shader-id -> SPIR-V words map, so everything
// downstream of this file is source-agnostic.
//
// Derived from WinNative's lsfg_dll.c (GPL-3.0-or-later), whose LSFG port is
// credited to Camille LaVey / the Eden Emulator Project, and which follows
// upstream lsfg-vk (PancakeTAS). Bannerlator is GPL-3.0; see THIRD-PARTY-LICENSES.md.
// ============================================================================

#include <cstdint>
#include <string>
#include <vector>

namespace lsfg {

enum class DllStatus {
    Ok = 0,
    NotInstalled,        // no such file / cannot open
    UnreadableFile,      // opens but cannot be mapped or is empty
    NotPortableExecutable,
    MissingShaders,      // parses, but the base chain is not all there
    TranslationFailed,   // a shader failed DXBC->SPIR-V or SPIR-V adoption
    CacheUnusable        // cache could not be written or read back
};

// SPIR-V version words. The translator emits 1.6; a Vulkan 1.1 device takes
// 1.4 (via VK_KHR_spirv_1_4) and a 1.2 device 1.5. One definition for the
// probe, the engine and the shader loader.
constexpr uint32_t kSpirv14 = 0x00010400u;
constexpr uint32_t kSpirv15 = 0x00010500u;
constexpr uint32_t kSpirv16 = 0x00010600u;

enum class Variant {
    None = 0,
    SpirvFp16,      // precompiled fp16 blobs at base+49
    SpirvFp32,      // precompiled fp32 blobs at base+98
    DxbcTranslated  // base chain, translated on device (the normal case)
};

// The 25 shader ids that make up the chain.
constexpr uint32_t kShaderMipmaps   = 255u;
constexpr uint32_t kShaderGenerate  = 256u;
constexpr uint32_t kShaderChainFirst= 280u;
constexpr uint32_t kShaderChainLast = 302u;
constexpr uint32_t kShaderCount     = 25u;

// Upstream spelling for the two ids the ported chain files refer to by name.
// Kept so those files stay byte-close to their upstream form; these alias the
// constants above rather than being a second source of truth.
constexpr uint32_t LSFG_SHADER_MIPMAPS  = kShaderMipmaps;
constexpr uint32_t LSFG_SHADER_GENERATE = kShaderGenerate;

struct Module {
    uint32_t              id = 0;
    std::vector<uint32_t> words;   // SPIR-V
};

struct ModuleSet {
    std::vector<Module> modules;
    Variant             variant = Variant::None;

    const std::vector<uint32_t>* find(uint32_t id) const;
    bool complete() const;
};

// The canonical shader-id list, in dispatch-independent order.
const std::vector<uint32_t>& shaderIds();

const char* statusName(DllStatus s);
const char* variantName(Variant v);

// Does this file look like a usable Lossless.dll? Checks the base chain only,
// so a DLL without the SPIR-V variants (i.e. every build on Steam today) is
// reported as valid — the translator handles it.
DllStatus validateDll(const std::string& dllPath);

// Which producer would be used for this DLL.
Variant dllVariant(const std::string& dllPath, bool preferFp16);

// Parse the DLL, produce all 25 SPIR-V modules, and write them to cachePath
// (via temp file + rename, so a failed build cannot leave a half-written
// cache behind). Records which producer ran in the cache header.
DllStatus buildCache(const std::string& dllPath, const std::string& cachePath, bool preferFp16);

// Is the cache current for this DLL? Compares source size + content hash.
DllStatus cacheMatchesSource(const std::string& cachePath, const std::string& dllPath,
                             bool& outMatches);

// Load a previously built cache.
DllStatus loadModules(const std::string& cachePath, ModuleSet& outSet);

// Which producer built an existing cache, without loading the modules.
DllStatus cacheVariant(const std::string& cachePath, Variant& outVariant);

// Experimental Vulkan 1.1 / 1.2 compat: lower a DXBC-translated module's
// declared SPIR-V version to `targetVersion` (kSpirv14 or kSpirv15). Below 1.5
// the Vulkan memory model is not core, so SPV_KHR_vulkan_memory_model is
// declared for it (the device side enables VK_KHR_vulkan_memory_model). The
// cache is built without a device in hand, always at the translator's native
// 1.6, so this runs at load time against whatever the device can take. A
// module already at or below the target is left untouched. Returns false,
// leaving the module untouched, for a malformed module, a target below 1.4, or
// a module declaring DemoteToHelperInvocation: that capability is core only in
// 1.6 and its extension is never enabled on the compat path, so such a module
// cannot be made legal here (DXVK emits it for pixel-shader discard only, so
// the 25 compute shaders never carry it). Whether the device then accepts the
// result is vkCreateShaderModule's / vkCreateComputePipelines' verdict.
bool downgradeSpirv(std::vector<uint32_t>& words, uint32_t targetVersion);

} // namespace lsfg
