// See lsfg_dxbc.h. DXBC -> SPIR-V via DXVK's vendored translator.
//
// Follows upstream lsfg-vk's src/extract/trans.cpp, including its
// encounter-order binding renumber. Derived from WinNative's lsfg_dxbc.cpp
// (GPL-3.0-or-later); the translator itself is DXVK's, zlib licensed, vendored
// under cpp/thirdparty/dxbc.

#include "lsfg_dxbc.h"

#include <algorithm>
#include <cstring>

#include <dxbc_modinfo.h>
#include <dxbc_module.h>
#include <dxbc_reader.h>
#include <thirdparty/spirv.hpp>

namespace lsfg {
namespace {

struct BindingSlot {
    uint32_t bindingIndex  = 0;
    uint32_t bindingOffset = 0;   // word offset of the Binding literal
    uint32_t setIndex      = 0;
    uint32_t setOffset     = 0;
};

} // namespace

bool translateDxbc(const uint8_t* bytecode, uint32_t size, std::vector<uint32_t>& outWords) {
    outWords.clear();
    if (!bytecode || size < 4) return false;

    try {
        dxvk::DxbcReader reader(reinterpret_cast<const char*>(bytecode), size);
        dxvk::DxbcModule module(reader);
        const dxvk::DxbcModuleInfo info{};
        auto code = module.compile(info, "CS");

        // Collect the descriptor decorations in the order the Binding
        // decorations appear. Everything before the first OpFunction is the
        // decoration block, so the scan stops there.
        std::vector<BindingSlot> slotsByVarId;
        std::vector<uint32_t>    varIdsInOrder;
        for (auto ins : code) {
            if (ins.opCode() == spv::OpDecorate) {
                const uint32_t varId = ins.arg(1);
                if (ins.arg(2) == spv::DecorationBinding) {
                    slotsByVarId.resize(std::max(slotsByVarId.size(), (size_t)varId + 1));
                    slotsByVarId[varId].bindingIndex  = ins.arg(3);
                    slotsByVarId[varId].bindingOffset = ins.offset() + 3;
                    varIdsInOrder.push_back(varId);
                } else if (ins.arg(2) == spv::DecorationDescriptorSet) {
                    slotsByVarId.resize(std::max(slotsByVarId.size(), (size_t)varId + 1));
                    slotsByVarId[varId].setIndex  = ins.arg(3);
                    slotsByVarId[varId].setOffset = ins.offset() + 3;
                }
            }
            if (ins.opCode() == spv::OpFunction) break;
        }

        // Renumber to a dense 0..n range, in encounter order.
        uint32_t next = 0;
        for (const uint32_t varId : varIdsInOrder) {
            const BindingSlot& slot = slotsByVarId[varId];
            if (slot.bindingOffset == 0) continue;
            code.data()[slot.bindingOffset] = next++;
        }

        const size_t byteSize = code.size();
        if (byteSize == 0 || byteSize % sizeof(uint32_t) != 0) return false;

        outWords.resize(byteSize / sizeof(uint32_t));
        std::memcpy(outWords.data(), code.data(), byteSize);
        return true;
    } catch (...) {
        // DXVK's compiler signals malformed bytecode by throwing. A bad
        // resource must degrade to "this DLL is unusable", never to a crash.
        outWords.clear();
        return false;
    }
}

} // namespace lsfg
