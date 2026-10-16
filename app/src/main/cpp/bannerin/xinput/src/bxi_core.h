/*
 * bxi_core.h - the BANNERIN slot-file reader used by BANNERIN-XInput.
 *
 * Portable C99 plus the GCC/Clang __atomic builtins, no OS calls: the Windows DLL (src/xinput.c)
 * and the Linux host test (test/host_test.c) include the same code, so the protocol and the
 * XInput mapping the CI checks on Linux x86_64 and aarch64 are the ones the DLL ships.
 *
 * FILE (format 1, see core/src/layout.rs; every offset below is pinned there by const asserts)
 *   0x000 header: "BANNERIN" tag (u64), major/minor, sizes, slot, instance id (u64), retired flag,
 *         writer pid, creation time; 0x40 bell, 0x44 published index
 *   0x100 4 state copies x 128 bytes: a sequence word, then 31 data words (W_* below)
 *   the file is 4096 bytes and never grows or shrinks while in use
 *
 * PROTOCOL (core/src/proto.rs): single writer, lock-free readers. Publish n goes into copy n % 4,
 * whose sequence word is odd while the copy is written; then `published` becomes n. A reader loads
 * `published`, then that copy's sequence word (must be even), the data words, and the sequence
 * word again; it accepts the copy only if the sequence word did not change AND data word 0 (the
 * publish number) equals the `published` value it started from. The retry count is bounded.
 *
 * ORDERING: every shared word is read with an acquire load, and two explicit full fences bracket
 * the data words: one after the first sequence load, one before the second. On arm64/arm64ec they
 * are `dmb ish`; on x86 `mfence` (i386 is built with -msse2 so it is never the `lock or` form).
 * Natively x86 needs neither (TSO), but this DLL also runs as x86 code under box64/FEX, which do
 * not always emulate TSO for plain loads and do turn a fence into a real barrier. 64-bit header
 * fields are read as two 32-bit loads: a 64-bit atomic load on i386 may be a locked cmpxchg8b,
 * which writes and would fault on the read-only mapping.
 */
#ifndef BXI_CORE_H
#define BXI_CORE_H

#include <stddef.h>
#include <stdint.h>

/* layout (format 1) */
#define BXI_MAGIC_LO 0x4e4e4142u /* "BANN" */
#define BXI_MAGIC_HI 0x4e495245u /* "ERIN" */
#define BXI_FORMAT_MAJOR 1u
#define BXI_FILE_SIZE 4096u
#define BXI_HEADER_SIZE 256u
#define BXI_COPY_SIZE 128u
#define BXI_COPY_COUNT 4u
#define BXI_COPY_WORDS 31u
#define BXI_JOURNAL_LEN 256u

#define BXI_H_MAGIC 0u
#define BXI_H_MAJOR 8u
#define BXI_H_MINOR 12u
#define BXI_H_FILE_SIZE 16u
#define BXI_H_HEADER_SIZE 20u
#define BXI_H_COPY_SIZE 24u
#define BXI_H_COPY_COUNT 28u
#define BXI_H_JOURNAL_LEN 32u
#define BXI_H_SLOT 36u
#define BXI_H_INSTANCE 40u
#define BXI_H_RETIRED 48u
#define BXI_H_WRITER_PID 52u
#define BXI_H_PUBLISHED 68u
#define BXI_COPIES_OFF 256u

/* data words of one state copy */
#define BXI_W_PUB 0u
#define BXI_W_FLAGS 1u
#define BXI_W_UNPLUG 2u
#define BXI_W_EDGES 3u
#define BXI_W_COUNTS 4u
#define BXI_BUTTONS 11u
#define BXI_W_AXES 15u
#define BXI_AXES 8u
#define BXI_FLAG_CONNECTED 1u

/* canonical button index (core/src/abi.rs: ascending evdev code order) */
enum { BXI_B_A, BXI_B_B, BXI_B_X, BXI_B_Y, BXI_B_TL, BXI_B_TR, BXI_B_SELECT, BXI_B_START, BXI_B_MODE,
       BXI_B_THUMBL, BXI_B_THUMBR };
/* canonical axis index: X, Y, RX, RY, BRAKE (left trigger), GAS (right trigger), HAT0X, HAT0Y */
enum { BXI_A_LX, BXI_A_LY, BXI_A_RX, BXI_A_RY, BXI_A_LT, BXI_A_RT, BXI_A_HATX, BXI_A_HATY };

/* XINPUT_GAMEPAD wButtons bits (public XInput API values) */
#define BXI_XI_DPAD_UP 0x0001u
#define BXI_XI_DPAD_DOWN 0x0002u
#define BXI_XI_DPAD_LEFT 0x0004u
#define BXI_XI_DPAD_RIGHT 0x0008u
#define BXI_XI_START 0x0010u
#define BXI_XI_BACK 0x0020u
#define BXI_XI_LEFT_THUMB 0x0040u
#define BXI_XI_RIGHT_THUMB 0x0080u
#define BXI_XI_LEFT_SHOULDER 0x0100u
#define BXI_XI_RIGHT_SHOULDER 0x0200u
#define BXI_XI_GUIDE 0x0400u
#define BXI_XI_A 0x1000u
#define BXI_XI_B 0x2000u
#define BXI_XI_X 0x4000u
#define BXI_XI_Y 0x8000u

/* canonical button index -> XInput bit */
static const uint16_t BXI_BUTTON_BITS[BXI_BUTTONS] = {
    BXI_XI_A, BXI_XI_B, BXI_XI_X, BXI_XI_Y, BXI_XI_LEFT_SHOULDER, BXI_XI_RIGHT_SHOULDER,
    BXI_XI_BACK, BXI_XI_START, BXI_XI_GUIDE, BXI_XI_LEFT_THUMB, BXI_XI_RIGHT_THUMB,
};

/* One XInput gamepad report, in XINPUT_GAMEPAD field order. */
typedef struct bxi_pad {
    uint16_t buttons;
    uint8_t lt, rt;
    int16_t lx, ly, rx, ry;
} bxi_pad;

static inline const uint32_t *bxi_word(const uint8_t *base, uint32_t off) {
    return (const uint32_t *)(const void *)(base + off);
}

static inline uint32_t bxi_ld(const uint8_t *base, uint32_t off) {
    return __atomic_load_n(bxi_word(base, off), __ATOMIC_ACQUIRE);
}

static inline void bxi_fence(void) {
    __atomic_thread_fence(__ATOMIC_SEQ_CST);
}

static inline void bxi_pause(void) {
#if defined(__aarch64__) || defined(__arm64ec__) || defined(_M_ARM64EC) || defined(__arm__)
    __asm__ __volatile__("yield" ::: "memory");
#elif defined(__i386__) || defined(__x86_64__)
    __asm__ __volatile__("pause" ::: "memory");
#else
    __atomic_signal_fence(__ATOMIC_SEQ_CST);
#endif
}

/* 1 if `base` (a mapping of at least BXI_FILE_SIZE bytes) is a format-1 slot file for `slot`. The
 * tag is read first: the writer stores it last, with release, after the rest of the header. */
static inline int bxi_valid(const uint8_t *base, uint32_t slot) {
    if (bxi_ld(base, BXI_H_MAGIC) != BXI_MAGIC_LO || bxi_ld(base, BXI_H_MAGIC + 4) != BXI_MAGIC_HI)
        return 0;
    bxi_fence();
    return bxi_ld(base, BXI_H_MAJOR) == BXI_FORMAT_MAJOR
        && bxi_ld(base, BXI_H_HEADER_SIZE) == BXI_HEADER_SIZE
        && bxi_ld(base, BXI_H_COPY_SIZE) == BXI_COPY_SIZE
        && bxi_ld(base, BXI_H_COPY_COUNT) == BXI_COPY_COUNT
        && bxi_ld(base, BXI_H_JOURNAL_LEN) == BXI_JOURNAL_LEN
        && bxi_ld(base, BXI_H_FILE_SIZE) >= BXI_FILE_SIZE
        && bxi_ld(base, BXI_H_SLOT) == slot;
}

static inline int bxi_retired(const uint8_t *base) {
    return bxi_ld(base, BXI_H_RETIRED) != 0;
}

/* The file's instance id (never 0 for a live file; constant for the file's lifetime). */
static inline uint64_t bxi_instance(const uint8_t *base) {
    return (uint64_t)bxi_ld(base, BXI_H_INSTANCE) | ((uint64_t)bxi_ld(base, BXI_H_INSTANCE + 4) << 32);
}

/* A consistent copy of the latest published state into out[]. Returns 1, or 0 if every one of
 * `attempts` reads was overtaken by the writer lapping the ring (the caller keeps its last state). */
static inline int bxi_snapshot(const uint8_t *base, uint32_t out[BXI_COPY_WORDS], unsigned attempts) {
    for (unsigned i = 0; i < attempts; i++) {
        uint32_t n = bxi_ld(base, BXI_H_PUBLISHED);
        uint32_t c = BXI_COPIES_OFF + (n % BXI_COPY_COUNT) * BXI_COPY_SIZE;
        uint32_t s1 = bxi_ld(base, c);
        if (s1 & 1u) {
            bxi_pause();
            continue;
        }
        bxi_fence();
        for (uint32_t k = 0; k < BXI_COPY_WORDS; k++)
            out[k] = bxi_ld(base, c + 4u + 4u * k);
        bxi_fence();
        if (bxi_ld(base, c) == s1 && out[BXI_W_PUB] == n)
            return 1;
        bxi_pause();
    }
    return 0;
}

/* Pressed-button mask (bit = canonical index) from the per-button edge counters: every button
 * starts released, so an odd count means pressed. */
static inline uint16_t bxi_pressed(const uint32_t w[BXI_COPY_WORDS]) {
    uint16_t m = 0;
    for (uint32_t b = 0; b < BXI_BUTTONS; b++)
        m |= (uint16_t)((w[BXI_W_COUNTS + b] & 1u) << b);
    return m;
}

/* Buttons pressed AND released again since `seen` (the counters a previous read saw), i.e. taps a
 * poll would otherwise miss: released then and now, but at least two edges in between. */
static inline uint16_t bxi_missed_taps(const uint32_t w[BXI_COPY_WORDS], const uint32_t seen[BXI_BUTTONS]) {
    uint16_t m = 0;
    for (uint32_t b = 0; b < BXI_BUTTONS; b++) {
        uint32_t now = w[BXI_W_COUNTS + b];
        if ((now & 1u) == 0 && (seen[b] & 1u) == 0 && (uint32_t)(now - seen[b]) >= 2u)
            m |= (uint16_t)(1u << b);
    }
    return m;
}

static inline int32_t bxi_axis(const uint32_t w[BXI_COPY_WORDS], uint32_t a) {
    return (int32_t)w[BXI_W_AXES + a];
}

static inline int16_t bxi_clamp16(int32_t v) {
    return (int16_t)(v < -32768 ? -32768 : v > 32767 ? 32767 : v);
}

static inline uint8_t bxi_clamp8(int32_t v) {
    return (uint8_t)(v < 0 ? 0 : v > 255 ? 255 : v);
}

/* XInput Y: up is positive. evdev Y (what BANNERIN carries): down is positive. -(-32768) = 32768
 * does not fit a SHORT and clamps to 32767. */
static inline int16_t bxi_stick_y(int32_t v) {
    return bxi_clamp16(v == INT32_MIN ? INT32_MAX : -v);
}

/* The XInput report for a snapshot. `buttons` is the canonical pressed mask to show (pressed, plus
 * any missed taps the caller wants to surface); `guide` = 0 hides the Guide button (XInputGetState),
 * 1 reports it (XInputGetStateEx). */
static inline void bxi_map(const uint32_t w[BXI_COPY_WORDS], uint16_t buttons, int guide, bxi_pad *out) {
    uint16_t xb = 0;
    for (uint32_t b = 0; b < BXI_BUTTONS; b++)
        if (buttons & (1u << b))
            xb |= BXI_BUTTON_BITS[b];
    int32_t hx = bxi_axis(w, BXI_A_HATX), hy = bxi_axis(w, BXI_A_HATY);
    if (hx < 0) xb |= BXI_XI_DPAD_LEFT;
    if (hx > 0) xb |= BXI_XI_DPAD_RIGHT;
    if (hy < 0) xb |= BXI_XI_DPAD_UP;
    if (hy > 0) xb |= BXI_XI_DPAD_DOWN;
    if (!guide) xb &= (uint16_t)~BXI_XI_GUIDE;
    out->buttons = xb;
    out->lt = bxi_clamp8(bxi_axis(w, BXI_A_LT));
    out->rt = bxi_clamp8(bxi_axis(w, BXI_A_RT));
    out->lx = bxi_clamp16(bxi_axis(w, BXI_A_LX));
    out->ly = bxi_stick_y(bxi_axis(w, BXI_A_LY));
    out->rx = bxi_clamp16(bxi_axis(w, BXI_A_RX));
    out->ry = bxi_stick_y(bxi_axis(w, BXI_A_RY));
}

/* CONTROL BLOCK (format minor 5, 0x0B00..0x0BFF; written by the app, read-only here). All u32 LE.
 * Seqlock: the writer makes ctl_seq odd, writes the fields, bumps ctl_gen, makes ctl_seq even again
 * (release). A reader checks ctl_gen with one acquire load per call and re-reads the block only when
 * it changed: ctl_seq even, fence, the fields, fence, ctl_seq unchanged, at most `attempts` times;
 * when every attempt is overtaken it keeps its last good copy. Used only if the header's minor is at
 * least 5, the magic is "BCTL" and the size at least 256; otherwise the DLL behaves as before it. */
#define BXI_CTL_MIN_MINOR 5u
#define BXI_CTL_OFF 0xb00u
#define BXI_CTL_MAGIC 0x4c544342u /* "BCTL" */
#define BXI_CTL_SIZE_MIN 256u
#define BXI_C_MAGIC 0x00u
#define BXI_C_SIZE 0x04u
#define BXI_C_SEQ 0x08u
#define BXI_C_GEN 0x0cu
#define BXI_C_AXIS_MODE 0x10u
#define BXI_C_AXIS_HZ 0x14u
#define BXI_C_AXIS_AUTO_MAX 0x18u
#define BXI_C_XI_MODE 0x1cu
#define BXI_C_XI_FLAGS 0x20u
#define BXI_C_ID_FLAGS 0x40u
#define BXI_C_ID_BUS 0x44u
#define BXI_C_ID_VENDOR 0x48u
#define BXI_C_ID_PRODUCT 0x4cu
#define BXI_C_ID_VERSION 0x50u

#define BXI_XI_MODE_UNSET 0u   /* = direct */
#define BXI_XI_MODE_DIRECT 1u
#define BXI_XI_MODE_FORWARD 2u
#define BXI_XIF_FORWARD_AVAILABLE 0x1u
#define BXI_XIF_HIDDEN 0x2u
#define BXI_IDF_VALID 0x1u

typedef struct bxi_ctl {
    uint32_t gen;
    uint32_t xi_mode, xi_flags;
    uint32_t id_flags, id_bus, id_vendor, id_product, id_version;
} bxi_ctl;

/* 1 if the file carries a usable control block. */
static inline int bxi_ctl_present(const uint8_t *base) {
    return bxi_ld(base, BXI_H_MINOR) >= BXI_CTL_MIN_MINOR
        && bxi_ld(base, BXI_CTL_OFF + BXI_C_MAGIC) == BXI_CTL_MAGIC
        && bxi_ld(base, BXI_CTL_OFF + BXI_C_SIZE) >= BXI_CTL_SIZE_MIN;
}

/* The change counter: one acquire load, the per-call check. */
static inline uint32_t bxi_ctl_gen(const uint8_t *base) {
    return bxi_ld(base, BXI_CTL_OFF + BXI_C_GEN);
}

/* A consistent copy of the block into *out. Returns 1, or 0 if every attempt was overtaken by the
 * writer (the caller keeps its last good copy). Never blocks, never writes. */
static inline int bxi_ctl_read(const uint8_t *base, bxi_ctl *out, unsigned attempts) {
    for (unsigned i = 0; i < attempts; i++) {
        uint32_t s1 = bxi_ld(base, BXI_CTL_OFF + BXI_C_SEQ);
        if (s1 & 1u) {
            bxi_pause();
            continue;
        }
        bxi_fence();
        bxi_ctl c;
        c.gen = bxi_ld(base, BXI_CTL_OFF + BXI_C_GEN);
        c.xi_mode = bxi_ld(base, BXI_CTL_OFF + BXI_C_XI_MODE);
        c.xi_flags = bxi_ld(base, BXI_CTL_OFF + BXI_C_XI_FLAGS);
        c.id_flags = bxi_ld(base, BXI_CTL_OFF + BXI_C_ID_FLAGS);
        c.id_bus = bxi_ld(base, BXI_CTL_OFF + BXI_C_ID_BUS);
        c.id_vendor = bxi_ld(base, BXI_CTL_OFF + BXI_C_ID_VENDOR);
        c.id_product = bxi_ld(base, BXI_CTL_OFF + BXI_C_ID_PRODUCT);
        c.id_version = bxi_ld(base, BXI_CTL_OFF + BXI_C_ID_VERSION);
        bxi_fence();
        if (bxi_ld(base, BXI_CTL_OFF + BXI_C_SEQ) == s1) {
            *out = c;
            return 1;
        }
        bxi_pause();
    }
    return 0;
}

/* "<slot>=<path>[;<slot>=<path>...]" (FAKE_EVDEV_MEMFD_PATHS, the grammar of core/src/guest.rs:
 * blanks around keys and paths are trimmed, malformed entries skipped, the first entry for a slot
 * wins). Calls fn(ctx, slot, path, len) for each accepted entry; returns the mask of slots. Generic
 * over the character type through the macro so the DLL can parse the WCHAR environment. */
#define BXI_DEFINE_PARSE_PATHS(name, CH)                                                         \
    static inline uint32_t name(const CH *s, void (*fn)(void *, uint32_t, const CH *, size_t),     \
                                void *ctx) {                                                       \
        uint32_t mask = 0;                                                                         \
        while (s && *s) {                                                                          \
            const CH *e = s;                                                                       \
            while (*e && *e != ';') e++;                                                           \
            const CH *eq = s;                                                                      \
            while (eq < e && *eq != '=') eq++;                                                     \
            if (eq < e) {                                                                          \
                const CH *k0 = s, *k1 = eq, *p0 = eq + 1, *p1 = e;                                 \
                while (k0 < k1 && (*k0 == ' ' || *k0 == '\t' || *k0 == '\r' || *k0 == '\n')) k0++; \
                while (k1 > k0 && (k1[-1] == ' ' || k1[-1] == '\t' || k1[-1] == '\r' || k1[-1] == '\n')) k1--; \
                while (p0 < p1 && (*p0 == ' ' || *p0 == '\t' || *p0 == '\r' || *p0 == '\n')) p0++; \
                while (p1 > p0 && (p1[-1] == ' ' || p1[-1] == '\t' || p1[-1] == '\r' || p1[-1] == '\n')) p1--; \
                if (k1 - k0 == 1 && *k0 >= '0' && *k0 <= '3' && p1 > p0) {                         \
                    uint32_t slot = (uint32_t)(*k0 - '0');                                         \
                    if (!(mask & (1u << slot))) {                                                  \
                        mask |= 1u << slot;                                                        \
                        fn(ctx, slot, p0, (size_t)(p1 - p0));                                      \
                    }                                                                              \
                }                                                                                  \
            }                                                                                      \
            s = *e ? e + 1 : e;                                                                    \
        }                                                                                          \
        return mask;                                                                               \
    }

#endif /* BXI_CORE_H */
