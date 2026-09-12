// See lsfg_dll.h. PE resource walk + on-device SPIR-V cache for the Lossless
// Scaling frame-generation chain.
//
// Derived from WinNative's lsfg_dll.c (GPL-3.0-or-later), LSFG port credited to
// Camille LaVey / the Eden Emulator Project, following upstream lsfg-vk
// (PancakeTAS). Bannerlator is GPL-3.0.

#include "lsfg_dll.h"
#include "lsfg_dxbc.h"

#include <android/log.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cstdio>
#include <cstring>

#define LOG_TAG "LsfgDll"
#define LSFG_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LSFG_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace lsfg {
namespace {

// ---- PE constants ---------------------------------------------------------
constexpr uint16_t kDosMagic       = 0x5A4Du;   // "MZ"
constexpr uint32_t kPeSignature    = 0x00004550u; // "PE\0\0"
constexpr uint16_t kPe32Magic      = 0x010Bu;
constexpr uint16_t kPe32PlusMagic  = 0x020Bu;

constexpr size_t kDosLfanewOffset        = 0x3Cu;
constexpr size_t kCoffHeaderSize         = 20u;
constexpr size_t kOptionalHeaderSizeOff  = 16u;
constexpr size_t kDataDirectoryEntrySize = 8u;
constexpr size_t kDataDirectoryOffPe32   = 96u;
constexpr size_t kDataDirectoryOffPe32P  = 112u;
constexpr size_t kResourceDirectoryIndex = 2u;
constexpr size_t kSectionHeaderSize      = 40u;

constexpr size_t   kResourceDirectorySize   = 16u;
constexpr size_t   kResourceNamedCountOff   = 12u;
constexpr size_t   kResourceIdCountOff      = 14u;
constexpr size_t   kResourceEntrySize       = 8u;
constexpr uint32_t kResourceSubdirectoryBit = 0x80000000u;
constexpr uint32_t kResourceTypeRcData      = 10u;

// ---- SPIR-V constants -----------------------------------------------------
constexpr uint32_t kSpirvMagic          = 0x07230203u;
constexpr size_t   kSpirvHeaderWords    = 5u;
constexpr uint32_t kSpirvWordCountShift = 16u;
constexpr uint32_t kSpirvOpcodeMask     = 0xFFFFu;
constexpr uint32_t kSpirvOpFunction     = 54u;
constexpr uint32_t kSpirvOpDecorate     = 71u;
constexpr uint32_t kDecorationBinding       = 33u;
constexpr uint32_t kDecorationDescriptorSet = 34u;
constexpr size_t   kDecorationLiteralWord   = 3u;

constexpr uint32_t kVariantFp16Offset = 49u;
constexpr uint32_t kVariantFp32Offset = 98u;

constexpr uint32_t kMaxResourceId = 512u;
constexpr size_t   kMaxSpirvWords = 16u * 1024u * 1024u;

constexpr uint32_t kCacheMagic   = 0x4C534642u;  // "BFSL" little-endian
constexpr uint32_t kCacheVersion = 1u;

struct CacheHeader {
    uint32_t magic;
    uint32_t version;
    uint64_t sourceSize;
    uint64_t sourceHash;
    uint32_t moduleCount;
    uint32_t variant;
};

struct PeSection {
    uint32_t virtualAddress = 0;
    uint32_t virtualSize    = 0;
    uint32_t rawAddress     = 0;
    uint32_t rawSize        = 0;
};

// Read-only mapping of the DLL. Closes itself; the DLL is never executed.
class PeImage {
public:
    ~PeImage() { close(); }

    bool open(const std::string& path) {
        fd_ = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd_ < 0) return false;
        struct stat info{};
        if (fstat(fd_, &info) != 0 || info.st_size <= 0) { close(); return false; }
        size_ = (size_t)info.st_size;
        void* m = mmap(nullptr, size_, PROT_READ, MAP_PRIVATE, fd_, 0);
        if (m == MAP_FAILED) { close(); return false; }
        data_ = (const uint8_t*)m;
        return true;
    }

    void close() {
        if (data_) { munmap((void*)data_, size_); data_ = nullptr; }
        if (fd_ >= 0) { ::close(fd_); fd_ = -1; }
        size_ = 0;
        sections_.clear();
    }

    const uint8_t* data() const { return data_; }
    size_t size() const { return size_; }

    bool readU16(size_t off, uint16_t& out) const {
        if (off > size_ || size_ - off < sizeof(uint16_t)) return false;
        memcpy(&out, data_ + off, sizeof(uint16_t));
        return true;
    }
    bool readU32(size_t off, uint32_t& out) const {
        if (off > size_ || size_ - off < sizeof(uint32_t)) return false;
        memcpy(&out, data_ + off, sizeof(uint32_t));
        return true;
    }

    bool findPeHeader(size_t& outOffset) const {
        uint16_t dos = 0;
        if (!readU16(0, dos) || dos != kDosMagic) return false;
        uint32_t peOff = 0;
        if (!readU32(kDosLfanewOffset, peOff)) return false;
        uint32_t sig = 0;
        if (!readU32(peOff, sig) || sig != kPeSignature) return false;
        outOffset = (size_t)peOff;
        return true;
    }

    bool readSections(size_t peOffset) {
        uint16_t count = 0, optSize = 0;
        if (!readU16(peOffset + 4 + 2, count) ||
            !readU16(peOffset + 4 + kOptionalHeaderSizeOff, optSize)) return false;
        if (count == 0) return false;

        sections_.assign(count, PeSection{});
        const size_t table = peOffset + 4 + kCoffHeaderSize + optSize;
        for (uint16_t i = 0; i < count; i++) {
            const size_t off = table + (size_t)i * kSectionHeaderSize;
            PeSection& s = sections_[i];
            if (!readU32(off + 8,  s.virtualSize)    ||
                !readU32(off + 12, s.virtualAddress) ||
                !readU32(off + 16, s.rawSize)        ||
                !readU32(off + 20, s.rawAddress)) { sections_.clear(); return false; }
        }
        return true;
    }

    bool rvaToOffset(uint32_t rva, size_t& outOffset) const {
        for (const PeSection& s : sections_) {
            const uint32_t span = s.virtualSize > s.rawSize ? s.virtualSize : s.rawSize;
            if (span == 0 || rva < s.virtualAddress) continue;
            const uint32_t rel = rva - s.virtualAddress;
            if (rel < span) { outOffset = (size_t)s.rawAddress + rel; return true; }
        }
        return false;
    }

private:
    int             fd_   = -1;
    const uint8_t*  data_ = nullptr;
    size_t          size_ = 0;
    std::vector<PeSection> sections_;
};

// RCDATA id -> blob, for the ids we care about.
struct ResourceTable {
    const uint8_t* data[kMaxResourceId] = {};
    uint32_t       size[kMaxResourceId] = {};
};

uint64_t fnv1a64(const uint8_t* data, size_t size) {
    uint64_t hash = 1469598103934665603ULL;
    for (size_t i = 0; i < size; i++) {
        hash ^= (uint64_t)data[i];
        hash *= 1099511628211ULL;
    }
    return hash;
}

bool resourceEntryCount(const PeImage& img, size_t dirOffset, size_t& outTotal) {
    uint16_t named = 0, ids = 0;
    if (!img.readU16(dirOffset + kResourceNamedCountOff, named) ||
        !img.readU16(dirOffset + kResourceIdCountOff, ids)) return false;
    outTotal = (size_t)named + (size_t)ids;
    return true;
}

bool resourceEntryAt(const PeImage& img, size_t dirOffset, size_t index,
                     uint32_t& outId, uint32_t& outOffset,
                     bool& outIsDirectory, bool& outIsNamed) {
    const size_t off = dirOffset + kResourceDirectorySize + index * kResourceEntrySize;
    uint32_t name = 0, data = 0;
    if (!img.readU32(off, name) || !img.readU32(off + 4, data)) return false;
    outId          = name & ~kResourceSubdirectoryBit;
    outOffset      = data & ~kResourceSubdirectoryBit;
    outIsDirectory = (data & kResourceSubdirectoryBit) != 0;
    outIsNamed     = (name & kResourceSubdirectoryBit) != 0;
    return true;
}

bool resourceReadLeaf(const PeImage& img, size_t leafOffset,
                      const uint8_t*& outData, uint32_t& outSize) {
    uint32_t rva = 0, size = 0;
    if (!img.readU32(leafOffset, rva) || !img.readU32(leafOffset + 4, size) || size == 0)
        return false;
    size_t dataOffset = 0;
    if (!img.rvaToOffset(rva, dataOffset)) return false;
    if (dataOffset > img.size() || img.size() - dataOffset < size) return false;
    outData = img.data() + dataOffset;
    outSize = size;
    return true;
}

// Walk type -> name -> language and keep the first language of every RCDATA id.
bool collectRcData(const PeImage& img, size_t resourceBase, ResourceTable& out) {
    size_t typeTotal = 0;
    if (!resourceEntryCount(img, resourceBase, typeTotal)) return false;

    for (size_t t = 0; t < typeTotal; t++) {
        uint32_t typeId = 0, typeOffset = 0;
        bool typeIsDir = false, typeIsNamed = false;
        if (!resourceEntryAt(img, resourceBase, t, typeId, typeOffset, typeIsDir, typeIsNamed))
            return false;
        if (typeIsNamed || typeId != kResourceTypeRcData || !typeIsDir) continue;

        const size_t nameBase = resourceBase + typeOffset;
        size_t nameTotal = 0;
        if (!resourceEntryCount(img, nameBase, nameTotal)) return false;

        for (size_t n = 0; n < nameTotal; n++) {
            uint32_t nameId = 0, nameOffset = 0;
            bool nameIsDir = false, nameIsNamed = false;
            if (!resourceEntryAt(img, nameBase, n, nameId, nameOffset, nameIsDir, nameIsNamed))
                return false;
            if (nameIsNamed || !nameIsDir || nameId >= kMaxResourceId) continue;

            const size_t langBase = resourceBase + nameOffset;
            size_t langTotal = 0;
            if (!resourceEntryCount(img, langBase, langTotal)) return false;

            for (size_t l = 0; l < langTotal; l++) {
                uint32_t langId = 0, langOffset = 0;
                bool langIsDir = false, langIsNamed = false;
                if (!resourceEntryAt(img, langBase, l, langId, langOffset, langIsDir, langIsNamed))
                    return false;
                if (langIsDir) continue;

                const uint8_t* blob = nullptr;
                uint32_t blobSize = 0;
                if (!resourceReadLeaf(img, resourceBase + langOffset, blob, blobSize)) continue;
                out.data[nameId] = blob;
                out.size[nameId] = blobSize;
                break;   // first language wins
            }
        }
    }
    return true;
}

DllStatus parseResources(PeImage& img, ResourceTable& table) {
    size_t peOffset = 0;
    if (!img.findPeHeader(peOffset)) return DllStatus::NotPortableExecutable;
    if (!img.readSections(peOffset)) return DllStatus::NotPortableExecutable;

    uint16_t optionalMagic = 0;
    const size_t optionalHeaderOffset = peOffset + 4 + kCoffHeaderSize;
    if (!img.readU16(optionalHeaderOffset, optionalMagic)) return DllStatus::NotPortableExecutable;

    size_t dataDirectory = 0;
    if (optionalMagic == kPe32Magic)          dataDirectory = optionalHeaderOffset + kDataDirectoryOffPe32;
    else if (optionalMagic == kPe32PlusMagic) dataDirectory = optionalHeaderOffset + kDataDirectoryOffPe32P;
    else return DllStatus::NotPortableExecutable;

    uint32_t resourceRva = 0;
    if (!img.readU32(dataDirectory + kResourceDirectoryIndex * kDataDirectoryEntrySize, resourceRva)
        || resourceRva == 0) {
        return DllStatus::MissingShaders;
    }

    size_t resourceBase = 0;
    if (!img.rvaToOffset(resourceRva, resourceBase)) return DllStatus::MissingShaders;
    if (!collectRcData(img, resourceBase, table)) return DllStatus::MissingShaders;
    return DllStatus::Ok;
}

// ---- SPIR-V adoption (precompiled-variant path) ---------------------------

bool isSpirvModule(const uint8_t* blob, uint32_t size) {
    if (!blob || size < kSpirvHeaderWords * sizeof(uint32_t)) return false;
    if (size % sizeof(uint32_t) != 0) return false;
    uint32_t magic = 0;
    memcpy(&magic, blob, sizeof(magic));
    return magic == kSpirvMagic;
}

uint32_t lookupDescriptorSet(const std::vector<uint32_t>& w, uint32_t targetId) {
    size_t off = kSpirvHeaderWords;
    while (off < w.size()) {
        const uint32_t length = w[off] >> kSpirvWordCountShift;
        const uint32_t opcode = w[off] & kSpirvOpcodeMask;
        if (length == 0 || off + length > w.size()) break;
        if (opcode == kSpirvOpFunction) break;
        if (opcode == kSpirvOpDecorate && length >= 4 &&
            w[off + 2] == kDecorationDescriptorSet && w[off + 1] == targetId) {
            return w[off + 3];
        }
        off += length;
    }
    return 0;
}

// Precompiled blobs are renumbered into set/binding order — that is the
// convention they were built with. The DXBC path uses encounter order instead
// (see lsfg_dxbc), which is what DXVK's output pairs with. Do not merge them.
bool renumberBindingsSetOrder(std::vector<uint32_t>& w) {
    struct Slot { uint32_t set; uint32_t binding; size_t literalOffset; };
    std::vector<Slot> slots;

    size_t off = kSpirvHeaderWords;
    while (off < w.size()) {
        const uint32_t length = w[off] >> kSpirvWordCountShift;
        const uint32_t opcode = w[off] & kSpirvOpcodeMask;
        if (length == 0 || off + length > w.size()) return false;
        if (opcode == kSpirvOpFunction) break;
        if (opcode == kSpirvOpDecorate && length >= 4 && w[off + 2] == kDecorationBinding) {
            slots.push_back(Slot{ lookupDescriptorSet(w, w[off + 1]),
                                  w[off + 3],
                                  off + kDecorationLiteralWord });
        }
        off += length;
    }
    if (slots.empty()) return true;

    std::sort(slots.begin(), slots.end(), [](const Slot& a, const Slot& b) {
        return a.set != b.set ? a.set < b.set : a.binding < b.binding;
    });
    for (size_t i = 0; i < slots.size(); i++) w[slots[i].literalOffset] = (uint32_t)i;
    return true;
}

bool adoptSpirv(const uint8_t* blob, uint32_t size, std::vector<uint32_t>& outWords) {
    if (!isSpirvModule(blob, size)) return false;
    const size_t wordCount = size / sizeof(uint32_t);
    if (wordCount > kMaxSpirvWords) return false;

    outWords.resize(wordCount);
    memcpy(outWords.data(), blob, wordCount * sizeof(uint32_t));
    if (!renumberBindingsSetOrder(outWords)) { outWords.clear(); return false; }
    return true;
}

bool hasNativeVariant(const ResourceTable& t, uint32_t variantOffset) {
    for (uint32_t id : shaderIds()) {
        const uint32_t vid = id + variantOffset;
        if (vid >= kMaxResourceId) return false;
        if (!isSpirvModule(t.data[vid], t.size[vid])) return false;
    }
    return true;
}

bool hasBaseChain(const ResourceTable& t) {
    for (uint32_t id : shaderIds()) {
        if (id >= kMaxResourceId || t.data[id] == nullptr) return false;
    }
    return true;
}

Variant selectVariant(const ResourceTable& t, bool preferFp16) {
    if (preferFp16 && hasNativeVariant(t, kVariantFp16Offset)) return Variant::SpirvFp16;
    if (hasNativeVariant(t, kVariantFp32Offset)) return Variant::SpirvFp32;
    if (hasNativeVariant(t, kVariantFp16Offset)) return Variant::SpirvFp16;
    return Variant::None;
}

uint32_t variantOffset(Variant v) {
    return v == Variant::SpirvFp16 ? kVariantFp16Offset : kVariantFp32Offset;
}

bool writeCache(const std::string& cachePath, const CacheHeader& header, const ModuleSet& set) {
    const std::string tempPath = cachePath + ".tmp";
    FILE* f = fopen(tempPath.c_str(), "wb");
    if (!f) return false;

    bool ok = fwrite(&header, sizeof(header), 1, f) == 1;
    for (size_t i = 0; ok && i < set.modules.size(); i++) {
        const Module& m = set.modules[i];
        const uint32_t wordCount = (uint32_t)m.words.size();
        ok = fwrite(&m.id, sizeof(m.id), 1, f) == 1
          && fwrite(&wordCount, sizeof(wordCount), 1, f) == 1
          && fwrite(m.words.data(), sizeof(uint32_t), wordCount, f) == wordCount;
    }
    if (ok) ok = fflush(f) == 0;
    if (ok) ok = fsync(fileno(f)) == 0;
    fclose(f);

    // Temp file + rename, so a failed or interrupted build can never leave a
    // half-written cache that would later be loaded as if it were complete.
    if (!ok || rename(tempPath.c_str(), cachePath.c_str()) != 0) {
        unlink(tempPath.c_str());
        return false;
    }
    return true;
}

bool readCacheHeader(const std::string& cachePath, CacheHeader& out) {
    FILE* f = fopen(cachePath.c_str(), "rb");
    if (!f) return false;
    const bool ok = fread(&out, sizeof(out), 1, f) == 1;
    fclose(f);
    return ok && out.magic == kCacheMagic && out.version == kCacheVersion;
}

} // namespace

// ---- public API -----------------------------------------------------------

const std::vector<uint32_t>& shaderIds() {
    static const std::vector<uint32_t> ids = [] {
        std::vector<uint32_t> v;
        v.reserve(kShaderCount);
        v.push_back(kShaderMipmaps);
        v.push_back(kShaderGenerate);
        for (uint32_t id = kShaderChainFirst; id <= kShaderChainLast; id++) v.push_back(id);
        return v;
    }();
    return ids;
}

const std::vector<uint32_t>* ModuleSet::find(uint32_t id) const {
    for (const Module& m : modules) if (m.id == id) return &m.words;
    return nullptr;
}

bool ModuleSet::complete() const {
    if (modules.size() != kShaderCount) return false;
    for (uint32_t id : shaderIds()) if (!find(id)) return false;
    return true;
}

const char* statusName(DllStatus s) {
    switch (s) {
        case DllStatus::Ok:                    return "ok";
        case DllStatus::NotInstalled:          return "not installed";
        case DllStatus::UnreadableFile:        return "unreadable file";
        case DllStatus::NotPortableExecutable: return "not a PE file";
        case DllStatus::MissingShaders:        return "missing shaders";
        case DllStatus::TranslationFailed:     return "shader translation failed";
        case DllStatus::CacheUnusable:         return "cache unusable";
    }
    return "unknown";
}

const char* variantName(Variant v) {
    switch (v) {
        case Variant::SpirvFp16:      return "spirv-fp16";
        case Variant::SpirvFp32:      return "spirv-fp32";
        case Variant::DxbcTranslated: return "dxbc-translated";
        case Variant::None:           return "none";
    }
    return "none";
}

DllStatus validateDll(const std::string& dllPath) {
    if (dllPath.empty()) return DllStatus::NotInstalled;

    PeImage img;
    if (!img.open(dllPath)) return DllStatus::NotInstalled;

    ResourceTable table;
    DllStatus status = parseResources(img, table);
    if (status == DllStatus::Ok && !hasBaseChain(table)) status = DllStatus::MissingShaders;
    return status;
}

Variant dllVariant(const std::string& dllPath, bool preferFp16) {
    if (dllPath.empty()) return Variant::None;

    PeImage img;
    if (!img.open(dllPath)) return Variant::None;

    ResourceTable table;
    if (parseResources(img, table) != DllStatus::Ok) return Variant::None;

    Variant v = selectVariant(table, preferFp16);
    if (v == Variant::None && hasBaseChain(table)) v = Variant::DxbcTranslated;
    return v;
}

DllStatus buildCache(const std::string& dllPath, const std::string& cachePath, bool preferFp16) {
    if (dllPath.empty() || cachePath.empty()) return DllStatus::NotInstalled;

    PeImage img;
    if (!img.open(dllPath)) return DllStatus::NotInstalled;

    ResourceTable table;
    DllStatus status = parseResources(img, table);
    if (status != DllStatus::Ok) return status;

    const Variant precompiled = selectVariant(table, preferFp16);
    const bool translate = precompiled == Variant::None;
    if (translate && !hasBaseChain(table)) return DllStatus::MissingShaders;

    ModuleSet set;
    set.variant = translate ? Variant::DxbcTranslated : precompiled;
    set.modules.reserve(kShaderCount);

    const uint32_t offset = translate ? 0u : variantOffset(precompiled);
    for (uint32_t id : shaderIds()) {
        const uint32_t resourceId = id + offset;
        if (resourceId >= kMaxResourceId) return DllStatus::MissingShaders;

        Module m;
        m.id = id;
        const bool ok = translate
            ? translateDxbc(table.data[resourceId], table.size[resourceId], m.words)
            : adoptSpirv(table.data[resourceId], table.size[resourceId], m.words);
        if (!ok || m.words.empty()) {
            LSFG_LOGE("shader %u (%s) failed", id, translate ? "dxbc" : "spirv");
            return DllStatus::TranslationFailed;
        }
        set.modules.push_back(std::move(m));
    }

    CacheHeader header{};
    header.magic       = kCacheMagic;
    header.version     = kCacheVersion;
    header.sourceSize  = (uint64_t)img.size();
    header.sourceHash  = fnv1a64(img.data(), img.size());
    header.moduleCount = (uint32_t)set.modules.size();
    header.variant     = (uint32_t)set.variant;

    if (!writeCache(cachePath, header, set)) {
        LSFG_LOGE("cache write failed: %s", cachePath.c_str());
        return DllStatus::CacheUnusable;
    }

    size_t totalWords = 0;
    for (const Module& m : set.modules) totalWords += m.words.size();
    LSFG_LOGI("cached %zu LSFG modules, %zu SPIR-V words (%s)",
              set.modules.size(), totalWords, variantName(set.variant));
    return DllStatus::Ok;
}

DllStatus cacheMatchesSource(const std::string& cachePath, const std::string& dllPath,
                             bool& outMatches) {
    outMatches = false;
    if (cachePath.empty() || dllPath.empty()) return DllStatus::CacheUnusable;

    CacheHeader header{};
    if (!readCacheHeader(cachePath, header)) return DllStatus::CacheUnusable;

    PeImage img;
    if (!img.open(dllPath)) return DllStatus::NotInstalled;

    outMatches = header.sourceSize == (uint64_t)img.size()
              && header.sourceHash == fnv1a64(img.data(), img.size());
    return DllStatus::Ok;
}

DllStatus cacheVariant(const std::string& cachePath, Variant& outVariant) {
    outVariant = Variant::None;
    CacheHeader header{};
    if (!readCacheHeader(cachePath, header)) return DllStatus::CacheUnusable;
    if (header.variant > (uint32_t)Variant::DxbcTranslated) return DllStatus::CacheUnusable;
    outVariant = (Variant)header.variant;
    return DllStatus::Ok;
}

DllStatus loadModules(const std::string& cachePath, ModuleSet& outSet) {
    outSet.modules.clear();
    outSet.variant = Variant::None;
    if (cachePath.empty()) return DllStatus::CacheUnusable;

    FILE* f = fopen(cachePath.c_str(), "rb");
    if (!f) return DllStatus::NotInstalled;

    CacheHeader header{};
    if (fread(&header, sizeof(header), 1, f) != 1 || header.magic != kCacheMagic ||
        header.version != kCacheVersion || header.moduleCount != kShaderCount) {
        fclose(f);
        return DllStatus::CacheUnusable;
    }
    outSet.variant = (Variant)header.variant;
    outSet.modules.reserve(header.moduleCount);

    DllStatus status = DllStatus::Ok;
    for (uint32_t i = 0; i < header.moduleCount; i++) {
        uint32_t id = 0, wordCount = 0;
        if (fread(&id, sizeof(id), 1, f) != 1 ||
            fread(&wordCount, sizeof(wordCount), 1, f) != 1 ||
            wordCount == 0 || wordCount > kMaxSpirvWords) {
            status = DllStatus::CacheUnusable;
            break;
        }
        Module m;
        m.id = id;
        m.words.resize(wordCount);
        if (fread(m.words.data(), sizeof(uint32_t), wordCount, f) != wordCount) {
            status = DllStatus::CacheUnusable;
            break;
        }
        outSet.modules.push_back(std::move(m));
    }
    fclose(f);

    if (status == DllStatus::Ok && !outSet.complete()) status = DllStatus::MissingShaders;
    if (status != DllStatus::Ok) { outSet.modules.clear(); outSet.variant = Variant::None; }
    return status;
}

} // namespace lsfg
