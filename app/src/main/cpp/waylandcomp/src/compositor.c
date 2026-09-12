/*
 * bannerlator-wayland — the embedded compositor of the Wayland display path.
 *
 * Clients are the Wine processes of a container (winewayland.drv) and, through them,
 * Mesa's Vulkan WSI. Each process is its own client, so a Windows virtual desktop is
 * described with banner_desktop_v1: the desktop owner marks one surface as the desktop,
 * and every process reports its top-level windows' desktop positions and the Windows
 * stacking order.
 *
 * The scene is the desktop surface at the bottom, then every mapped xdg_toplevel at its
 * reported position in stacking order, each with its subsurface tree (a game's Vulkan
 * swapchain is a subsurface of its window) and wp_viewport crop/scale applied. It is
 * redrawn once per event-loop pass after any commit or layout change, stretched to the
 * Android surface.
 *
 * Input goes to the desktop surface in desktop coordinates: the Wine server routes it to
 * the window under the pointer (or the focus window) in whichever process owns it, the
 * same as winex11's virtual desktop. Without a desktop (a client that doesn't speak
 * banner_desktop_v1) the topmost window under the pointer gets it.
 */
#define _GNU_SOURCE 1
#define _POSIX_C_SOURCE 200809L
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <stdint.h>
#include <fcntl.h>
#include <time.h>
#include <stdarg.h>
#include <errno.h>
#include <sys/timerfd.h>
#include <android/log.h>
#include <wayland-server.h>

#include "xdg-shell-server-protocol.h"
#include "linux-dmabuf-v1-server-protocol.h"
#include "viewporter-server-protocol.h"
#include "banner-desktop-v1-server-protocol.h"
#include "presentation-time-server-protocol.h"
#include "vk_present.h"

#define WLOGI(...) __android_log_print(ANDROID_LOG_INFO, "BannerWayland", __VA_ARGS__)
#define WLOGE(...) __android_log_print(ANDROID_LOG_ERROR, "BannerWayland", __VA_ARGS__)

/* ------------------------------------------------------------------ session log
 * One readable, time-stamped file per Wayland session in Download/Wayland-logs, also
 * mirrored to logcat: which programs connect, the desktop, windows opening and closing,
 * Vulkan frames arriving, a frame-rate summary every 10 seconds, and errors. */

#define SESSION_LOG_DIR "/storage/emulated/0/Download/Wayland-logs"

static FILE *g_log;

void banner_log(const char *tag, const char *fmt, ...) {
    char msg[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof(msg), fmt, ap);
    va_end(ap);
    WLOGI("[%s] %s", tag, msg);
    if (!g_log) return;
    struct timespec ts;
    struct tm tm;
    clock_gettime(CLOCK_REALTIME, &ts);
    localtime_r(&ts.tv_sec, &tm);
    fprintf(g_log, "%02d:%02d:%02d.%03ld  %-9s %s\n", tm.tm_hour, tm.tm_min, tm.tm_sec,
            ts.tv_nsec / 1000000, tag, msg);
}

static void open_session_log(void) {
    char path[256], stamp[32];
    time_t now = time(NULL);
    struct tm tm;

    localtime_r(&now, &tm);
    strftime(stamp, sizeof(stamp), "%Y-%m-%d_%H-%M-%S", &tm);
    mkdir("/storage/emulated/0/Download", 0775);
    if (mkdir(SESSION_LOG_DIR, 0775) != 0 && errno != EEXIST)
        WLOGE("can't create %s: %s", SESSION_LOG_DIR, strerror(errno));
    snprintf(path, sizeof(path), "%s/wayland-%s.log", SESSION_LOG_DIR, stamp);
    if (!(g_log = fopen(path, "w"))) {
        WLOGE("can't open session log %s: %s", path, strerror(errno));
        return;
    }
    setvbuf(g_log, NULL, _IOLBF, 0);
    strftime(stamp, sizeof(stamp), "%Y-%m-%d %H:%M:%S", &tm);
    fprintf(g_log,
            "Bannerlator Wayland session\n"
            "===========================\n"
            "Started   %s\n"
            "Display   Wayland: Windows programs draw through the Bannerlator compositor.\n"
            "          No X server is used for this session.\n"
            "Log       %s\n\n"
            "Time          Area      Event\n"
            "------------  --------  -----------------------------------------------------\n",
            stamp, path);
    WLOGI("session log: %s", path);
}

/* At most ~10 lines a second for chatty events (window moves), so a drag can't flood logcat. */
static int log_budget(void) {
    static struct timespec window;
    static int used;
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    if (now.tv_sec != window.tv_sec) { window = now; used = 0; }
    return used++ < 10;
}

#ifndef BTN_LEFT
#define BTN_LEFT 0x110  /* linux/input-event-codes.h */
#endif
#ifndef BTN_RIGHT
#define BTN_RIGHT 0x111
#endif

/* Java sends pointer coordinates in this space, covering the whole output surface. */
#define INPUT_SPACE_W 1920
#define INPUT_SPACE_H 1080

static struct wl_display *g_display;

/* Which program each Wayland client is (from /proc/<pid>/cmdline), for the session log. */
struct client_info {
    struct wl_client *client;
    struct wl_listener destroy;
    pid_t pid;
    char name[64];
    struct client_info *next;
};
static struct client_info *g_clients;

static const char *client_name(struct wl_client *client) {
    for (struct client_info *ci = g_clients; ci; ci = ci->next)
        if (ci->client == client) return ci->name;
    return "a program";
}

static void on_client_destroyed(struct wl_listener *l, void *data) {
    struct client_info *ci = wl_container_of(l, ci, destroy), **pp;
    banner_log("program", "disconnected: %s (pid %d)", ci->name, (int)ci->pid);
    for (pp = &g_clients; *pp; pp = &(*pp)->next)
        if (*pp == ci) { *pp = ci->next; break; }
    free(ci);
}

static void on_client_created(struct wl_listener *l, void *data) {
    struct wl_client *client = data;
    struct client_info *ci = calloc(1, sizeof(*ci));
    char path[64], buf[256];
    uid_t uid; gid_t gid;
    int fd;
    ssize_t n;

    if (!ci) return;
    ci->client = client;
    wl_client_get_credentials(client, &ci->pid, &uid, &gid);
    snprintf(ci->name, sizeof(ci->name), "pid %d", (int)ci->pid);
    snprintf(path, sizeof(path), "/proc/%d/cmdline", (int)ci->pid);
    if ((fd = open(path, O_RDONLY | O_CLOEXEC)) >= 0) {
        if ((n = read(fd, buf, sizeof(buf) - 1)) > 0) {
            char *base = buf, *p;
            buf[n] = 0;  /* argv[0] only; Wine puts the Windows program path there */
            for (p = buf; *p; p++) if (*p == '\\' || *p == '/') base = p + 1;
            if (*base) snprintf(ci->name, sizeof(ci->name), "%s", base);
        }
        close(fd);
    }
    ci->destroy.notify = on_client_destroyed;
    wl_client_add_destroy_listener(client, &ci->destroy);
    ci->next = g_clients;
    g_clients = ci;
    banner_log("program", "connected over Wayland: %s (pid %d)", ci->name, (int)ci->pid);
}

/* ------------------------------------------------------------------ surfaces */

enum surface_role { ROLE_NONE, ROLE_TOPLEVEL, ROLE_SUBSURFACE, ROLE_DESKTOP };

struct surface {
    struct wl_resource *resource;
    struct wl_list link;                    /* g_surfaces */

    /* Pending state, applied on commit. */
    struct wl_resource *pending_buffer;
    struct wl_listener pending_buffer_destroy;
    int pending_attach;
    int pending_src_set, pending_dst_set;
    float pending_src[4];                   /* x, y, w, h; w < 0 = unset */
    int pending_dst[2];                     /* w, h; w < 0 = unset */
    struct wl_list pending_frames;
    struct wl_list pending_feedback;        /* wp_presentation feedback asked for before commit */

    /* Current content. */
    struct vkp_image *shm_img;              /* our copy of the last wl_shm buffer */
    struct wl_resource *dmabuf;             /* current dmabuf buffer, held until replaced */
    struct wl_listener dmabuf_destroy;
    int buf_w, buf_h, has_content;
    int src_set, dst_set;
    float src[4];
    int dst[2];
    struct wl_list frames;                  /* frame callbacks for the next redraw */
    struct wl_list feedback;                /* presentation feedback for the current content */
    int drawn;                              /* part of the last rendered scene */
    int64_t next_release_ns;                /* FPS limiter: when this surface's next buffer goes back */

    enum surface_role role;
    struct wl_resource *xdg_surface, *xdg_toplevel;
    struct wl_resource *viewport;

    /* Toplevel placement on the scene. */
    int mapped;                             /* in g_toplevels */
    struct wl_list toplevel_link;
    int x, y, placed;
    uint32_t hwnd;

    /* Subsurface tree. */
    struct surface *parent;
    struct wl_resource *subsurface;
    struct wl_list children;                /* bottom to top */
    struct wl_list child_link;
    int sub_x, sub_y, sub_pending_x, sub_pending_y, sub_pending;
    int below_parent;

    char *title;                            /* xdg_toplevel title, for the session log */
    int announced_vulkan;                   /* logged its first dmabuf frame */
};

static struct wl_list g_surfaces;           /* every surface */
static struct wl_list g_toplevels;          /* mapped toplevels, bottom to top */
static struct surface *g_desktop;
static uint32_t *g_zorder;                  /* last reported Windows stacking order, top first */
static size_t g_zorder_count;
static struct wl_event_source *g_render_idle, *g_frame_timer;

/* Rendering is driven by the screen's vsync (Java's Choreographer ticks, nativeVsync): one scene
 * per refresh showing the newest buffers, so the event loop is never parked in the swapchain and
 * clients get their buffers back as soon as they're replaced. Without ticks (an older app, no
 * window) a fallback timer renders instead. */
static int g_dirty;                         /* something changed since the last render */
static int64_t g_last_vsync_ns;             /* last tick, 0 = none yet */
static int64_t g_refresh_ns = 16666667;     /* screen refresh interval, from the ticks */
static struct wl_event_source *g_fallback_timer;
static int g_fallback_armed;

/* FPS limiter (the in-game drawer's): frames per second, 0 = unlimited. Set from the app thread,
 * read here. Like the X11 path, which delays the "your buffer is free" notice, the limit paces
 * when a replaced buffer is released to its client, so the game itself slows to the cap. */
volatile int g_fps_limit;

/* Shortcut launches: explorer's windows (the desktop, taskbar, Start menu) aren't drawn, matching
 * the X11 renderer's unviewable "explorer.exe". They still exist for input routing. */
volatile int g_hide_shell;

/* The panel's refresh rate in mHz, from the app; wl_output advertises it so Wine's display modes
 * carry the real rate (games pick their saved 144 Hz mode, as on X11). 0 = 60 Hz. */
volatile int g_output_refresh_mhz;

/* The advertised output size: the container's screen (what the app's X server reports on X11),
 * so Wine's display-mode list stops at the desktop size. 0 = 1920x1080. */
volatile int g_output_w, g_output_h;
struct pending_release {
    struct wl_resource *buffer;
    struct wl_listener destroy;
    int64_t at_ns;
    struct wl_list link;
};
static struct wl_list g_pending_releases;
static int g_release_timer_fd = -1;
static struct wl_event_source *g_release_source;
static int g_scene_w, g_scene_h;            /* size of the last drawn scene */
static int g_desktop_w, g_desktop_h;        /* last size the desktop had content at */
static unsigned g_stat_frames, g_stat_dmabuf, g_stat_shm; /* since the last 10 s summary */

static void schedule_render(void);

static int64_t now_ns(void) {
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

/* ------------------------------------------------------------------ buffer release pacing */

static void pending_release_free(struct pending_release *pr) {
    wl_list_remove(&pr->destroy.link);
    wl_list_remove(&pr->link);
    free(pr);
}

static void on_pending_release_buffer_destroyed(struct wl_listener *l, void *data) {
    struct pending_release *pr = wl_container_of(l, pr, destroy);
    pending_release_free(pr);
}

static void arm_release_timer(void) {
    struct pending_release *pr;
    int64_t earliest = 0;
    if (g_release_timer_fd < 0) return;
    wl_list_for_each(pr, &g_pending_releases, link)
        if (!earliest || pr->at_ns < earliest) earliest = pr->at_ns;
    struct itimerspec its = {0};
    if (earliest) { its.it_value.tv_sec = earliest / 1000000000LL; its.it_value.tv_nsec = earliest % 1000000000LL; }
    timerfd_settime(g_release_timer_fd, TFD_TIMER_ABSTIME, &its, NULL);
}

static int on_release_timer(int fd, uint32_t mask, void *data) {
    struct pending_release *pr, *tmp;
    uint64_t expirations;
    int64_t now = now_ns() + 300000; /* anything due within 0.3 ms goes now */
    if (read(fd, &expirations, sizeof(expirations)) < 0 && errno != EAGAIN) return 0;
    wl_list_for_each_safe(pr, tmp, &g_pending_releases, link) {
        if (pr->at_ns <= now) {
            wl_buffer_send_release(pr->buffer);
            pending_release_free(pr);
        }
    }
    wl_display_flush_clients(g_display);
    arm_release_timer();
    return 0;
}

/* Give a replaced buffer back to its client: now, or on the limiter's cadence. */
static void release_buffer(struct surface *s, struct wl_resource *buffer) {
    int limit = g_fps_limit;
    if (limit <= 0 || g_release_timer_fd < 0) { wl_buffer_send_release(buffer); return; }
    int64_t interval = 1000000000LL / limit, now = now_ns();
    if (s->next_release_ns <= now - interval) s->next_release_ns = now + interval;
    else s->next_release_ns += interval;
    struct pending_release *pr = calloc(1, sizeof(*pr));
    if (!pr) { wl_buffer_send_release(buffer); return; }
    pr->buffer = buffer;
    pr->at_ns = s->next_release_ns;
    pr->destroy.notify = on_pending_release_buffer_destroyed;
    wl_resource_add_destroy_listener(buffer, &pr->destroy);
    wl_list_insert(g_pending_releases.prev, &pr->link);
    arm_release_timer();
}

/* ------------------------------------------------------------------ seat state */

/* Each Wine process is a separate client with its own wl_pointer / wl_keyboard. */
struct seat_pointer { struct wl_resource *ptr; struct wl_resource *focus; };
struct seat_keyboard { struct wl_resource *kb; struct wl_resource *focus; };
#define MAX_PTRS 32
static struct seat_pointer g_ptrs[MAX_PTRS];
static int g_nptrs;
static struct seat_keyboard g_kbs[MAX_PTRS];
static int g_nkbs;
static struct surface *g_grab;              /* no-desktop fallback: surface holding the button */
static struct surface *g_key_target;        /* no-desktop fallback: last clicked surface */
static int g_input_pipe[2] = {-1, -1};
/* type 0 = pointer (p1=action 0down/1move/2up, p2=x, p3=y); type 1 = key (p1=evdev, p2=state 1down/0up) */
struct input_msg { int type; int p1; int p2; int p3; };

/* ------------------------------------------------------------------ dmabuf buffers */

#define FOURCC(a, b, c, d) \
    ((uint32_t)(a) | ((uint32_t)(b) << 8) | ((uint32_t)(c) << 16) | ((uint32_t)(d) << 24))
#define DRM_ARGB8888 FOURCC('A', 'R', '2', '4')
#define DRM_XRGB8888 FOURCC('X', 'R', '2', '4')
#define DRM_ABGR8888 FOURCC('A', 'B', '2', '4')
#define DRM_XBGR8888 FOURCC('X', 'B', '2', '4')
#define MOD_LINEAR 0ULL
#define MOD_INVALID 0x00ffffffffffffffULL
#define MAX_PLANES 4

struct dmabuf_params {
    int fd[MAX_PLANES];
    uint32_t offset[MAX_PLANES], stride[MAX_PLANES];
    uint64_t modifier[MAX_PLANES];
    int n_planes;
};
struct dmabuf_buffer {
    int fd[MAX_PLANES];
    uint32_t offset[MAX_PLANES], stride[MAX_PLANES];
    int n_planes;
    int32_t width, height;
    uint32_t format;
    uint64_t modifier;
    struct vkp_image *img;                  /* imported once, reused for every frame */
    int import_failed;
};

static void dbuf_buffer_destroy_req(struct wl_client *c, struct wl_resource *r) {
    wl_resource_destroy(r);
}
static const struct wl_buffer_interface dbuf_buffer_impl = {
    .destroy = dbuf_buffer_destroy_req,
};

static struct dmabuf_buffer *get_dmabuf(struct wl_resource *buffer) {
    if (buffer && wl_resource_instance_of(buffer, &wl_buffer_interface, &dbuf_buffer_impl))
        return wl_resource_get_user_data(buffer);
    return NULL;
}

/* ------------------------------------------------------------------ surface state */

static void surface_size(const struct surface *s, int *w, int *h) {
    if (s->dst_set) { *w = s->dst[0]; *h = s->dst[1]; }
    else if (s->src_set) { *w = (int)(s->src[2] + 0.5f); *h = (int)(s->src[3] + 0.5f); }
    else { *w = s->buf_w; *h = s->buf_h; }
}

/* "Title" (program) for the log; subsurfaces are named after the window they belong to. */
static void describe(const struct surface *s, char *out, size_t size) {
    const struct surface *w = s;
    while (w->parent) w = w->parent;
    const char *prog = client_name(wl_resource_get_client(w->resource));
    if (w->title && *w->title) snprintf(out, size, "\"%s\" (%s)", w->title, prog);
    else if (w->role == ROLE_DESKTOP) snprintf(out, size, "the desktop (%s)", prog);
    else snprintf(out, size, "window %#x (%s)", w->hwnd, prog);
}

static struct surface *toplevel_by_hwnd(uint32_t hwnd) {
    struct surface *s;
    wl_list_for_each(s, &g_toplevels, toplevel_link)
        if (s->hwnd == hwnd) return s;
    return NULL;
}

/* Reorder the mapped toplevels to match the last reported Windows z-order. Windows the
 * report doesn't mention keep their relative order below the ones it does. */
static void apply_zorder(void) {
    for (size_t i = g_zorder_count; i-- > 0;) {
        struct surface *s = toplevel_by_hwnd(g_zorder[i]);
        if (!s) continue;
        wl_list_remove(&s->toplevel_link);
        wl_list_insert(g_toplevels.prev, &s->toplevel_link);
    }
}

static void map_toplevel(struct surface *s) {
    int w, h;
    if (s->mapped) return;
    s->mapped = 1;
    surface_size(s, &w, &h);
    {
        char name[160];
        describe(s, name, sizeof(name));
        banner_log("window", "opened %s %dx%d at %d,%d%s", name, w, h, s->x, s->y,
                   s->placed ? "" : " (no desktop position yet)");
    }
    wl_list_insert(g_toplevels.prev, &s->toplevel_link); /* new windows start on top */
    apply_zorder();
}

static void unmap_toplevel(struct surface *s) {
    if (!s->mapped) return;
    s->mapped = 0;
    {
        char name[160];
        describe(s, name, sizeof(name));
        banner_log("window", "closed %s", name);
    }
    wl_list_remove(&s->toplevel_link);
    wl_list_init(&s->toplevel_link);
}

static void drop_dmabuf(struct surface *s, int release) {
    if (!s->dmabuf) return;
    wl_list_remove(&s->dmabuf_destroy.link);
    if (release) release_buffer(s, s->dmabuf);
    s->dmabuf = NULL;
}

static void on_dmabuf_destroyed(struct wl_listener *l, void *data) {
    struct surface *s = wl_container_of(l, s, dmabuf_destroy);
    wl_list_remove(&s->dmabuf_destroy.link);
    s->dmabuf = NULL;
    s->has_content = 0;
    if (s->role == ROLE_TOPLEVEL) unmap_toplevel(s);
    schedule_render();
}

static void on_pending_buffer_destroyed(struct wl_listener *l, void *data) {
    struct surface *s = wl_container_of(l, s, pending_buffer_destroy);
    wl_list_remove(&s->pending_buffer_destroy.link);
    wl_list_init(&s->pending_buffer_destroy.link);
    s->pending_buffer = NULL;
}

static void fire_frames(struct wl_list *frames) {
    struct wl_resource *cb, *tmp;
    uint32_t t;
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    t = (uint32_t)(ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
    wl_resource_for_each_safe(cb, tmp, frames) {
        wl_callback_send_done(cb, t);
        wl_resource_destroy(cb);
    }
}

/* Copy a committed wl_shm buffer into the surface's image and release the buffer. */
static void take_shm(struct surface *s, struct wl_shm_buffer *shm, struct wl_resource *buffer) {
    int32_t w = wl_shm_buffer_get_width(shm), h = wl_shm_buffer_get_height(shm);
    int32_t stride = wl_shm_buffer_get_stride(shm);

    if (s->shm_img && (vkp_image_width(s->shm_img) != w || vkp_image_height(s->shm_img) != h)) {
        vkp_image_destroy(s->shm_img);
        s->shm_img = NULL;
    }
    if (!s->shm_img) s->shm_img = vkp_image_create_shm(w, h);
    if (s->shm_img) {
        wl_shm_buffer_begin_access(shm);
        vkp_image_upload_shm(s->shm_img, wl_shm_buffer_get_data(shm), stride);
        wl_shm_buffer_end_access(shm);
    }
    wl_buffer_send_release(buffer);
    s->buf_w = w;
    s->buf_h = h;
    s->has_content = s->shm_img != NULL;
    g_stat_shm++;
}

/* The window the app's performance HUD follows: the latest one to start presenting GPU frames
 * (X11 binds the HUD to the _MESA_DRV window and counts X presents instead). JNI upcalls. */
static struct surface *g_hud_surface;
extern void banner_on_game_surface(const char *window, const char *gpu); /* window NULL = gone */
extern void banner_on_game_frame(void);

static void take_dmabuf(struct surface *s, struct dmabuf_buffer *b, struct wl_resource *buffer) {
    if (s->dmabuf != buffer) {
        drop_dmabuf(s, 1);
        s->dmabuf = buffer;
        s->dmabuf_destroy.notify = on_dmabuf_destroyed;
        wl_resource_add_destroy_listener(buffer, &s->dmabuf_destroy);
    }
    if (!b->img && !b->import_failed && b->n_planes >= 1) {
        b->img = vkp_image_from_dmabuf(b->fd[0], b->format, b->modifier, b->width, b->height,
                                       b->stride[0], b->offset[0]);
        if (!b->img) {
            b->import_failed = 1;
            WLOGE("dmabuf import failed (%dx%d fmt=0x%08x mod=0x%llx)", b->width, b->height,
                  b->format, (unsigned long long)b->modifier);
        }
    }
    if (s->shm_img) { vkp_image_destroy(s->shm_img); s->shm_img = NULL; }
    s->buf_w = b->width;
    s->buf_h = b->height;
    s->has_content = b->img != NULL;
    g_stat_dmabuf++;
    if (!s->announced_vulkan) {
        char name[160];
        s->announced_vulkan = 1;
        describe(s, name, sizeof(name));
        if (b->img)
            banner_log("vulkan", "%s is presenting GPU frames through Wayland: %dx%d, format %c%c%c%c, %s (zero-copy)",
                       name, b->width, b->height, b->format & 0xff, (b->format >> 8) & 0xff,
                       (b->format >> 16) & 0xff, (b->format >> 24) & 0xff,
                       b->modifier == MOD_LINEAR ? "linear" : "tiled");
        else
            banner_log("error", "could not import GPU frames from %s (%dx%d, modifier %#llx)",
                       name, b->width, b->height, (unsigned long long)b->modifier);
        if (b->img) {
            g_hud_surface = s;
            banner_on_game_surface(name, vkp_gpu_name());
        }
    }
    if (s == g_hud_surface && b->img) banner_on_game_frame();
}

/* ------------------------------------------------------------------ wl_surface */

static void surface_destroy(struct wl_client *c, struct wl_resource *r) {
    wl_resource_destroy(r);
}
static void surface_attach(struct wl_client *c, struct wl_resource *r,
                           struct wl_resource *buffer, int32_t x, int32_t y) {
    struct surface *s = wl_resource_get_user_data(r);
    if (s->pending_buffer) wl_list_remove(&s->pending_buffer_destroy.link);
    wl_list_init(&s->pending_buffer_destroy.link);
    s->pending_buffer = buffer;
    s->pending_attach = 1;
    if (buffer) {
        s->pending_buffer_destroy.notify = on_pending_buffer_destroyed;
        wl_resource_add_destroy_listener(buffer, &s->pending_buffer_destroy);
    }
}
static void surface_damage(struct wl_client *c, struct wl_resource *r,
                           int32_t x, int32_t y, int32_t w, int32_t h) {}
static void frame_callback_destroy(struct wl_resource *r) {
    wl_list_remove(wl_resource_get_link(r));
}

/* wp_presentation: tells Mesa's Vulkan driver when a frame reached the screen or was replaced
 * before it did. Without it the driver waits for a frame callback (one per refresh) for every
 * frame, which paced games to the screen rate even in mailbox mode. */
static void feedback_resource_destroy(struct wl_resource *r) {
    wl_list_remove(wl_resource_get_link(r));
}

static void feedback_discard_all(struct wl_list *list) {
    struct wl_resource *fb, *tmp;
    wl_resource_for_each_safe(fb, tmp, list) {
        wp_presentation_feedback_send_discarded(fb);
        wl_resource_destroy(fb);
    }
}

static void feedback_present_all(struct wl_list *list, int64_t t) {
    struct wl_resource *fb, *tmp;
    uint64_t sec = (uint64_t)(t / 1000000000LL);
    uint32_t nsec = (uint32_t)(t % 1000000000LL);
    wl_resource_for_each_safe(fb, tmp, list) {
        wp_presentation_feedback_send_presented(fb, (uint32_t)(sec >> 32), (uint32_t)sec, nsec,
                                                (uint32_t)g_refresh_ns, 0, 0,
                                                WP_PRESENTATION_FEEDBACK_KIND_VSYNC);
        wl_resource_destroy(fb);
    }
}

static void presentation_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void presentation_feedback(struct wl_client *c, struct wl_resource *r,
                                  struct wl_resource *surface, uint32_t id) {
    struct surface *s = wl_resource_get_user_data(surface);
    struct wl_resource *fb = wl_resource_create(c, &wp_presentation_feedback_interface,
                                                wl_resource_get_version(r), id);
    if (!fb) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(fb, NULL, NULL, feedback_resource_destroy);
    if (!s) { wp_presentation_feedback_send_discarded(fb); wl_resource_destroy(fb); return; }
    wl_list_insert(s->pending_feedback.prev, wl_resource_get_link(fb));
}
static const struct wp_presentation_interface presentation_impl = {
    .destroy = presentation_destroy,
    .feedback = presentation_feedback,
};
static void bind_presentation(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &wp_presentation_interface, ver, id);
    if (!r) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(r, &presentation_impl, NULL, NULL);
    wp_presentation_send_clock_id(r, CLOCK_MONOTONIC);
}
static void surface_frame(struct wl_client *c, struct wl_resource *r, uint32_t cb) {
    struct surface *s = wl_resource_get_user_data(r);
    struct wl_resource *callback = wl_resource_create(c, &wl_callback_interface, 1, cb);
    if (!callback) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(callback, NULL, NULL, frame_callback_destroy);
    wl_list_insert(s->pending_frames.prev, wl_resource_get_link(callback));
}
static void surface_set_opaque(struct wl_client *c, struct wl_resource *r,
                               struct wl_resource *region) {}
static void surface_set_input(struct wl_client *c, struct wl_resource *r,
                              struct wl_resource *region) {}

static void surface_commit(struct wl_client *c, struct wl_resource *r) {
    struct surface *s = wl_resource_get_user_data(r);
    struct surface *child;

    if (s->pending_src_set) {
        s->src_set = s->pending_src[2] > 0;
        memcpy(s->src, s->pending_src, sizeof(s->src));
        s->pending_src_set = 0;
    }
    if (s->pending_dst_set) {
        s->dst_set = s->pending_dst[0] > 0;
        memcpy(s->dst, s->pending_dst, sizeof(s->dst));
        s->pending_dst_set = 0;
    }

    if (s->pending_attach) {
        struct wl_resource *buffer = s->pending_buffer;
        struct dmabuf_buffer *db = get_dmabuf(buffer);
        struct wl_shm_buffer *shm = buffer && !db ? wl_shm_buffer_get(buffer) : NULL;

        /* The previous content is replaced before reaching the screen. */
        feedback_discard_all(&s->feedback);

        if (buffer) {
            wl_list_remove(&s->pending_buffer_destroy.link);
            wl_list_init(&s->pending_buffer_destroy.link);
        }
        s->pending_buffer = NULL;
        s->pending_attach = 0;

        if (s->role == ROLE_NONE) {
            /* Cursor or role-less surface: never drawn (the app draws its own pointer). */
            if (buffer) wl_buffer_send_release(buffer);
        } else if (db) {
            take_dmabuf(s, db, buffer);
        } else if (shm) {
            drop_dmabuf(s, 1);
            take_shm(s, shm, buffer);
        } else {
            drop_dmabuf(s, 1);
            s->has_content = 0;
            if (buffer) wl_buffer_send_release(buffer);
        }
    }

    /* Subsurface positions are applied when the parent commits. */
    wl_list_for_each(child, &s->children, child_link) {
        if (child->sub_pending) {
            child->sub_x = child->sub_pending_x;
            child->sub_y = child->sub_pending_y;
            child->sub_pending = 0;
        }
    }

    wl_list_insert_list(s->frames.prev, &s->pending_frames);
    wl_list_init(&s->pending_frames);
    wl_list_insert_list(s->feedback.prev, &s->pending_feedback);
    wl_list_init(&s->pending_feedback);

    if (s->role == ROLE_TOPLEVEL && s->xdg_toplevel) {
        if (s->has_content) map_toplevel(s);
        else unmap_toplevel(s);
    }
    schedule_render();
}
static void surface_set_buffer_transform(struct wl_client *c, struct wl_resource *r, int32_t t) {}
static void surface_set_buffer_scale(struct wl_client *c, struct wl_resource *r, int32_t s) {}
static void surface_damage_buffer(struct wl_client *c, struct wl_resource *r,
                                  int32_t x, int32_t y, int32_t w, int32_t h) {}
static void surface_offset(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y) {}

static const struct wl_surface_interface surface_impl = {
    .destroy = surface_destroy,
    .attach = surface_attach,
    .damage = surface_damage,
    .frame = surface_frame,
    .set_opaque_region = surface_set_opaque,
    .set_input_region = surface_set_input,
    .commit = surface_commit,
    .set_buffer_transform = surface_set_buffer_transform,
    .set_buffer_scale = surface_set_buffer_scale,
    .damage_buffer = surface_damage_buffer,
    .offset = surface_offset,
};

static void detach_from_parent(struct surface *s) {
    if (!s->parent) return;
    wl_list_remove(&s->child_link);
    wl_list_init(&s->child_link);
    s->parent = NULL;
}

static void surface_resource_destroy(struct wl_resource *r) {
    struct surface *s = wl_resource_get_user_data(r);
    struct surface *child, *tmp;
    struct wl_resource *cb, *cbtmp;

    if (!s) return;
    for (int i = 0; i < g_nptrs; i++) if (g_ptrs[i].focus == r) g_ptrs[i].focus = NULL;
    for (int i = 0; i < g_nkbs; i++) if (g_kbs[i].focus == r) g_kbs[i].focus = NULL;
    if (g_grab == s) g_grab = NULL;
    if (g_key_target == s) g_key_target = NULL;
    if (g_desktop == s) { g_desktop = NULL; banner_log("desktop", "the desktop closed"); }
    if (g_hud_surface == s) { g_hud_surface = NULL; banner_on_game_surface(NULL, NULL); }

    unmap_toplevel(s);
    detach_from_parent(s);
    wl_list_for_each_safe(child, tmp, &s->children, child_link) {
        wl_list_remove(&child->child_link);
        wl_list_init(&child->child_link);
        child->parent = NULL;
    }
    if (s->pending_buffer) wl_list_remove(&s->pending_buffer_destroy.link);
    drop_dmabuf(s, 0);
    vkp_image_destroy(s->shm_img);
    wl_resource_for_each_safe(cb, cbtmp, &s->pending_frames) wl_resource_destroy(cb);
    wl_resource_for_each_safe(cb, cbtmp, &s->frames) wl_resource_destroy(cb);
    feedback_discard_all(&s->pending_feedback);
    feedback_discard_all(&s->feedback);
    if (s->viewport) wl_resource_set_user_data(s->viewport, NULL);
    if (s->subsurface) wl_resource_set_user_data(s->subsurface, NULL);
    if (s->xdg_surface) wl_resource_set_user_data(s->xdg_surface, NULL);
    if (s->xdg_toplevel) wl_resource_set_user_data(s->xdg_toplevel, NULL);
    wl_list_remove(&s->link);
    free(s->title);
    free(s);
    schedule_render();
}

/* ------------------------------------------------------------------ wl_region */

static void region_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void region_add(struct wl_client *c, struct wl_resource *r,
                       int32_t x, int32_t y, int32_t w, int32_t h) {}
static void region_subtract(struct wl_client *c, struct wl_resource *r,
                            int32_t x, int32_t y, int32_t w, int32_t h) {}
static const struct wl_region_interface region_impl = {
    .destroy = region_destroy,
    .add = region_add,
    .subtract = region_subtract,
};

/* ------------------------------------------------------------------ wl_compositor */

static void compositor_create_surface(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct surface *s = calloc(1, sizeof(*s));
    if (!s) { wl_client_post_no_memory(c); return; }
    s->resource = wl_resource_create(c, &wl_surface_interface, wl_resource_get_version(r), id);
    if (!s->resource) { free(s); wl_client_post_no_memory(c); return; }
    wl_list_init(&s->pending_frames);
    wl_list_init(&s->frames);
    wl_list_init(&s->pending_feedback);
    wl_list_init(&s->feedback);
    wl_list_init(&s->children);
    wl_list_init(&s->child_link);
    wl_list_init(&s->toplevel_link);
    wl_list_init(&s->pending_buffer_destroy.link);
    wl_list_insert(&g_surfaces, &s->link);
    wl_resource_set_implementation(s->resource, &surface_impl, s, surface_resource_destroy);
}
static void compositor_create_region(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct wl_resource *reg = wl_resource_create(c, &wl_region_interface, 1, id);
    if (!reg) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(reg, &region_impl, NULL, NULL);
}
static const struct wl_compositor_interface compositor_impl = {
    .create_surface = compositor_create_surface,
    .create_region = compositor_create_region,
};
static void bind_compositor(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &wl_compositor_interface, ver, id);
    wl_resource_set_implementation(r, &compositor_impl, NULL, NULL);
}

/* --------------------------------------------------------------- wl_subcompositor
 * winewayland puts a window's Vulkan/GL swapchain in a subsurface over the window. */

static void subsurface_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void subsurface_set_position(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y) {
    struct surface *s = wl_resource_get_user_data(r);
    if (!s) return;
    s->sub_pending_x = x;
    s->sub_pending_y = y;
    s->sub_pending = 1;
}
static void restack_child(struct surface *s, struct surface *sibling, int above) {
    if (!s || !s->parent || !sibling) return;
    if (sibling == s->parent) {
        /* Relative to the parent: directly above it or directly below it. */
        s->below_parent = !above;
        wl_list_remove(&s->child_link);
        if (above) wl_list_insert(&s->parent->children, &s->child_link);
        else wl_list_insert(s->parent->children.prev, &s->child_link);
    } else if (sibling->parent == s->parent) {
        s->below_parent = sibling->below_parent;
        wl_list_remove(&s->child_link);
        if (above) wl_list_insert(&sibling->child_link, &s->child_link);
        else wl_list_insert(sibling->child_link.prev, &s->child_link);
    }
    schedule_render();
}
static void subsurface_place_above(struct wl_client *c, struct wl_resource *r,
                                   struct wl_resource *sibling) {
    restack_child(wl_resource_get_user_data(r), wl_resource_get_user_data(sibling), 1);
}
static void subsurface_place_below(struct wl_client *c, struct wl_resource *r,
                                   struct wl_resource *sibling) {
    restack_child(wl_resource_get_user_data(r), wl_resource_get_user_data(sibling), 0);
}
static void subsurface_set_sync(struct wl_client *c, struct wl_resource *r) {}
static void subsurface_set_desync(struct wl_client *c, struct wl_resource *r) {}
static const struct wl_subsurface_interface subsurface_impl = {
    .destroy = subsurface_destroy,
    .set_position = subsurface_set_position,
    .place_above = subsurface_place_above,
    .place_below = subsurface_place_below,
    .set_sync = subsurface_set_sync,
    .set_desync = subsurface_set_desync,
};
static void subsurface_resource_destroy(struct wl_resource *r) {
    struct surface *s = wl_resource_get_user_data(r);
    if (!s) return;
    detach_from_parent(s);
    s->subsurface = NULL;
    s->role = ROLE_NONE;
    s->has_content = 0;
    drop_dmabuf(s, 1);
    schedule_render();
}

static void subcompositor_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void subcompositor_get_subsurface(struct wl_client *c, struct wl_resource *r, uint32_t id,
                                         struct wl_resource *surface, struct wl_resource *parent) {
    struct surface *s = wl_resource_get_user_data(surface);
    struct surface *p = wl_resource_get_user_data(parent);
    struct wl_resource *sub = wl_resource_create(c, &wl_subsurface_interface, wl_resource_get_version(r), id);
    if (!sub) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(sub, &subsurface_impl, s, subsurface_resource_destroy);
    if (!s || !p || s == p) return;
    detach_from_parent(s);
    s->role = ROLE_SUBSURFACE;
    s->subsurface = sub;
    s->parent = p;
    s->below_parent = 0;
    s->sub_x = s->sub_y = 0;
    wl_list_insert(p->children.prev, &s->child_link); /* new subsurfaces go on top */
}
static const struct wl_subcompositor_interface subcompositor_impl = {
    .destroy = subcompositor_destroy,
    .get_subsurface = subcompositor_get_subsurface,
};
static void bind_subcompositor(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &wl_subcompositor_interface, ver, id);
    wl_resource_set_implementation(r, &subcompositor_impl, NULL, NULL);
}

/* ---------------------------------------------------------------- wp_viewporter
 * winewayland crops window buffers to the window size and scales swapchains to the
 * client area with viewports, so they must be honoured for things to line up. */

static void viewport_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void viewport_set_source(struct wl_client *c, struct wl_resource *r,
                                wl_fixed_t x, wl_fixed_t y, wl_fixed_t w, wl_fixed_t h) {
    struct surface *s = wl_resource_get_user_data(r);
    if (!s) return;
    s->pending_src[0] = (float)wl_fixed_to_double(x);
    s->pending_src[1] = (float)wl_fixed_to_double(y);
    s->pending_src[2] = w == wl_fixed_from_int(-1) ? -1.0f : (float)wl_fixed_to_double(w);
    s->pending_src[3] = h == wl_fixed_from_int(-1) ? -1.0f : (float)wl_fixed_to_double(h);
    s->pending_src_set = 1;
}
static void viewport_set_destination(struct wl_client *c, struct wl_resource *r, int32_t w, int32_t h) {
    struct surface *s = wl_resource_get_user_data(r);
    if (!s) return;
    s->pending_dst[0] = w;
    s->pending_dst[1] = h;
    s->pending_dst_set = 1;
}
static const struct wp_viewport_interface viewport_impl = {
    .destroy = viewport_destroy,
    .set_source = viewport_set_source,
    .set_destination = viewport_set_destination,
};
static void viewport_resource_destroy(struct wl_resource *r) {
    struct surface *s = wl_resource_get_user_data(r);
    if (!s) return;
    s->viewport = NULL;
    s->pending_src[2] = -1.0f; s->pending_src_set = 1;
    s->pending_dst[0] = -1; s->pending_dst_set = 1;
}

static void viewporter_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void viewporter_get_viewport(struct wl_client *c, struct wl_resource *r, uint32_t id,
                                    struct wl_resource *surface) {
    struct surface *s = wl_resource_get_user_data(surface);
    struct wl_resource *vp = wl_resource_create(c, &wp_viewport_interface, wl_resource_get_version(r), id);
    if (!vp) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(vp, &viewport_impl, s, viewport_resource_destroy);
    if (s) s->viewport = vp;
}
static const struct wp_viewporter_interface viewporter_impl = {
    .destroy = viewporter_destroy,
    .get_viewport = viewporter_get_viewport,
};
static void bind_viewporter(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &wp_viewporter_interface, ver, id);
    wl_resource_set_implementation(r, &viewporter_impl, NULL, NULL);
}

/* ------------------------------------------------------------------ xdg_shell */

static void xdg_toplevel_destroy_req(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void xdg_toplevel_noop_parent(struct wl_client *c, struct wl_resource *r, struct wl_resource *p) {}
static void xdg_toplevel_set_title(struct wl_client *c, struct wl_resource *r, const char *title) {
    struct surface *s = wl_resource_get_user_data(r);
    if (!s) return;
    free(s->title);
    s->title = title ? strdup(title) : NULL;
}
static void xdg_toplevel_set_app_id(struct wl_client *c, struct wl_resource *r, const char *id) {}
static void xdg_toplevel_show_menu(struct wl_client *c, struct wl_resource *r, struct wl_resource *seat,
                                   uint32_t serial, int32_t x, int32_t y) {}
static void xdg_toplevel_move(struct wl_client *c, struct wl_resource *r, struct wl_resource *seat,
                              uint32_t serial) {}
static void xdg_toplevel_resize(struct wl_client *c, struct wl_resource *r, struct wl_resource *seat,
                                uint32_t serial, uint32_t edges) {}
static void xdg_toplevel_set_i32(struct wl_client *c, struct wl_resource *r, int32_t w, int32_t h) {}
static void xdg_toplevel_noop(struct wl_client *c, struct wl_resource *r) {}
static const struct xdg_toplevel_interface xdg_toplevel_impl = {
    .destroy = xdg_toplevel_destroy_req,
    .set_parent = xdg_toplevel_noop_parent,
    .set_title = xdg_toplevel_set_title,
    .set_app_id = xdg_toplevel_set_app_id,
    .show_window_menu = xdg_toplevel_show_menu,
    .move = xdg_toplevel_move,
    .resize = xdg_toplevel_resize,
    .set_max_size = xdg_toplevel_set_i32,
    .set_min_size = xdg_toplevel_set_i32,
    .set_maximized = xdg_toplevel_noop,
    .unset_maximized = xdg_toplevel_noop,
    .set_fullscreen = xdg_toplevel_noop_parent,
    .unset_fullscreen = xdg_toplevel_noop,
    .set_minimized = xdg_toplevel_noop,
};
static void xdg_toplevel_resource_destroy(struct wl_resource *r) {
    struct surface *s = wl_resource_get_user_data(r);
    if (!s) return;
    s->xdg_toplevel = NULL;
    unmap_toplevel(s);
    s->placed = 0;
    s->hwnd = 0;
    schedule_render();
}

static void xdg_surface_destroy_req(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void xdg_surface_get_toplevel(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct surface *s = wl_resource_get_user_data(r);
    struct wl_resource *tl = wl_resource_create(c, &xdg_toplevel_interface, wl_resource_get_version(r), id);
    if (!tl) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(tl, &xdg_toplevel_impl, s, xdg_toplevel_resource_destroy);
    if (s) {
        s->role = ROLE_TOPLEVEL;
        s->xdg_toplevel = tl;
    }
    /* Size 0x0 = the client picks; active. Then the surface configure that applies it. */
    struct wl_array states;
    wl_array_init(&states);
    uint32_t *st = wl_array_add(&states, sizeof(uint32_t));
    *st = XDG_TOPLEVEL_STATE_ACTIVATED;
    xdg_toplevel_send_configure(tl, 0, 0, &states);
    wl_array_release(&states);
    xdg_surface_send_configure(r, wl_display_next_serial(g_display));
}
static void xdg_surface_get_popup(struct wl_client *c, struct wl_resource *r, uint32_t id,
                                  struct wl_resource *parent, struct wl_resource *positioner) {}
static void xdg_surface_set_geometry(struct wl_client *c, struct wl_resource *r,
                                     int32_t x, int32_t y, int32_t w, int32_t h) {}
static void xdg_surface_ack_configure(struct wl_client *c, struct wl_resource *r, uint32_t serial) {}
static const struct xdg_surface_interface xdg_surface_impl = {
    .destroy = xdg_surface_destroy_req,
    .get_toplevel = xdg_surface_get_toplevel,
    .get_popup = xdg_surface_get_popup,
    .set_window_geometry = xdg_surface_set_geometry,
    .ack_configure = xdg_surface_ack_configure,
};
static void xdg_surface_resource_destroy(struct wl_resource *r) {
    struct surface *s = wl_resource_get_user_data(r);
    if (s) s->xdg_surface = NULL;
}

static void xdg_wm_base_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void xdg_wm_base_create_positioner(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct wl_resource *p = wl_resource_create(c, &xdg_positioner_interface, wl_resource_get_version(r), id);
    if (p) wl_resource_set_implementation(p, NULL, NULL, NULL);
}
static void xdg_wm_base_get_xdg_surface(struct wl_client *c, struct wl_resource *r, uint32_t id,
                                        struct wl_resource *surf) {
    struct surface *s = wl_resource_get_user_data(surf);
    struct wl_resource *xs = wl_resource_create(c, &xdg_surface_interface, wl_resource_get_version(r), id);
    if (!xs) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(xs, &xdg_surface_impl, s, xdg_surface_resource_destroy);
    if (s) s->xdg_surface = xs;
}
static void xdg_wm_base_pong(struct wl_client *c, struct wl_resource *r, uint32_t serial) {}
static const struct xdg_wm_base_interface xdg_wm_base_impl = {
    .destroy = xdg_wm_base_destroy,
    .create_positioner = xdg_wm_base_create_positioner,
    .get_xdg_surface = xdg_wm_base_get_xdg_surface,
    .pong = xdg_wm_base_pong,
};
static void bind_xdg_wm_base(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &xdg_wm_base_interface, ver, id);
    wl_resource_set_implementation(r, &xdg_wm_base_impl, NULL, NULL);
}

/* ------------------------------------------------------------------ banner_desktop_v1 */

static void desktop_destroy_req(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void desktop_set_desktop(struct wl_client *c, struct wl_resource *r, struct wl_resource *surface) {
    struct surface *s = wl_resource_get_user_data(surface);
    if (!s || (s->role != ROLE_NONE && s->role != ROLE_DESKTOP)) return;
    s->role = ROLE_DESKTOP;
    g_desktop = s;
    banner_log("desktop", "Windows virtual desktop created by %s", client_name(c));
    schedule_render();
}
static void desktop_set_window(struct wl_client *c, struct wl_resource *r, struct wl_resource *surface,
                               uint32_t hwnd, int32_t x, int32_t y) {
    struct surface *s = wl_resource_get_user_data(surface);
    if (!s) return;
    if (s->placed && (s->x != x || s->y != y) && s->mapped && log_budget()) {
        char name[160];
        describe(s, name, sizeof(name));
        banner_log("window", "moved %s to %d,%d", name, x, y);
    }
    s->hwnd = hwnd;
    s->x = x;
    s->y = y;
    s->placed = 1;
    if (s->mapped) apply_zorder();
    schedule_render();
}
static void desktop_set_zorder(struct wl_client *c, struct wl_resource *r, struct wl_array *hwnds) {
    size_t count = hwnds->size / sizeof(uint32_t);
    uint32_t *copy = count ? malloc(count * sizeof(uint32_t)) : NULL;
    if (count && !copy) return;
    if (count) memcpy(copy, hwnds->data, count * sizeof(uint32_t));
    free(g_zorder);
    g_zorder = copy;
    g_zorder_count = count;
    apply_zorder();
    schedule_render();
}
static const struct banner_desktop_v1_interface desktop_impl = {
    .destroy = desktop_destroy_req,
    .set_desktop = desktop_set_desktop,
    .set_window = desktop_set_window,
    .set_zorder = desktop_set_zorder,
};
static void bind_desktop(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &banner_desktop_v1_interface, ver, id);
    wl_resource_set_implementation(r, &desktop_impl, NULL, NULL);
}

/* ------------------------------------------------------------ zwp_linux_dmabuf_v1 */

static void dbuf_buffer_resource_destroy(struct wl_resource *r) {
    struct dmabuf_buffer *b = wl_resource_get_user_data(r);
    if (!b) return;
    vkp_image_destroy(b->img);
    for (int i = 0; i < b->n_planes; i++)
        if (b->fd[i] >= 0) close(b->fd[i]);
    free(b);
}

static void params_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void params_add(struct wl_client *c, struct wl_resource *r, int32_t fd, uint32_t plane,
                       uint32_t offset, uint32_t stride, uint32_t mod_hi, uint32_t mod_lo) {
    struct dmabuf_params *p = wl_resource_get_user_data(r);
    if (plane >= MAX_PLANES) { close(fd); return; }
    if (p->fd[plane] >= 0) close(p->fd[plane]);
    p->fd[plane] = fd;
    p->offset[plane] = offset;
    p->stride[plane] = stride;
    p->modifier[plane] = ((uint64_t)mod_hi << 32) | mod_lo;
    if ((int)plane + 1 > p->n_planes) p->n_planes = plane + 1;
}
static struct wl_resource *params_do_create(struct wl_client *c, struct wl_resource *r, uint32_t id,
                                            int32_t w, int32_t h, uint32_t format, uint32_t flags) {
    struct dmabuf_params *p = wl_resource_get_user_data(r);
    struct dmabuf_buffer *b = calloc(1, sizeof(*b));
    if (!b) return NULL;
    b->n_planes = p->n_planes;
    b->width = w; b->height = h; b->format = format;
    b->modifier = p->modifier[0];
    for (int i = 0; i < MAX_PLANES; i++) b->fd[i] = -1;
    for (int i = 0; i < p->n_planes; i++) {
        b->fd[i] = p->fd[i];
        b->offset[i] = p->offset[i];
        b->stride[i] = p->stride[i];
        p->fd[i] = -1; /* ownership moves to the buffer */
    }
    struct wl_resource *buf = wl_resource_create(c, &wl_buffer_interface, 1, id);
    if (!buf) {
        for (int i = 0; i < b->n_planes; i++) if (b->fd[i] >= 0) close(b->fd[i]);
        free(b);
        wl_client_post_no_memory(c);
        return NULL;
    }
    wl_resource_set_implementation(buf, &dbuf_buffer_impl, b, dbuf_buffer_resource_destroy);
    return buf;
}
static void params_create(struct wl_client *c, struct wl_resource *r, int32_t w, int32_t h,
                          uint32_t format, uint32_t flags) {
    struct wl_resource *buf = params_do_create(c, r, 0, w, h, format, flags);
    if (buf) zwp_linux_buffer_params_v1_send_created(r, buf);
    else zwp_linux_buffer_params_v1_send_failed(r);
}
static void params_create_immed(struct wl_client *c, struct wl_resource *r, uint32_t buffer_id,
                                int32_t w, int32_t h, uint32_t format, uint32_t flags) {
    params_do_create(c, r, buffer_id, w, h, format, flags);
}
static const struct zwp_linux_buffer_params_v1_interface params_impl = {
    .destroy = params_destroy,
    .add = params_add,
    .create = params_create,
    .create_immed = params_create_immed,
};
static void params_resource_destroy(struct wl_resource *r) {
    struct dmabuf_params *p = wl_resource_get_user_data(r);
    if (!p) return;
    for (int i = 0; i < MAX_PLANES; i++)
        if (p->fd[i] >= 0) close(p->fd[i]);
    free(p);
}

static void dmabuf_destroy(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static void dmabuf_create_params(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct dmabuf_params *p = calloc(1, sizeof(*p));
    if (!p) { wl_client_post_no_memory(c); return; }
    for (int i = 0; i < MAX_PLANES; i++) p->fd[i] = -1;
    struct wl_resource *pr = wl_resource_create(c, &zwp_linux_buffer_params_v1_interface,
                                                wl_resource_get_version(r), id);
    if (!pr) { free(p); wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(pr, &params_impl, p, params_resource_destroy);
}
static const struct zwp_linux_dmabuf_v1_interface dmabuf_impl = {
    .destroy = dmabuf_destroy,
    .create_params = dmabuf_create_params,
};
static void bind_dmabuf(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &zwp_linux_dmabuf_v1_interface, ver, id);
    wl_resource_set_implementation(r, &dmabuf_impl, NULL, NULL);
    uint32_t fmts[] = {DRM_ARGB8888, DRM_XRGB8888, DRM_ABGR8888, DRM_XBGR8888};
    uint64_t mods[] = {MOD_LINEAR, MOD_INVALID};
    for (unsigned f = 0; f < 4; f++) {
        zwp_linux_dmabuf_v1_send_format(r, fmts[f]);
        if (ver >= 3)
            for (unsigned m = 0; m < 2; m++)
                zwp_linux_dmabuf_v1_send_modifier(r, fmts[f], (uint32_t)(mods[m] >> 32),
                                                  (uint32_t)(mods[m] & 0xffffffff));
    }
}

/* ------------------------------------------------------------------ wl_output */

static void bind_output(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &wl_output_interface, ver, id);
    wl_resource_set_implementation(r, NULL, NULL, NULL);
    wl_output_send_geometry(r, 0, 0, 340, 190, WL_OUTPUT_SUBPIXEL_UNKNOWN,
                            "Bannerlator", "Wayland", WL_OUTPUT_TRANSFORM_NORMAL);
    wl_output_send_mode(r, WL_OUTPUT_MODE_CURRENT | WL_OUTPUT_MODE_PREFERRED,
                        g_output_w > 0 ? g_output_w : 1920, g_output_h > 0 ? g_output_h : 1080,
                        g_output_refresh_mhz > 0 ? g_output_refresh_mhz : 60000);
    if (ver >= 2) {
        wl_output_send_scale(r, 1);
        wl_output_send_done(r);
    }
}

/* ------------------------------------------------------------------ rendering */

struct draw_list { struct vkp_draw *d; int n, cap; };

static struct vkp_image *surface_image(struct surface *s) {
    if (!s->has_content) return NULL;
    if (s->shm_img) return s->shm_img;
    struct dmabuf_buffer *b = get_dmabuf(s->dmabuf);
    return b ? b->img : NULL;
}

static void add_surface(struct draw_list *dl, struct surface *s, int ox, int oy) {
    struct vkp_image *img = surface_image(s);
    float sx = 0, sy = 0, sw = (float)s->buf_w, sh = (float)s->buf_h;
    int dw, dh;

    if (!img) return;
    if (s->src_set) { sx = s->src[0]; sy = s->src[1]; sw = s->src[2]; sh = s->src[3]; }
    surface_size(s, &dw, &dh);
    if (dw <= 0 || dh <= 0 || sw <= 0 || sh <= 0) return;
    if (dl->n == dl->cap) {
        int cap = dl->cap ? dl->cap * 2 : 32;
        struct vkp_draw *d = realloc(dl->d, (size_t)cap * sizeof(*d));
        if (!d) return;
        dl->d = d;
        dl->cap = cap;
    }
    dl->d[dl->n++] = (struct vkp_draw){img, sx, sy, sw, sh, ox, oy, dw, dh};
    s->drawn = 1;
}

static void add_tree(struct draw_list *dl, struct surface *s, int ox, int oy, int depth) {
    struct surface *c;
    if (depth > 8) return;
    wl_list_for_each(c, &s->children, child_link)
        if (c->below_parent) add_tree(dl, c, ox + c->sub_x, oy + c->sub_y, depth + 1);
    add_surface(dl, s, ox, oy);
    wl_list_for_each(c, &s->children, child_link)
        if (!c->below_parent) add_tree(dl, c, ox + c->sub_x, oy + c->sub_y, depth + 1);
}

/* Scene size: the desktop's, or without one the largest window's (a single
 * fullscreen game then fills the screen exactly as before). */
static void scene_size(int *w, int *h) {
    struct surface *s;
    long long best = 0;

    if (g_desktop && g_desktop->has_content) {
        surface_size(g_desktop, w, h);
        g_desktop_w = *w;
        g_desktop_h = *h;
        return;
    }
    if (g_desktop && g_desktop_w > 0) { *w = g_desktop_w; *h = g_desktop_h; return; }
    *w = *h = 0;
    wl_list_for_each(s, &g_toplevels, toplevel_link) {
        int sw, sh;
        surface_size(s, &sw, &sh);
        if ((long long)sw * sh > best) { best = (long long)sw * sh; *w = sw; *h = sh; }
    }
    if (*w <= 0 || *h <= 0) { *w = INPUT_SPACE_W; *h = INPUT_SPACE_H; }
}

static void fire_all_frames(void) {
    struct surface *s;
    wl_list_for_each(s, &g_surfaces, link) fire_frames(&s->frames);
}

static int on_frame_timer(void *data) {
    fire_all_frames();
    return 0;
}

static void render_scene(void) {
    struct draw_list dl = {0};
    struct surface *s;
    int w, h;

    g_dirty = 0;
    wl_list_for_each(s, &g_surfaces, link) s->drawn = 0;
    scene_size(&w, &h);
    if (w != g_scene_w || h != g_scene_h) {
        if (g_desktop) banner_log("desktop", "size %dx%d", w, h);
        else banner_log("desktop", "no desktop: showing %dx%d (largest window)", w, h);
        g_scene_w = w;
        g_scene_h = h;
    }
    if (g_desktop && !g_hide_shell) add_tree(&dl, g_desktop, 0, 0, 0);
    wl_list_for_each(s, &g_toplevels, toplevel_link) {
        if (g_desktop && !s->placed) continue; /* wait for its position */
        if (g_hide_shell && !strcmp(client_name(wl_resource_get_client(s->resource)), "explorer.exe")) continue;
        add_tree(&dl, s, s->placed ? s->x : 0, s->placed ? s->y : 0, 0);
    }

    if (vkp_render(w, h, dl.d, dl.n) == 0) {
        int64_t t = now_ns();
        g_stat_frames++;
        wl_list_for_each(s, &g_surfaces, link)
            if (s->drawn) feedback_present_all(&s->feedback, t);
        fire_all_frames();
    } else {
        /* No output surface yet (or it went away): keep clients paced without it. */
        if (!g_frame_timer)
            g_frame_timer = wl_event_loop_add_timer(wl_display_get_event_loop(g_display),
                                                    on_frame_timer, NULL);
        if (g_frame_timer) wl_event_source_timer_update(g_frame_timer, 16);
    }
    free(dl.d);
    wl_display_flush_clients(g_display);
}

static int on_fallback_timer(void *data) {
    g_fallback_armed = 0;
    if (g_dirty) render_scene();
    return 0;
}

static void schedule_render(void) {
    g_dirty = 1;
    if (!g_display) return;
    /* With vsync ticks flowing the next tick renders; otherwise render on a timer. */
    if (g_last_vsync_ns && now_ns() - g_last_vsync_ns < 100000000LL) return;
    if (g_fallback_armed) return;
    if (!g_fallback_timer)
        g_fallback_timer = wl_event_loop_add_timer(wl_display_get_event_loop(g_display),
                                                   on_fallback_timer, NULL);
    if (!g_fallback_timer) return;
    g_fallback_armed = 1;
    wl_event_source_timer_update(g_fallback_timer, 8);
}

/* A screen refresh (Choreographer tick): draw the newest state once. */
static void on_vsync(int64_t frame_time_ns) {
    int64_t now = now_ns();
    if (g_last_vsync_ns) {
        int64_t d = now - g_last_vsync_ns;
        if (d > 3000000LL && d < 40000000LL) g_refresh_ns = (g_refresh_ns * 7 + d) / 8;
    }
    g_last_vsync_ns = now;
    (void)frame_time_ns;
    if (g_dirty) render_scene();
}

/* ------------------------------------------------------------------ wl_seat */

static uint32_t now_ms(void) {
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint32_t)(ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
}

static void pointer_set_cursor(struct wl_client *c, struct wl_resource *r, uint32_t serial,
                               struct wl_resource *surface, int32_t hx, int32_t hy) {
    /* The app draws its own pointer; cursor surfaces stay role-less and aren't drawn. */
}
static void pointer_release(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static const struct wl_pointer_interface pointer_impl = {
    .set_cursor = pointer_set_cursor, .release = pointer_release,
};
static void pointer_res_destroy(struct wl_resource *r) {
    for (int i = 0; i < g_nptrs; i++)
        if (g_ptrs[i].ptr == r) { g_ptrs[i] = g_ptrs[--g_nptrs]; break; }
}

static void keyboard_release(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static const struct wl_keyboard_interface keyboard_impl = { .release = keyboard_release };
static void keyboard_res_destroy(struct wl_resource *r) {
    for (int i = 0; i < g_nkbs; i++)
        if (g_kbs[i].kb == r) { g_kbs[i] = g_kbs[--g_nkbs]; break; }
}
static void touch_release(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static const struct wl_touch_interface touch_impl = { .release = touch_release };

static void seat_get_pointer(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct wl_resource *p = wl_resource_create(c, &wl_pointer_interface, wl_resource_get_version(r), id);
    if (!p) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(p, &pointer_impl, NULL, pointer_res_destroy);
    if (g_nptrs < MAX_PTRS) { g_ptrs[g_nptrs].ptr = p; g_ptrs[g_nptrs].focus = NULL; g_nptrs++; }
}
static void seat_get_keyboard(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct wl_resource *k = wl_resource_create(c, &wl_keyboard_interface, wl_resource_get_version(r), id);
    if (!k) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(k, &keyboard_impl, NULL, keyboard_res_destroy);
    if (g_nkbs < MAX_PTRS) { g_kbs[g_nkbs].kb = k; g_kbs[g_nkbs].focus = NULL; g_nkbs++; }
    /* Send the xkb keymap the app extracted to $XDG_RUNTIME_DIR/keymap.xkb. Without a keymap the
     * client can't interpret our evdev key codes. */
    const char *rt = getenv("XDG_RUNTIME_DIR");
    char path[512];
    snprintf(path, sizeof(path), "%s/keymap.xkb", rt ? rt : ".");
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        struct stat st;
        if (fstat(fd, &st) == 0 && st.st_size > 0)
            wl_keyboard_send_keymap(k, WL_KEYBOARD_KEYMAP_FORMAT_XKB_V1, fd, (uint32_t)st.st_size);
        close(fd);
    } else {
        WLOGE("keymap open failed: %s", path);
    }
    if (wl_resource_get_version(k) >= WL_KEYBOARD_REPEAT_INFO_SINCE_VERSION)
        wl_keyboard_send_repeat_info(k, 25, 500);
}
static void seat_get_touch(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct wl_resource *t = wl_resource_create(c, &wl_touch_interface, wl_resource_get_version(r), id);
    if (t) wl_resource_set_implementation(t, &touch_impl, NULL, NULL);
}
static void seat_release(struct wl_client *c, struct wl_resource *r) { wl_resource_destroy(r); }
static const struct wl_seat_interface seat_impl = {
    .get_pointer = seat_get_pointer, .get_keyboard = seat_get_keyboard,
    .get_touch = seat_get_touch, .release = seat_release,
};
static void bind_seat(struct wl_client *c, void *data, uint32_t ver, uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &wl_seat_interface, ver, id);
    wl_resource_set_implementation(r, &seat_impl, NULL, NULL);
    wl_seat_send_capabilities(r, WL_SEAT_CAPABILITY_POINTER | WL_SEAT_CAPABILITY_KEYBOARD);
    if (ver >= 2) wl_seat_send_name(r, "bannerlator-seat");
}

static struct seat_pointer *pointer_for(struct wl_client *client) {
    for (int i = 0; i < g_nptrs; i++)
        if (wl_resource_get_client(g_ptrs[i].ptr) == client) return &g_ptrs[i];
    return NULL;
}
static struct seat_keyboard *keyboard_for(struct wl_client *client) {
    for (int i = 0; i < g_nkbs; i++)
        if (wl_resource_get_client(g_kbs[i].kb) == client) return &g_kbs[i];
    return NULL;
}

/* Topmost window whose scene rectangle contains the point (no-desktop fallback). */
static struct surface *toplevel_at(double x, double y) {
    struct surface *s;
    wl_list_for_each_reverse(s, &g_toplevels, toplevel_link) {
        int w, h, sx = s->placed ? s->x : 0, sy = s->placed ? s->y : 0;
        surface_size(s, &w, &h);
        if (x >= sx && y >= sy && x < sx + w && y < sy + h) return s;
    }
    return NULL;
}

static void pointer_focus(struct wl_resource *target, wl_fixed_t fx, wl_fixed_t fy) {
    struct wl_client *client = wl_resource_get_client(target);
    for (int i = 0; i < g_nptrs; i++) {
        if (!g_ptrs[i].focus || g_ptrs[i].focus == target) continue;
        wl_pointer_send_leave(g_ptrs[i].ptr, wl_display_next_serial(g_display), g_ptrs[i].focus);
        if (wl_resource_get_version(g_ptrs[i].ptr) >= WL_POINTER_FRAME_SINCE_VERSION)
            wl_pointer_send_frame(g_ptrs[i].ptr);
        g_ptrs[i].focus = NULL;
    }
    struct seat_pointer *sp = pointer_for(client);
    if (sp && sp->focus != target) {
        sp->focus = target;
        wl_pointer_send_enter(sp->ptr, wl_display_next_serial(g_display), target, fx, fy);
    }
}

static void keyboard_focus(struct wl_resource *target) {
    struct seat_keyboard *sk = keyboard_for(wl_resource_get_client(target));
    if (!sk || sk->focus == target) return;
    for (int i = 0; i < g_nkbs; i++) {
        if (!g_kbs[i].focus || &g_kbs[i] == sk) continue;
        wl_keyboard_send_leave(g_kbs[i].kb, wl_display_next_serial(g_display), g_kbs[i].focus);
        g_kbs[i].focus = NULL;
    }
    struct wl_array keys;
    wl_array_init(&keys);
    if (sk->focus) wl_keyboard_send_leave(sk->kb, wl_display_next_serial(g_display), sk->focus);
    sk->focus = target;
    wl_keyboard_send_enter(sk->kb, wl_display_next_serial(g_display), target, &keys);
    /* Baseline modifiers = none; Shift/Ctrl arrive as their own key events. */
    wl_keyboard_send_modifiers(sk->kb, wl_display_next_serial(g_display), 0, 0, 0, 0);
    wl_array_release(&keys);
}

/* Last pointer position in scene coordinates (buttons and scrolls from the app's X-server
 * input path arrive without one). */
static double g_ptr_x, g_ptr_y;

/* One pointer event at scene coordinates: motion, then an optional button change
 * (button 0 = none). */
static void pointer_event(double x, double y, uint32_t button, int pressed) {
    struct surface *target;

    g_ptr_x = x; g_ptr_y = y;

    if (g_desktop) {
        target = g_desktop;
    } else {
        target = g_grab ? g_grab : toplevel_at(x, y);
        if (button && pressed) { g_grab = target; g_key_target = target; }
        if (button && !pressed) g_grab = NULL;
    }
    if (!target) return;

    struct seat_pointer *sp = pointer_for(wl_resource_get_client(target->resource));
    if (!sp) return;
    int tx = 0, ty = 0;
    if (target != g_desktop && target->placed) { tx = target->x; ty = target->y; }
    wl_fixed_t fx = wl_fixed_from_double(x - tx), fy = wl_fixed_from_double(y - ty);
    uint32_t t = now_ms();

    pointer_focus(target->resource, fx, fy);
    wl_pointer_send_motion(sp->ptr, t, fx, fy);
    if (button)
        wl_pointer_send_button(sp->ptr, wl_display_next_serial(g_display), t, button,
                               pressed ? WL_POINTER_BUTTON_STATE_PRESSED : WL_POINTER_BUTTON_STATE_RELEASED);
    if (wl_resource_get_version(sp->ptr) >= WL_POINTER_FRAME_SINCE_VERSION)
        wl_pointer_send_frame(sp->ptr);
    wl_display_flush_clients(g_display);
}

/* Java touch events arrive in INPUT_SPACE over the whole output: action 0=press, 1=move, 2=release. */
static void deliver_pointer(const struct input_msg *m) {
    int w, h;
    scene_size(&w, &h);
    pointer_event((double)m->p2 * w / INPUT_SPACE_W, (double)m->p3 * h / INPUT_SPACE_H,
                  m->p1 == 1 ? 0 : BTN_LEFT, m->p1 == 0);
}

static void key_event(uint32_t evdev, int pressed);
static void deliver_key(const struct input_msg *m) {
    key_event((uint32_t)m->p1, m->p2);
}

/* Vertical wheel steps (negative = up) at the current pointer position. */
static void scroll_event(int steps) {
    struct surface *target = g_desktop ? g_desktop : (g_grab ? g_grab : toplevel_at(g_ptr_x, g_ptr_y));
    if (!target || !steps) return;
    struct seat_pointer *sp = pointer_for(wl_resource_get_client(target->resource));
    if (!sp) return;
    int tx = 0, ty = 0;
    if (target != g_desktop && target->placed) { tx = target->x; ty = target->y; }
    uint32_t t = now_ms();
    pointer_focus(target->resource, wl_fixed_from_double(g_ptr_x - tx), wl_fixed_from_double(g_ptr_y - ty));
    if (wl_resource_get_version(sp->ptr) >= WL_POINTER_AXIS_DISCRETE_SINCE_VERSION)
        wl_pointer_send_axis_discrete(sp->ptr, WL_POINTER_AXIS_VERTICAL_SCROLL, steps);
    wl_pointer_send_axis(sp->ptr, t, WL_POINTER_AXIS_VERTICAL_SCROLL, wl_fixed_from_int(steps * 10));
    if (wl_resource_get_version(sp->ptr) >= WL_POINTER_FRAME_SINCE_VERSION)
        wl_pointer_send_frame(sp->ptr);
    wl_display_flush_clients(g_display);
}

static void key_event(uint32_t evdev, int pressed) {
    struct surface *target = g_desktop ? g_desktop : g_key_target;
    if (!target) {
        struct surface *s;
        wl_list_for_each_reverse(s, &g_toplevels, toplevel_link) { target = s; break; }
    }
    if (!target) return;
    struct seat_keyboard *sk = keyboard_for(wl_resource_get_client(target->resource));
    if (!sk) return;
    keyboard_focus(target->resource);
    wl_keyboard_send_key(sk->kb, wl_display_next_serial(g_display), now_ms(), evdev,
                         pressed ? WL_KEYBOARD_KEY_STATE_PRESSED : WL_KEYBOARD_KEY_STATE_RELEASED);
    wl_display_flush_clients(g_display);
}

/* wl event-loop callback: drain queued input events written by the Android UI thread. */
static int on_input_readable(int fd, uint32_t mask, void *data) {
    struct input_msg m;
    while (read(fd, &m, sizeof(m)) == (ssize_t)sizeof(m)) {
        switch (m.type) {
        case 1: deliver_key(&m); break;
        case 2: pointer_event(m.p1, m.p2, 0, 0); break;          /* scene motion */
        case 3: pointer_event(g_ptr_x, g_ptr_y, m.p1, m.p2); break; /* button at pointer */
        case 4: scroll_event(m.p1); break;
        case 5: on_vsync(((int64_t)m.p1 << 32) | (uint32_t)m.p2); break;
        default: deliver_pointer(&m); break;
        }
    }
    return 0;
}

/* Called from JNI (Android UI thread). Queues a pointer event; the compositor thread
 * dispatches it. x/y are in INPUT_SPACE (0..1919, 0..1079) over the whole output. */
void banner_wayland_send_pointer(int action, int x, int y) {
    if (g_input_pipe[1] < 0) return;
    struct input_msg m = { 0, action, x, y };
    ssize_t n = write(g_input_pipe[1], &m, sizeof(m));
    (void)n;
}

/* Called from JNI. Queues a key event. evdev = Linux input keycode (e.g. KEY_A=30); state 1=down 0=up. */
void banner_wayland_send_key(int evdev, int state) {
    if (g_input_pipe[1] < 0) return;
    struct input_msg m = { 1, evdev, state, 0 };
    ssize_t n = write(g_input_pipe[1], &m, sizeof(m));
    (void)n;
}

/* Called from JNI on every screen refresh (Choreographer). */
void banner_wayland_vsync(int64_t frame_time_ns) {
    if (g_input_pipe[1] < 0) return;
    struct input_msg m = { 5, (int)(frame_time_ns >> 32), (int)(uint32_t)frame_time_ns, 0 };
    ssize_t n = write(g_input_pipe[1], &m, sizeof(m));
    (void)n;
}

/* Called from JNI with the app's X-server input (on-screen controls, mouse): type 2 = motion
 * to scene x,y; 3 = evdev button a pressed/released (b); 4 = a wheel steps (negative = up). */
void banner_wayland_send_scene_input(int type, int a, int b) {
    if (g_input_pipe[1] < 0) return;
    struct input_msg m = { type, a, b, 0 };
    ssize_t n = write(g_input_pipe[1], &m, sizeof(m));
    (void)n;
}

/* ------------------------------------------------------------------ 10 s summary */

static struct wl_event_source *g_stats_timer;

static int on_stats_timer(void *data) {
    int windows = 0;
    struct surface *s;
    wl_list_for_each(s, &g_toplevels, toplevel_link) windows++;
    if (g_stat_frames || g_stat_dmabuf || g_stat_shm)
        banner_log("stats", "last 10 s: %u frames on screen (%.1f fps) | %u GPU frames from games | %u window redraws | %d windows open",
                   g_stat_frames, g_stat_frames / 10.0, g_stat_dmabuf, g_stat_shm, windows);
    g_stat_frames = g_stat_dmabuf = g_stat_shm = 0;
    wl_event_source_timer_update(g_stats_timer, 10000);
    return 0;
}

/* ------------------------------------------------------------------ test input
 * $XDG_RUNTIME_DIR/test-input is a FIFO for scripted testing from a root shell, in scene
 * (desktop) coordinates: "move X Y", "click X Y", "rclick X Y", "dclick X Y", "down X Y",
 * "up X Y", "rdown X Y", "rup X Y", "key CODE" (evdev), "keydown CODE", "keyup CODE".
 * It lives in the app's private directory, so only the app and root can reach it. */

static char g_test_buf[512];
static size_t g_test_len;

static void test_input_line(const char *line) {
    double x, y;
    unsigned code;
    char cmd[16];
    int n = sscanf(line, "%15s %lf %lf", cmd, &x, &y);

    if (n < 2) return;
    banner_log("test", "input: %s", line);
    if (!strcmp(cmd, "key") || !strcmp(cmd, "keydown") || !strcmp(cmd, "keyup")) {
        code = (unsigned)x;
        if (strcmp(cmd, "keyup")) key_event(code, 1);
        if (strcmp(cmd, "keydown")) key_event(code, 0);
        return;
    }
    if (n < 3) return;
    if (!strcmp(cmd, "move")) pointer_event(x, y, 0, 0);
    else if (!strcmp(cmd, "down")) pointer_event(x, y, BTN_LEFT, 1);
    else if (!strcmp(cmd, "up")) pointer_event(x, y, BTN_LEFT, 0);
    else if (!strcmp(cmd, "rdown")) pointer_event(x, y, BTN_RIGHT, 1);
    else if (!strcmp(cmd, "rup")) pointer_event(x, y, BTN_RIGHT, 0);
    else if (!strcmp(cmd, "click") || !strcmp(cmd, "rclick") || !strcmp(cmd, "dclick")) {
        uint32_t button = cmd[0] == 'r' ? BTN_RIGHT : BTN_LEFT;
        int times = cmd[0] == 'd' ? 2 : 1;
        pointer_event(x, y, 0, 0);
        for (int i = 0; i < times; i++) {
            pointer_event(x, y, button, 1);
            pointer_event(x, y, button, 0);
        }
    }
}

static int on_test_input(int fd, uint32_t mask, void *data) {
    ssize_t r;
    while ((r = read(fd, g_test_buf + g_test_len, sizeof(g_test_buf) - 1 - g_test_len)) > 0) {
        char *start = g_test_buf, *nl;
        g_test_len += (size_t)r;
        g_test_buf[g_test_len] = 0;
        while ((nl = strchr(start, '\n'))) {
            *nl = 0;
            test_input_line(start);
            start = nl + 1;
        }
        g_test_len = strlen(start);
        memmove(g_test_buf, start, g_test_len);
        if (g_test_len >= sizeof(g_test_buf) - 1) g_test_len = 0; /* overlong line: drop */
    }
    return 0;
}

static void start_test_input(struct wl_event_loop *loop) {
    const char *rt = getenv("XDG_RUNTIME_DIR");
    char path[512];
    int fd;

    if (!rt || !*rt) return;
    snprintf(path, sizeof(path), "%s/test-input", rt);
    unlink(path);
    if (mkfifo(path, 0600) != 0) return;
    /* O_RDWR keeps a writer open ourselves, so a closing test shell never leaves us at EOF. */
    if ((fd = open(path, O_RDWR | O_NONBLOCK | O_CLOEXEC)) < 0) return;
    wl_event_loop_add_fd(loop, fd, WL_EVENT_READABLE, on_test_input, NULL);
}

/* ------------------------------------------------------------------ entry
 * Blocks in the wl event loop, so the JNI wrapper runs it on a dedicated thread.
 * XDG_RUNTIME_DIR must be set by the caller before this runs. */

static struct wl_listener g_client_created = { .notify = on_client_created };

int banner_wayland_run(void) {
    wl_list_init(&g_surfaces);
    wl_list_init(&g_toplevels);
    open_session_log();

    /* Bring the GPU up before any client can connect: loading Turnip the first time
     * can take many seconds, and it must not stall a client mid-handshake. */
    if (vkp_ready() != 0) WLOGE("renderer unavailable; clients will connect but nothing is drawn");

    struct wl_display *display = wl_display_create();
    if (!display) {
        WLOGE("wl_display_create failed");
        return 1;
    }

    /* Use a FIXED socket name so it always matches the guest's WAYLAND_DISPLAY=wayland-0
     * (wl_display_add_socket_auto would drift to wayland-1/2/… if a stale socket exists,
     * and the guest only ever looks for wayland-0). Unlink any stale socket+lock first so
     * a previous run that didn't clean up can't block the bind. */
    const char *rt = getenv("XDG_RUNTIME_DIR");
    if (rt && *rt) {
        char p[512];
        snprintf(p, sizeof(p), "%s/wayland-0", rt);      unlink(p);
        snprintf(p, sizeof(p), "%s/wayland-0.lock", rt); unlink(p);
    }
    if (wl_display_add_socket(display, "wayland-0") != 0) {
        WLOGE("add_socket(wayland-0) failed in XDG_RUNTIME_DIR=%s (errno path/perms?)",
              rt ? rt : "(null)");
        return 1;
    }
    banner_log("display", "Wayland compositor listening on %s/wayland-0", rt ? rt : "?");
    wl_display_add_client_created_listener(display, &g_client_created);

    wl_global_create(display, &wl_compositor_interface, 6, NULL, bind_compositor);
    wl_global_create(display, &wl_subcompositor_interface, 1, NULL, bind_subcompositor);
    wl_global_create(display, &wp_viewporter_interface, 1, NULL, bind_viewporter);
    wl_display_init_shm(display); /* wl_shm global + pool/buffer handling */
    wl_global_create(display, &wl_output_interface, 2, NULL, bind_output);
    wl_global_create(display, &xdg_wm_base_interface, 1, NULL, bind_xdg_wm_base);
    wl_global_create(display, &zwp_linux_dmabuf_v1_interface, 3, NULL, bind_dmabuf);
    wl_global_create(display, &wl_seat_interface, 5, NULL, bind_seat);
    wl_global_create(display, &banner_desktop_v1_interface, 1, NULL, bind_desktop);
    wl_global_create(display, &wp_presentation_interface, 2, NULL, bind_presentation);
    wl_list_init(&g_pending_releases);
    g_release_timer_fd = timerfd_create(CLOCK_MONOTONIC, TFD_NONBLOCK | TFD_CLOEXEC);
    if (g_release_timer_fd >= 0)
        g_release_source = wl_event_loop_add_fd(wl_display_get_event_loop(display), g_release_timer_fd,
                                                WL_EVENT_READABLE, on_release_timer, NULL);

    /* Input injection: the Android UI thread writes events to g_input_pipe[1];
     * the wl event loop drains them on this thread. */
    g_display = display;
    if (pipe2(g_input_pipe, O_CLOEXEC | O_NONBLOCK) == 0) {
        wl_event_loop_add_fd(wl_display_get_event_loop(display), g_input_pipe[0],
                             WL_EVENT_READABLE, on_input_readable, NULL);
    } else {
        WLOGE("input pipe creation failed");
    }
    start_test_input(wl_display_get_event_loop(display));
    if ((g_stats_timer = wl_event_loop_add_timer(wl_display_get_event_loop(display), on_stats_timer, NULL)))
        wl_event_source_timer_update(g_stats_timer, 10000);

    wl_display_run(display); /* blocks, dispatches the event loop */

    wl_display_destroy(display);
    return 0;
}
