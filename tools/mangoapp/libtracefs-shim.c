// libtracefs.so.1 for mangoapp: the GPU memory Valve's overlay reads, without the kernel's tracing.
//
// Valve's mangoapp (msm.cpp, ftrace_gpumem.c) takes the GPU's memory in use - the VRAM and PVRAM
// lines - from the gpu_mem_total tracepoint (pid 0, the device's total) through libtracefs. An
// Android app may not use tracefs at all: the instance could not be made, and both lines read 0.
// This stands in for the libtracefs calls mangoapp makes, and hands its record walker one
// gpu_mem_total record carrying what the app writes to /run/bannerlator-hud/gpu-mem (bytes; the
// session's LinuxGpuMemComponent, from KGSL's own accounting). Only mangoapp loads this copy.
//
// The walker reads libtraceevent's structures directly, so the ones below are libtraceevent
// 1.8.2's (event-parse.h), the version tools/mangoapp/packages.txt pins; tep_find_event_by_record
// and tep_read_number are answered here too, ahead of the real libtraceevent in mangoapp's lookup.
//
// The session preload's tracefs stand-in (tools/linuxfs/preload/tracefs.c) still wraps these calls,
// but this create never fails and this directory is not its own, so it passes every one through.
// (From Droid-Deck/DroidDeck #127.)

#include <sched.h>
#include <stddef.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define EXPORT __attribute__((visibility("default")))

// Must match LinuxGpuMemComponent.GUEST_FILE.
#define HUD_DIR "/run/bannerlator-hud"
#define GPU_MEM_FILE HUD_DIR "/gpu-mem"

struct tep_handle;
struct tep_event;
struct tep_print_arg;
struct tep_print_parse;

struct tep_format_field {
    struct tep_format_field *next;
    struct tep_event *event;
    char *type;
    char *name;
    char *alias;
    int offset;
    int size;
    unsigned int arraylen;
    unsigned int elementsize;
    unsigned long flags;
};

struct tep_format {
    int nr_common;
    int nr_fields;
    struct tep_format_field *common_fields;
    struct tep_format_field *fields;
};

struct tep_print_fmt {
    char *format;
    struct tep_print_arg *args;
    struct tep_print_parse *print_cache;
};

struct tep_event {
    struct tep_handle *tep;
    char *name;
    int id;
    int flags;
    struct tep_format format;
    struct tep_print_fmt print_fmt;
    char *system;
    void *handler;
    void *context;
};

struct tep_record {
    unsigned long long ts;
    unsigned long long offset;
    long long missed_events;
    int record_size;
    int size;
    void *data;
    int cpu;
    int ref_count;
    int locked;
    void *priv;
};

// gpu_mem_total's record: gpu_id, pid, size - as the kernel lays it out after the common fields.
struct gpu_mem_total {
    unsigned int gpu_id;
    unsigned int pid;
    unsigned long long size;
};

static struct tep_format_field size_field = {
    .name = "size", .offset = offsetof(struct gpu_mem_total, size), .size = 8};
static struct tep_format_field pid_field = {
    .next = &size_field, .name = "pid", .offset = offsetof(struct gpu_mem_total, pid), .size = 4};
static struct tep_event gpu_mem_event = {
    .name = "gpu_mem_total", .system = "gpu_mem", .format = {.nr_fields = 2, .fields = &pid_field}};

// Never dereferenced by mangoapp: they only have to be non-null.
static char handle;
static char instance;

static unsigned long long gpu_mem_bytes(void) {
    unsigned long long bytes = 0;
    FILE *f = fopen(GPU_MEM_FILE, "r");
    if (f) {
        if (fscanf(f, "%llu", &bytes) != 1) bytes = 0;
        fclose(f);
    }
    return bytes;
}

EXPORT void *tracefs_instance_create(const char *name) { (void)name; return &instance; }
EXPORT char *tracefs_instance_get_dir(void *inst) { (void)inst; return strdup(HUD_DIR); }
EXPORT void tracefs_put_tracing_file(char *name) { free(name); }
EXPORT struct tep_handle *tracefs_local_events(const char *dir) { (void)dir; return (struct tep_handle *)&handle; }
EXPORT int tracefs_event_enable(void *inst, const char *system, const char *event) { (void)inst; (void)system; (void)event; return 0; }
EXPORT int tracefs_event_disable(void *inst, const char *system, const char *event) { (void)inst; (void)system; (void)event; return 0; }
EXPORT bool tracefs_instance_is_new(void *inst) { (void)inst; return false; }
EXPORT int tracefs_instance_destroy(void *inst) { (void)inst; return 0; }
EXPORT void tracefs_instance_free(void *inst) { (void)inst; }

EXPORT int tracefs_iterate_raw_events(struct tep_handle *tep, void *inst, cpu_set_t *cpus, int cpu_size,
                                      int (*callback)(struct tep_event *, struct tep_record *, int, void *),
                                      void *context) {
    (void)tep; (void)inst; (void)cpus; (void)cpu_size;
    if (!callback) return -1;
    struct gpu_mem_total data = {.gpu_id = 0, .pid = 0, .size = gpu_mem_bytes()};
    struct tep_record record = {.size = sizeof(data), .record_size = sizeof(data), .data = &data};
    callback(&gpu_mem_event, &record, 0, context);
    return 0;
}

EXPORT struct tep_event *tep_find_event_by_record(struct tep_handle *tep, struct tep_record *record) {
    (void)tep; (void)record;
    return &gpu_mem_event;
}

EXPORT unsigned long long tep_read_number(struct tep_handle *tep, const void *ptr, int size) {
    (void)tep;
    switch (size) {
    case 1: return *(const unsigned char *)ptr;
    case 2: return *(const unsigned short *)ptr;
    case 4: return *(const unsigned int *)ptr;
    case 8: return *(const unsigned long long *)ptr;
    default: return 0;
    }
}

EXPORT void tep_free(struct tep_handle *tep) { (void)tep; }
