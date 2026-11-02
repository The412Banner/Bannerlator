// Ported from DroidDeck (github.com/Droid-Deck/DroidDeck, PRs #1969 and #1981).
#ifndef BANNERLATOR_KGSL_LAYOUT_H
#define BANNERLATOR_KGSL_LAYOUT_H
#include <stddef.h>

// Register layout per GPU generation. A counter kgsl places outside the range the kernel's CP protection lets
// command buffers read (adreno-gpulist.h *_protected_regs) is not read.
struct kgsl_layout {
    unsigned always_on;     // CP_ALWAYS_ON_COUNTER
    unsigned cycles;        // RBBM countable that counts GPU cycles
    unsigned readable_last; // last register of the readable range starting at 0
};
// A6xx/A7xx: CP_ALWAYS_ON_COUNTER 0x980, RBBM countable 0 = ALWAYS_COUNT, 0x000-0x4ff readable (gen7 list).
static const struct kgsl_layout KGSL_LAYOUT_A7XX = {0x980u, 0u, 0x4ffu};
// A8xx: CP_ALWAYS_ON_COUNTER 0x8e7 (gen8_reg.h), RBBM countable 1 = US_ALWAYS_COUNT (0 is NEVER_COUNT, Mesa
// a8xx_perfcntrs.xml), 0x000-0x3a3 readable (gen8_0_0_protected_regs).
static const struct kgsl_layout KGSL_LAYOUT_A8XX = {0x8e7u, 1u, 0x3a3u};

// The KGSL chip id's top byte: 0x44 on every A8xx (Adreno 810/829/830/840, Mesa freedreno_devices.py),
// 0x43 or the generation number on A6xx/A7xx. Later generations are not known, so nothing is read there.
static inline const struct kgsl_layout *kgsl_layout_for(unsigned chip_id) {
    unsigned core = chip_id >> 24;
    if (core == 0x44u) return &KGSL_LAYOUT_A8XX;
    if (core > 0x44u) return NULL;
    return &KGSL_LAYOUT_A7XX;
}

#endif
