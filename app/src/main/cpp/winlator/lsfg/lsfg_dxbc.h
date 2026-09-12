#pragma once
// ============================================================================
// lsfg_dxbc — DXBC -> SPIR-V for the Lossless Scaling shader chain.
//
// Why this exists at all: the base chain inside Lossless.dll is DXBC, and has
// always been DXBC. Lossless Scaling 3.2.2's release note ("Added shaders
// intended for use by the lsfg-vk project") describes precompiled SPIR-V
// copies at RCDATA base+49 / base+98 — but no build downloadable from Steam
// carries them. Measured against the current public build (buildId 19655272,
// 2025-08-19; linux_testing byte-identical on depot 993091): the whole 311 MB
// install contains zero occurrences of the SPIR-V magic word, and the DLL's
// 202 RCDATA entries are all DXBC.
//
// So a translator is required, not optional. Upstream lsfg-vk reaches the same
// conclusion and links DXVK's `dxbc` in src/extract/trans.cpp; we vendor the
// same subset (zlib licence) under cpp/thirdparty/dxbc and follow its
// trans.cpp step for step.
//
// The binding renumber here is ENCOUNTER ORDER — the order in which Binding
// decorations appear in DXVK's output — which is what DXVK's own layout pairs
// with. The precompiled-SPIR-V path in lsfg_dll.cpp uses a set/binding sort
// instead, because those blobs were built with that convention. The two must
// not be merged.
// ============================================================================

#include <cstdint>
#include <vector>

namespace lsfg {

// Translate one DXBC compute shader to SPIR-V, renumbering descriptor bindings
// into a dense 0..n range in encounter order. Returns false (leaving outWords
// empty) on any malformed input — DXVK's compiler throws, and everything is
// caught here.
bool translateDxbc(const uint8_t* bytecode, uint32_t size, std::vector<uint32_t>& outWords);

} // namespace lsfg
