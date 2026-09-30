/*
 * A trace instance for mangoapp where the kernel's tracefs is closed to the app.
 *
 * Valve's mangoapp (Deck mode's performance overlay) reads the GPU memory total on a Qualcomm display driver (msm_dpu / msm_drm) from the gpu_mem_total event, through a tracefs instance of its own.
 * It aborts when tracefs_instance_create() fails, and takes the whole overlay with it.
 * Android mounts tracefs for root and the readtracefs group only, so from the app it always fails.
 *
 * The real calls run first.
 * Only when they fail is the instance placed in an empty directory of the session's own.
 * Reading its events then answers with one gpu_mem_total record carrying the total that KGSL, the GPU driver of every Adreno under Android, reports to the app.
 * (From Droid-Deck/DroidDeck #55.)
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>

#define STANDIN_DIR "/tmp/bannerlator-tracefs"
#define KGSL_TOTAL "/sys/class/kgsl/kgsl/page_alloc"

struct tracefs_instance;
struct tep_handle;
struct tep_event;

/* The head of libtraceevent 1.8's struct tep_record (the version tools/mangoapp/packages.txt pins). */
struct tep_record {
  unsigned long long ts, offset;
  long long missed_events;
  int record_size, size;
  void *data;
  int cpu, ref_count, locked;
  void *priv;
};

/* The event as Android kernels declare it (trace/events/gpu_mem.h). */
static const char gpu_mem_total_format[] =
    "name: gpu_mem_total\n"
    "ID: 1\n"
    "format:\n"
    "\tfield:unsigned short common_type;\toffset:0;\tsize:2;\tsigned:0;\n"
    "\tfield:unsigned char common_flags;\toffset:2;\tsize:1;\tsigned:0;\n"
    "\tfield:unsigned char common_preempt_count;\toffset:3;\tsize:1;\tsigned:0;\n"
    "\tfield:int common_pid;\toffset:4;\tsize:4;\tsigned:1;\n"
    "\n"
    "\tfield:uint32_t gpu_id;\toffset:8;\tsize:4;\tsigned:0;\n"
    "\tfield:uint32_t pid;\toffset:12;\tsize:4;\tsigned:0;\n"
    "\tfield:uint64_t size;\toffset:16;\tsize:8;\tsigned:0;\n"
    "\n"
    "print fmt: \"gpu_id=%u pid=%u size=%llu\", REC->gpu_id, REC->pid, REC->size\n";

static struct tracefs_instance *standin;

struct tracefs_instance *tracefs_instance_create(const char *name) {
  struct tracefs_instance *(*real)(const char *) = dlsym(RTLD_NEXT, "tracefs_instance_create");
  struct tracefs_instance *instance = real ? real(name) : NULL;
  if (instance || !name) return instance;
  struct tracefs_instance *(*alloc)(const char *, const char *) = dlsym(RTLD_NEXT, "tracefs_instance_alloc");
  if (!alloc) return NULL;
  char path[PATH_MAX];
  mkdir(STANDIN_DIR, 0700);
  mkdir(STANDIN_DIR "/instances", 0700);
  snprintf(path, sizeof(path), STANDIN_DIR "/instances/%s", name);
  mkdir(path, 0700);
  return standin = alloc(STANDIN_DIR, name);
}

struct tep_handle *tracefs_local_events(const char *tracing_dir) {
  if (!tracing_dir || strncmp(tracing_dir, STANDIN_DIR "/", sizeof(STANDIN_DIR))) {
    struct tep_handle *(*real)(const char *) = dlsym(RTLD_NEXT, "tracefs_local_events");
    return real ? real(tracing_dir) : NULL;
  }
  struct tep_handle *(*tep_alloc)(void) = dlsym(RTLD_DEFAULT, "tep_alloc");
  int (*parse)(struct tep_handle *, const char *, unsigned long, const char *) = dlsym(RTLD_DEFAULT, "tep_parse_event");
  struct tep_handle *tep = tep_alloc ? tep_alloc() : NULL;
  if (tep && parse) parse(tep, gpu_mem_total_format, sizeof(gpu_mem_total_format) - 1, "gpu_mem");
  return tep;
}

int tracefs_iterate_raw_events(struct tep_handle *tep, struct tracefs_instance *instance, void *cpus, int cpu_size,
                               int (*callback)(struct tep_event *, struct tep_record *, int, void *), void *context) {
  if (!standin || instance != standin) {
    int (*real)(struct tep_handle *, struct tracefs_instance *, void *, int,
                int (*)(struct tep_event *, struct tep_record *, int, void *), void *) =
        dlsym(RTLD_NEXT, "tracefs_iterate_raw_events");
    return real ? real(tep, instance, cpus, cpu_size, callback, context) : -1;
  }
  struct tep_event *(*find)(struct tep_handle *, int) = dlsym(RTLD_DEFAULT, "tep_find_event");
  struct tep_event *event = find && tep ? find(tep, 1) : NULL;
  unsigned long long total;
  FILE *f = fopen(KGSL_TOTAL, "r");
  int have = f && fscanf(f, "%llu", &total) == 1;
  if (f) fclose(f);
  if (!event || !have || !callback) return -1;
  struct { uint16_t type; uint8_t flags, preempt; int32_t common_pid; uint32_t gpu_id, pid; uint64_t size; } data = {
      .type = 1, .size = total};
  struct tep_record record = {.record_size = sizeof(data), .size = sizeof(data), .data = &data};
  return callback(event, &record, 0, context);
}
