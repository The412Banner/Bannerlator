// Ported from DroidDeck (github.com/Droid-Deck/DroidDeck, PRs #1969 and #1981).
// GPU clock without root on Adreno (KGSL) devices whose kgsl sysfs is blocked for apps.
//
// kgsl lets any app reserve a performance counter (IOCTL_KGSL_PERFCOUNTER_GET) but refuses to read it back while
// /sys/class/kgsl/kgsl-3d0/perfcounter is 0, and clears the counters whenever the GPU switches to another process.
// So the counter is read by the GPU itself: a small command buffer copies CP_ALWAYS_ON_COUNTER (19.2 MHz) and the
// RBBM ALWAYS_COUNT counter (GPU core cycles) to memory, runs a block of NOP packets, and copies both again.
// clock = cycles / always-on ticks * 19.2 MHz. Both reads sit in one command buffer, so a counter clear between
// them only happens if the buffer is preempted; such samples fail the range checks and are dropped.
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

#include "../adrenotools/src/hook/kgsl.h"
#include "kgsl_layout.h"

#define CP_TYPE7_PKT 0x70000000u
#define CP_NOP 0x10u
#define CP_REG_TO_MEM 0x3eu
#define XO_HZ 19200000.0
#define NOP_DWORDS 0x3fffu
#define NOP_BLOCKS 4

struct sampler {
    int fd;
    const struct kgsl_layout *layout;
    unsigned context;
    unsigned counter_reg;
    struct kgsl_gpumem_alloc_id ib, out;
    uint32_t *ib_map;
    volatile uint64_t *out_map;
    size_t ib_bytes;
};

static unsigned odd_parity(unsigned v) {
    v ^= v >> 16;
    v ^= v >> 8;
    v ^= v >> 4;
    v &= 0xf;
    return (~0x6996u >> v) & 1u;
}

static uint32_t pkt7(unsigned op, unsigned count) {
    return CP_TYPE7_PKT | count | odd_parity(count) << 15 | (op & 0x7f) << 16 | odd_parity(op) << 23;
}

static void sampler_close(struct sampler *s) {
    if (!s) return;
    if (s->fd >= 0) {
        if (s->ib_map && s->ib_map != MAP_FAILED) munmap(s->ib_map, s->ib.mmapsize);
        if (s->out_map && s->out_map != MAP_FAILED) munmap((void *)s->out_map, s->out.mmapsize);
        if (s->ib.id) { struct kgsl_gpumem_free_id f = {.id = s->ib.id}; ioctl(s->fd, IOCTL_KGSL_GPUMEM_FREE_ID, &f); }
        if (s->out.id) { struct kgsl_gpumem_free_id f = {.id = s->out.id}; ioctl(s->fd, IOCTL_KGSL_GPUMEM_FREE_ID, &f); }
        if (s->context) { struct kgsl_drawctxt_destroy d = {.drawctxt_id = s->context}; ioctl(s->fd, IOCTL_KGSL_DRAWCTXT_DESTROY, &d); }
        if (s->counter_reg) {
            struct kgsl_perfcounter_put p = {.groupid = KGSL_PERFCOUNTER_GROUP_RBBM, .countable = s->layout->cycles};
            ioctl(s->fd, IOCTL_KGSL_PERFCOUNTER_PUT, &p);
        }
        close(s->fd);
    }
    free(s);
}

static void emit_reads(struct sampler *s, size_t *n, unsigned pass) {
    const unsigned regs[2] = {s->layout->always_on, s->counter_reg};
    for (unsigned k = 0; k < 2; k++) {
        uint64_t dst = s->out.gpuaddr + (uint64_t)(pass * 2 + k) * sizeof(uint64_t);
        s->ib_map[(*n)++] = pkt7(CP_REG_TO_MEM, 3);
        s->ib_map[(*n)++] = regs[k] | (2u << 18) | (1u << 30);  // two dwords, 64-bit read
        s->ib_map[(*n)++] = (uint32_t)dst;
        s->ib_map[(*n)++] = (uint32_t)(dst >> 32);
    }
}

// Returns 0 or a negative errno; *out is set on success.
static int sampler_open(struct sampler **out) {
    struct sampler *s = calloc(1, sizeof(*s));
    if (!s) return -ENOMEM;
    s->fd = open("/dev/kgsl-3d0", O_RDWR | O_CLOEXEC);
    if (s->fd < 0) { int e = -errno; s->fd = -1; sampler_close(s); return e; }
    struct kgsl_devinfo info;
    memset(&info, 0, sizeof(info));
    struct kgsl_device_getproperty prop = {.type = KGSL_PROP_DEVICE_INFO, .value = &info, .sizebytes = sizeof(info)};
    if (ioctl(s->fd, IOCTL_KGSL_DEVICE_GETPROPERTY, &prop)) { int e = -errno; sampler_close(s); return e; }
    s->layout = kgsl_layout_for(info.chip_id);
    if (!s->layout) { sampler_close(s); return -ENOTSUP; }
    struct kgsl_perfcounter_get get = {.groupid = KGSL_PERFCOUNTER_GROUP_RBBM, .countable = s->layout->cycles};
    if (ioctl(s->fd, IOCTL_KGSL_PERFCOUNTER_GET, &get)) { int e = -errno; sampler_close(s); return e; }
    s->counter_reg = get.offset;
    if (!get.offset || get.offset > s->layout->readable_last) { sampler_close(s); return -ERANGE; }
    struct kgsl_drawctxt_create ctx = {.flags = KGSL_CONTEXT_NO_GMEM_ALLOC | KGSL_CONTEXT_PREAMBLE};
    if (ioctl(s->fd, IOCTL_KGSL_DRAWCTXT_CREATE, &ctx)) { int e = -errno; sampler_close(s); return e; }
    s->context = ctx.drawctxt_id;
    s->ib_bytes = (16 + NOP_BLOCKS * (NOP_DWORDS + 1)) * sizeof(uint32_t);
    s->ib.size = (s->ib_bytes + 4095) & ~(size_t)4095;
    s->out.size = 4096;
    if (ioctl(s->fd, IOCTL_KGSL_GPUMEM_ALLOC_ID, &s->ib)) { int e = -errno; s->ib.id = 0; sampler_close(s); return e; }
    if (ioctl(s->fd, IOCTL_KGSL_GPUMEM_ALLOC_ID, &s->out)) { int e = -errno; s->out.id = 0; sampler_close(s); return e; }
    s->ib_map = mmap(NULL, s->ib.mmapsize, PROT_READ | PROT_WRITE, MAP_SHARED, s->fd, (off_t)s->ib.id << 12);
    s->out_map = mmap(NULL, s->out.mmapsize, PROT_READ | PROT_WRITE, MAP_SHARED, s->fd, (off_t)s->out.id << 12);
    if (s->ib_map == MAP_FAILED || s->out_map == MAP_FAILED) { int e = -errno; sampler_close(s); return e; }
    size_t n = 0;
    emit_reads(s, &n, 0);
    for (unsigned b = 0; b < NOP_BLOCKS; b++) {
        s->ib_map[n++] = pkt7(CP_NOP, NOP_DWORDS);
        memset(&s->ib_map[n], 0, NOP_DWORDS * sizeof(uint32_t));
        n += NOP_DWORDS;
    }
    emit_reads(s, &n, 1);
    s->ib_bytes = n * sizeof(uint32_t);
    *out = s;
    return 0;
}

// MHz, or a negative value: -errno from kgsl, or -1000 for a sample that failed the range checks.
static int sampler_mhz(struct sampler *s) {
    for (int i = 0; i < 4; i++) s->out_map[i] = 0;
    struct kgsl_command_object cmd = {.gpuaddr = s->ib.gpuaddr, .size = s->ib_bytes, .flags = KGSL_CMDLIST_IB};
    struct kgsl_gpu_command submit = {
        .cmdlist = (uint64_t)(uintptr_t)&cmd, .cmdsize = sizeof(cmd), .numcmds = 1, .context_id = s->context,
    };
    if (ioctl(s->fd, IOCTL_KGSL_GPU_COMMAND, &submit)) return -errno;
    struct kgsl_device_waittimestamp_ctxtid wait = {.context_id = s->context, .timestamp = submit.timestamp, .timeout = 250};
    if (ioctl(s->fd, IOCTL_KGSL_DEVICE_WAITTIMESTAMP_CTXTID, &wait)) return -errno;
    uint64_t xo0 = s->out_map[0], cyc0 = s->out_map[1], xo1 = s->out_map[2], cyc1 = s->out_map[3];
    if (xo1 <= xo0 || cyc1 <= cyc0) return -1000;
    uint64_t xo = xo1 - xo0, cycles = cyc1 - cyc0;
    // 5 us .. 50 ms of always-on time, and a clock between 50 MHz and 2 GHz.
    if (xo < 96 || xo > 960000) return -1000;
    double mhz = (double)cycles / (double)xo * XO_HZ / 1e6;
    if (mhz < 50 || mhz > 2000) return -1000;
    return (int)(mhz + 0.5);
}

#ifndef KGSL_CLOCK_NO_JNI
#include <jni.h>

JNIEXPORT jlong JNICALL Java_com_winlator_star_widget_gpu_KgslClock_nativeOpen(JNIEnv *env, jclass cls, jintArray error) {
    struct sampler *s = NULL;
    int r = sampler_open(&s);
    if (r && error && (*env)->GetArrayLength(env, error) > 0) {
        jint e = r;
        (*env)->SetIntArrayRegion(env, error, 0, 1, &e);
    }
    return r ? 0 : (jlong)(intptr_t)s;
}

JNIEXPORT jint JNICALL Java_com_winlator_star_widget_gpu_KgslClock_nativeSample(JNIEnv *env, jclass cls, jlong handle) {
    return handle ? sampler_mhz((struct sampler *)(intptr_t)handle) : -EBADF;
}

JNIEXPORT void JNICALL Java_com_winlator_star_widget_gpu_KgslClock_nativeClose(JNIEnv *env, jclass cls, jlong handle) {
    sampler_close((struct sampler *)(intptr_t)handle);
}
#endif
