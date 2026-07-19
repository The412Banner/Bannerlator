/*
 * bannerlator-wayland — milestone 1 compositor
 *
 * Goal: prove the SERVER HALF on-device. A real Wayland client (eventually
 * winewayland.drv) must be able to: connect, bind our globals, create a
 * surface, run the xdg-shell configure handshake, attach a buffer, and commit
 * — and we observe the commit. No rendering yet (that's milestone 2); attached
 * buffers are just logged and released so the client keeps producing frames.
 *
 * Globals advertised: wl_compositor, wl_subcompositor, wp_viewporter, wl_shm
 * (via wl_display_init_shm), wl_output, xdg_wm_base, zwp_linux_dmabuf_v1.
 * These are winewayland.drv's full required set (wl_compositor, xdg_wm_base,
 * wl_shm, wl_subcompositor, wp_viewporter); the rest it treats as optional.
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
#include <android/log.h>
#include <wayland-server.h>

#define WLOGI(...) __android_log_print(ANDROID_LOG_INFO, "BannerWayland", __VA_ARGS__)
#define WLOGE(...) __android_log_print(ANDROID_LOG_ERROR, "BannerWayland", __VA_ARGS__)

#ifndef BTN_LEFT
#define BTN_LEFT 0x110  /* linux/input-event-codes.h */
#endif

/* --- input state (wl_seat pointer). Touch events arrive from the Android SurfaceView on the
 * UI thread and are injected into the compositor thread via g_input_pipe so all wl_pointer
 * sends happen on the wl event-loop thread (libwayland is not thread-safe). --- */
static struct wl_display *g_display;
/* Each Wine process is a SEPARATE wayland client with its own wl_pointer, so we track one
 * per client and route events to the pointer whose client owns the visible surface. */
struct seat_pointer { struct wl_resource *ptr; struct wl_resource *focus; };
struct seat_keyboard { struct wl_resource *kb; struct wl_resource *focus; };
#define MAX_PTRS 16
static struct seat_pointer g_ptrs[MAX_PTRS];
static int g_nptrs;
static struct seat_keyboard g_kbs[MAX_PTRS];
static int g_nkbs;
static struct wl_resource *g_visible_surface; /* last surface committed with a buffer = what's on screen */
/* Real buffer size of g_visible_surface. We blit it STRETCHED to the fullscreen (1920x1080) output,
 * so incoming pointer coords (output space) must be scaled back to surface-local space by this ratio
 * or clicks on any non-fullscreen window (e.g. the file manager) land outside the real surface. */
static int g_vis_w = 1920, g_vis_h = 1080;
/* Area (px) of the current visible surface's buffer. We present the LARGEST presentable surface,
 * not the last one committed: winewayland in /desktop mode spawns tiny taskbar/helper toplevels
 * (e.g. 119x34) that would otherwise steal the fullscreen blit from the actual game window. */
static long long g_vis_area = 0;
static int g_input_pipe[2] = {-1, -1};
/* type 0 = pointer (p1=action 0down/1move/2up, p2=x, p3=y); type 1 = key (p1=evdev, p2=state 1down/0up) */
struct input_msg { int type; int p1; int p2; int p3; };
#include "xdg-shell-server-protocol.h"
#include "linux-dmabuf-v1-server-protocol.h"
#include "viewporter-server-protocol.h"
#include "vk_present.h"

/* Defined in the dmabuf section below; presents a committed dmabuf frame. */
static void present_committed_buffer(struct wl_resource *buffer);

/* ------------------------------------------------------------------ wl_surface */

struct surface {
    struct wl_resource *resource;
    struct wl_resource *pending_buffer; /* buffer from the most recent attach */
    struct wl_resource *xdg_surface;    /* set once role is assigned */
    int configured;
    int presentable; /* has a window role (xdg_surface or subsurface); a cursor/plain surface = 0 */
};

static void surface_destroy(struct wl_client *c, struct wl_resource *r) {
    wl_resource_destroy(r);
}
static void surface_attach(struct wl_client *c, struct wl_resource *r,
                           struct wl_resource *buffer, int32_t x, int32_t y) {
    struct surface *s = wl_resource_get_user_data(r);
    s->pending_buffer = buffer;
    fprintf(stderr, "[srv] surface.attach buffer=%p (%d,%d)\n", (void *)buffer, x, y);
}
static void surface_damage(struct wl_client *c, struct wl_resource *r,
                           int32_t x, int32_t y, int32_t w, int32_t h) {}
static void surface_frame(struct wl_client *c, struct wl_resource *r, uint32_t cb) {
    /* Fake vsync: immediately signal the frame callback so a real client keeps
     * rendering. Milestone 2 will pace this off the Android display. */
    struct wl_resource *callback =
        wl_resource_create(c, &wl_callback_interface, 1, cb);
    wl_callback_send_done(callback, 0);
    wl_resource_destroy(callback);
}
static void surface_set_opaque(struct wl_client *c, struct wl_resource *r,
                               struct wl_resource *region) {}
static void surface_set_input(struct wl_client *c, struct wl_resource *r,
                              struct wl_resource *region) {}
/* Pixel area of a committed buffer (dmabuf or wl_shm); used to pick the largest surface to present.
 * Defined below, after struct dmabuf_buffer / dbuf_buffer_impl are complete. */
static long long buffer_area(struct wl_resource *buffer);

static void surface_commit(struct wl_client *c, struct wl_resource *r) {
    struct surface *s = wl_resource_get_user_data(r);
    if (s->pending_buffer) {
        /* Only present real WINDOW surfaces (xdg_surface or subsurface role). The mouse cursor is a
         * plain wl_surface with no role, committed BEFORE wl_pointer.set_cursor is called (see Wine's
         * wayland_pointer.c) — so we can't identify it by set_cursor in time. Gating on a window role
         * skips it regardless: blitting a 24x24 cursor fullscreen would stretch it over the whole
         * screen. A real small cursor overlay is a later step. */
        if (!s->presentable) {
            wl_buffer_send_release(s->pending_buffer);
            s->pending_buffer = NULL;
            return;
        }
        /* Composite the frame to the output window (dmabuf path), then release so
         * the client can reuse the buffer. present + queue-wait completes the read
         * before release, so immediate release is safe. */
        /* Present only the LARGEST presentable surface (the game), so tiny taskbar/helper toplevels
         * from /desktop mode (119x34 etc.) don't steal the fullscreen blit. The current visible
         * surface always re-presents its own new frames. This is a single-window stopgap; a true
         * multi-window desktop needs per-surface geometry composition (the wlroots project). */
        long long area = buffer_area(s->pending_buffer);
        if (s->resource == g_visible_surface || area >= g_vis_area) {
            present_committed_buffer(s->pending_buffer); /* sets g_vis_w/h */
            g_visible_surface = s->resource; /* pointer routes here (we blit it fullscreen) */
            g_vis_area = area;
        }
        wl_buffer_send_release(s->pending_buffer);
        s->pending_buffer = NULL;
    }
}
static void surface_set_buffer_transform(struct wl_client *c, struct wl_resource *r,
                                         int32_t t) {}
static void surface_set_buffer_scale(struct wl_client *c, struct wl_resource *r,
                                     int32_t s) {}
static void surface_damage_buffer(struct wl_client *c, struct wl_resource *r,
                                  int32_t x, int32_t y, int32_t w, int32_t h) {}
static void surface_offset(struct wl_client *c, struct wl_resource *r,
                           int32_t x, int32_t y) {}

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

static void surface_resource_destroy(struct wl_resource *r) {
    struct surface *s = wl_resource_get_user_data(r);
    if (s && s->resource == g_visible_surface) { g_visible_surface = NULL; g_vis_area = 0; }
    free(s);
}

/* ------------------------------------------------------------------ wl_region */

static void region_destroy(struct wl_client *c, struct wl_resource *r) {
    wl_resource_destroy(r);
}
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

static void compositor_create_surface(struct wl_client *c, struct wl_resource *r,
                                      uint32_t id) {
    struct surface *s = calloc(1, sizeof(*s));
    s->resource = wl_resource_create(c, &wl_surface_interface,
                                     wl_resource_get_version(r), id);
    wl_resource_set_implementation(s->resource, &surface_impl, s,
                                   surface_resource_destroy);
    fprintf(stderr, "[srv] compositor.create_surface -> %p\n", (void *)s);
}
static void compositor_create_region(struct wl_client *c, struct wl_resource *r,
                                     uint32_t id) {
    struct wl_resource *reg =
        wl_resource_create(c, &wl_region_interface, 1, id);
    wl_resource_set_implementation(reg, &region_impl, NULL, NULL);
}
static const struct wl_compositor_interface compositor_impl = {
    .create_surface = compositor_create_surface,
    .create_region = compositor_create_region,
};
static void bind_compositor(struct wl_client *c, void *data, uint32_t ver,
                            uint32_t id) {
    struct wl_resource *r =
        wl_resource_create(c, &wl_compositor_interface, ver, id);
    wl_resource_set_implementation(r, &compositor_impl, NULL, NULL);
    fprintf(stderr, "[srv] client bound wl_compositor v%u\n", ver);
}

/* --------------------------------------------------------------- wl_subcompositor
 * winewayland.drv hard-requires wl_subcompositor at init (wayland_process_init aborts
 * with "compositor doesn't support wl_subcompositor" otherwise). It uses subsurfaces to
 * compose a window's client area / decorations. For our single fullscreen game surface the
 * main (parent) surface carries the dmabuf we present, so the subsurface requests can be
 * minimal no-ops — we only need to satisfy the protocol so init succeeds and the client
 * keeps committing the parent surface. */

static void subsurface_destroy(struct wl_client *c, struct wl_resource *r) {
    wl_resource_destroy(r);
}
static void subsurface_set_position(struct wl_client *c, struct wl_resource *r,
                                    int32_t x, int32_t y) {}
static void subsurface_place_above(struct wl_client *c, struct wl_resource *r,
                                   struct wl_resource *sibling) {}
static void subsurface_place_below(struct wl_client *c, struct wl_resource *r,
                                   struct wl_resource *sibling) {}
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

static void subcompositor_destroy(struct wl_client *c, struct wl_resource *r) {
    wl_resource_destroy(r);
}
static void subcompositor_get_subsurface(struct wl_client *c, struct wl_resource *r,
                                         uint32_t id, struct wl_resource *surface,
                                         struct wl_resource *parent) {
    struct wl_resource *sub =
        wl_resource_create(c, &wl_subsurface_interface, wl_resource_get_version(r), id);
    wl_resource_set_implementation(sub, &subsurface_impl, NULL, NULL);
    /* A subsurface carries window content (winewayland uses these for child windows) -> presentable. */
    { struct surface *s = wl_resource_get_user_data(surface); if (s) s->presentable = 1; }
    fprintf(stderr, "[srv] subcompositor.get_subsurface -> %p (parent %p)\n",
            (void *)sub, (void *)parent);
}
static const struct wl_subcompositor_interface subcompositor_impl = {
    .destroy = subcompositor_destroy,
    .get_subsurface = subcompositor_get_subsurface,
};
static void bind_subcompositor(struct wl_client *c, void *data, uint32_t ver,
                               uint32_t id) {
    struct wl_resource *r =
        wl_resource_create(c, &wl_subcompositor_interface, ver, id);
    wl_resource_set_implementation(r, &subcompositor_impl, NULL, NULL);
    fprintf(stderr, "[srv] client bound wl_subcompositor v%u\n", ver);
}

/* ---------------------------------------------------------------- wp_viewporter
 * winewayland.drv also hard-requires wp_viewporter (source-crop + destination-scale
 * of a surface). It sets a viewport destination to size the game surface. Our present
 * path already scales the committed dmabuf to fill the output window, so we accept the
 * viewport requests and treat them as no-ops (the destination == our output size). */

static void viewport_destroy(struct wl_client *c, struct wl_resource *r) {
    wl_resource_destroy(r);
}
static void viewport_set_source(struct wl_client *c, struct wl_resource *r,
                                wl_fixed_t x, wl_fixed_t y,
                                wl_fixed_t w, wl_fixed_t h) {}
static void viewport_set_destination(struct wl_client *c, struct wl_resource *r,
                                     int32_t w, int32_t h) {}
static const struct wp_viewport_interface viewport_impl = {
    .destroy = viewport_destroy,
    .set_source = viewport_set_source,
    .set_destination = viewport_set_destination,
};

static void viewporter_destroy(struct wl_client *c, struct wl_resource *r) {
    wl_resource_destroy(r);
}
static void viewporter_get_viewport(struct wl_client *c, struct wl_resource *r,
                                    uint32_t id, struct wl_resource *surface) {
    struct wl_resource *vp =
        wl_resource_create(c, &wp_viewport_interface, wl_resource_get_version(r), id);
    wl_resource_set_implementation(vp, &viewport_impl, NULL, NULL);
    fprintf(stderr, "[srv] viewporter.get_viewport -> %p\n", (void *)vp);
}
static const struct wp_viewporter_interface viewporter_impl = {
    .destroy = viewporter_destroy,
    .get_viewport = viewporter_get_viewport,
};
static void bind_viewporter(struct wl_client *c, void *data, uint32_t ver,
                            uint32_t id) {
    struct wl_resource *r =
        wl_resource_create(c, &wp_viewporter_interface, ver, id);
    wl_resource_set_implementation(r, &viewporter_impl, NULL, NULL);
    fprintf(stderr, "[srv] client bound wp_viewporter v%u\n", ver);
}

/* ------------------------------------------------------------------ xdg_shell */

static void xdg_toplevel_destroy(struct wl_client *c, struct wl_resource *r) {
    wl_resource_destroy(r);
}
static void xdg_toplevel_noop_parent(struct wl_client *c, struct wl_resource *r,
                                     struct wl_resource *p) {}
static void xdg_toplevel_set_title(struct wl_client *c, struct wl_resource *r,
                                   const char *title) {
    fprintf(stderr, "[srv] xdg_toplevel.set_title \"%s\"\n", title);
}
static void xdg_toplevel_set_app_id(struct wl_client *c, struct wl_resource *r,
                                    const char *id) {}
static void xdg_toplevel_show_menu(struct wl_client *c, struct wl_resource *r,
                                   struct wl_resource *seat, uint32_t serial,
                                   int32_t x, int32_t y) {}
static void xdg_toplevel_move(struct wl_client *c, struct wl_resource *r,
                              struct wl_resource *seat, uint32_t serial) {}
static void xdg_toplevel_resize(struct wl_client *c, struct wl_resource *r,
                                struct wl_resource *seat, uint32_t serial,
                                uint32_t edges) {}
static void xdg_toplevel_set_i32(struct wl_client *c, struct wl_resource *r,
                                 int32_t w, int32_t h) {}
static void xdg_toplevel_noop(struct wl_client *c, struct wl_resource *r) {}
static const struct xdg_toplevel_interface xdg_toplevel_impl = {
    .destroy = xdg_toplevel_destroy,
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

static void xdg_surface_destroy(struct wl_client *c, struct wl_resource *r) {
    wl_resource_destroy(r);
}
static void xdg_surface_get_toplevel(struct wl_client *c, struct wl_resource *r,
                                     uint32_t id) {
    struct wl_resource *tl =
        wl_resource_create(c, &xdg_toplevel_interface,
                           wl_resource_get_version(r), id);
    wl_resource_set_implementation(tl, &xdg_toplevel_impl, NULL, NULL);
    /* Tell the client its size (0x0 = client picks) and that it is active. */
    struct wl_array states;
    wl_array_init(&states);
    uint32_t *st = wl_array_add(&states, sizeof(uint32_t));
    *st = XDG_TOPLEVEL_STATE_ACTIVATED;
    xdg_toplevel_send_configure(tl, 0, 0, &states);
    wl_array_release(&states);
    fprintf(stderr, "[srv] xdg_surface.get_toplevel -> configured\n");
}
static void xdg_surface_get_popup(struct wl_client *c, struct wl_resource *r,
                                  uint32_t id, struct wl_resource *parent,
                                  struct wl_resource *positioner) {}
static void xdg_surface_set_geometry(struct wl_client *c, struct wl_resource *r,
                                     int32_t x, int32_t y, int32_t w, int32_t h) {}
static void xdg_surface_ack_configure(struct wl_client *c, struct wl_resource *r,
                                      uint32_t serial) {
    fprintf(stderr, "[srv] xdg_surface.ack_configure %u\n", serial);
}
static const struct xdg_surface_interface xdg_surface_impl = {
    .destroy = xdg_surface_destroy,
    .get_toplevel = xdg_surface_get_toplevel,
    .get_popup = xdg_surface_get_popup,
    .set_window_geometry = xdg_surface_set_geometry,
    .ack_configure = xdg_surface_ack_configure,
};

static void xdg_wm_base_destroy(struct wl_client *c, struct wl_resource *r) {
    wl_resource_destroy(r);
}
static void xdg_wm_base_create_positioner(struct wl_client *c,
                                          struct wl_resource *r, uint32_t id) {
    /* stub positioner resource */
    struct wl_resource *p =
        wl_resource_create(c, &xdg_positioner_interface,
                           wl_resource_get_version(r), id);
    wl_resource_set_implementation(p, NULL, NULL, NULL);
}
static void xdg_wm_base_get_xdg_surface(struct wl_client *c, struct wl_resource *r,
                                        uint32_t id, struct wl_resource *surf) {
    struct wl_resource *xs =
        wl_resource_create(c, &xdg_surface_interface,
                           wl_resource_get_version(r), id);
    wl_resource_set_implementation(xs, &xdg_surface_impl, NULL, NULL);
    /* This wl_surface now has a window role -> its committed buffers are presented (not a cursor). */
    { struct surface *s = wl_resource_get_user_data(surf); if (s) s->presentable = 1; }
    /* Initial configure so the client proceeds to attach a buffer. */
    xdg_surface_send_configure(xs, 1);
    fprintf(stderr, "[srv] xdg_wm_base.get_xdg_surface -> configure(1)\n");
}
static void xdg_wm_base_pong(struct wl_client *c, struct wl_resource *r,
                             uint32_t serial) {}
static const struct xdg_wm_base_interface xdg_wm_base_impl = {
    .destroy = xdg_wm_base_destroy,
    .create_positioner = xdg_wm_base_create_positioner,
    .get_xdg_surface = xdg_wm_base_get_xdg_surface,
    .pong = xdg_wm_base_pong,
};
static void bind_xdg_wm_base(struct wl_client *c, void *data, uint32_t ver,
                             uint32_t id) {
    struct wl_resource *r =
        wl_resource_create(c, &xdg_wm_base_interface, ver, id);
    wl_resource_set_implementation(r, &xdg_wm_base_impl, NULL, NULL);
    fprintf(stderr, "[srv] client bound xdg_wm_base v%u\n", ver);
}

/* ------------------------------------------------------------ zwp_linux_dmabuf_v1
 *
 * This is the milestone-2 heart: prove that Turnip's Vulkan WSI (the exact same
 * Mesa code winewayland.drv drives) hands US, an external compositor, a real
 * zero-copy dmabuf. We advertise formats+modifiers, then on params.create we
 * receive the client's dmabuf fd(s) and inspect them. We do NOT import to a GPU
 * here — receiving a valid dmabuf fd across the socket IS the risk-#1 proof.
 */
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
};

static void dbuf_buffer_destroy_req(struct wl_client *c, struct wl_resource *r) {
    wl_resource_destroy(r);
}
static const struct wl_buffer_interface dbuf_buffer_impl = {
    .destroy = dbuf_buffer_destroy_req,
};

/* Pixel area of a committed buffer (dmabuf or wl_shm), 0 if unknown. */
static long long buffer_area(struct wl_resource *buffer) {
    if (!buffer) return 0;
    if (wl_resource_instance_of(buffer, &wl_buffer_interface, &dbuf_buffer_impl)) {
        struct dmabuf_buffer *b = wl_resource_get_user_data(buffer);
        return b ? (long long)b->width * b->height : 0;
    }
    struct wl_shm_buffer *shm = wl_shm_buffer_get(buffer);
    if (shm) return (long long)wl_shm_buffer_get_width(shm) * wl_shm_buffer_get_height(shm);
    return 0;
}

/* Called from surface_commit: if the committed buffer is one of our dmabuf
 * buffers, composite it to the output window via the Vulkan present backend. */
static void present_committed_buffer(struct wl_resource *buffer) {
    if (!buffer) return;
    if (wl_resource_instance_of(buffer, &wl_buffer_interface, &dbuf_buffer_impl)) {
        struct dmabuf_buffer *b = wl_resource_get_user_data(buffer);
        if (b && b->n_planes >= 1) {
            if (b->width > 0 && b->height > 0) { g_vis_w = b->width; g_vis_h = b->height; }
            vk_present_commit_dmabuf(b->fd[0], b->format, b->modifier, b->width,
                                     b->height, b->stride[0], b->offset[0]);
        }
        return;
    }
    /* wl_shm (CPU) buffer — the Wine desktop / GDI windows. Upload+blit its pixels so they
     * appear on screen too, not just Vulkan/dmabuf game frames. */
    struct wl_shm_buffer *shm = wl_shm_buffer_get(buffer);
    if (shm) {
        wl_shm_buffer_begin_access(shm);
        void *data = wl_shm_buffer_get_data(shm);
        int32_t w = wl_shm_buffer_get_width(shm);
        int32_t h = wl_shm_buffer_get_height(shm);
        int32_t stride = wl_shm_buffer_get_stride(shm);
        if (data && w > 0 && h > 0) {
            g_vis_w = w; g_vis_h = h;
            vk_present_commit_shm(data, w, h, stride, wl_shm_buffer_get_format(shm));
        }
        wl_shm_buffer_end_access(shm);
    }
}
static void dbuf_buffer_resource_destroy(struct wl_resource *r) {
    struct dmabuf_buffer *b = wl_resource_get_user_data(r);
    if (!b) return;
    for (int i = 0; i < b->n_planes; i++)
        if (b->fd[i] >= 0) close(b->fd[i]);
    free(b);
}

static void params_destroy(struct wl_client *c, struct wl_resource *r) {
    wl_resource_destroy(r);
}
static void params_add(struct wl_client *c, struct wl_resource *r, int32_t fd,
                       uint32_t plane, uint32_t offset, uint32_t stride,
                       uint32_t mod_hi, uint32_t mod_lo) {
    struct dmabuf_params *p = wl_resource_get_user_data(r);
    if (plane >= MAX_PLANES) { close(fd); return; }
    p->fd[plane] = fd;
    p->offset[plane] = offset;
    p->stride[plane] = stride;
    p->modifier[plane] = ((uint64_t)mod_hi << 32) | mod_lo;
    if ((int)plane + 1 > p->n_planes) p->n_planes = plane + 1;
}
static struct wl_resource *params_do_create(struct wl_client *c,
                                            struct wl_resource *r, uint32_t id,
                                            int32_t w, int32_t h, uint32_t format,
                                            uint32_t flags) {
    struct dmabuf_params *p = wl_resource_get_user_data(r);
    struct dmabuf_buffer *b = calloc(1, sizeof(*b));
    b->n_planes = p->n_planes;
    b->width = w; b->height = h; b->format = format;
    b->modifier = p->modifier[0];
    fprintf(stderr,
            "[srv] *** DMABUF RECEIVED via zwp_linux_dmabuf: %dx%d "
            "format=0x%08x(%c%c%c%c) modifier=0x%016llx planes=%d\n",
            w, h, format, format & 0xff, (format >> 8) & 0xff,
            (format >> 16) & 0xff, (format >> 24) & 0xff,
            (unsigned long long)p->modifier[0], p->n_planes);
    for (int i = 0; i < p->n_planes; i++) {
        struct stat st;
        long long sz = -1;
        if (p->fd[i] >= 0 && fstat(p->fd[i], &st) == 0) sz = (long long)st.st_size;
        fprintf(stderr, "[srv]     plane %d: fd=%d size=%lld offset=%u stride=%u\n",
                i, p->fd[i], sz, p->offset[i], p->stride[i]);
        b->fd[i] = p->fd[i];
        b->offset[i] = p->offset[i];
        b->stride[i] = p->stride[i];
        p->fd[i] = -1; /* ownership moves to the buffer */
    }
    struct wl_resource *buf =
        wl_resource_create(c, &wl_buffer_interface, 1, id);
    wl_resource_set_implementation(buf, &dbuf_buffer_impl, b,
                                   dbuf_buffer_resource_destroy);
    return buf;
}
static void params_create(struct wl_client *c, struct wl_resource *r, int32_t w,
                          int32_t h, uint32_t format, uint32_t flags) {
    struct wl_resource *buf = params_do_create(c, r, 0, w, h, format, flags);
    zwp_linux_buffer_params_v1_send_created(r, buf); /* server-allocated new_id */
}
static void params_create_immed(struct wl_client *c, struct wl_resource *r,
                                uint32_t buffer_id, int32_t w, int32_t h,
                                uint32_t format, uint32_t flags) {
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

static void dmabuf_destroy(struct wl_client *c, struct wl_resource *r) {
    wl_resource_destroy(r);
}
static void dmabuf_create_params(struct wl_client *c, struct wl_resource *r,
                                 uint32_t id) {
    struct dmabuf_params *p = calloc(1, sizeof(*p));
    for (int i = 0; i < MAX_PLANES; i++) p->fd[i] = -1;
    struct wl_resource *pr =
        wl_resource_create(c, &zwp_linux_buffer_params_v1_interface,
                           wl_resource_get_version(r), id);
    wl_resource_set_implementation(pr, &params_impl, p, params_resource_destroy);
}
static const struct zwp_linux_dmabuf_v1_interface dmabuf_impl = {
    .destroy = dmabuf_destroy,
    .create_params = dmabuf_create_params,
};
static void bind_dmabuf(struct wl_client *c, void *data, uint32_t ver,
                        uint32_t id) {
    struct wl_resource *r =
        wl_resource_create(c, &zwp_linux_dmabuf_v1_interface, ver, id);
    wl_resource_set_implementation(r, &dmabuf_impl, NULL, NULL);
    uint32_t fmts[] = {DRM_ARGB8888, DRM_XRGB8888, DRM_ABGR8888, DRM_XBGR8888};
    uint64_t mods[] = {MOD_LINEAR, MOD_INVALID};
    for (unsigned f = 0; f < 4; f++) {
        zwp_linux_dmabuf_v1_send_format(r, fmts[f]);
        if (ver >= 3)
            for (unsigned m = 0; m < 2; m++)
                zwp_linux_dmabuf_v1_send_modifier(r, fmts[f],
                                                  (uint32_t)(mods[m] >> 32),
                                                  (uint32_t)(mods[m] & 0xffffffff));
    }
    fprintf(stderr,
            "[srv] client bound zwp_linux_dmabuf_v1 v%u (advertised 4 formats)\n",
            ver);
}

/* ------------------------------------------------------------------ wl_output */

static void bind_output(struct wl_client *c, void *data, uint32_t ver,
                        uint32_t id) {
    struct wl_resource *r = wl_resource_create(c, &wl_output_interface, ver, id);
    wl_resource_set_implementation(r, NULL, NULL, NULL);
    wl_output_send_geometry(r, 0, 0, 340, 190, WL_OUTPUT_SUBPIXEL_UNKNOWN,
                            "Bannerlator", "Wayland-spike",
                            WL_OUTPUT_TRANSFORM_NORMAL);
    wl_output_send_mode(r, WL_OUTPUT_MODE_CURRENT | WL_OUTPUT_MODE_PREFERRED,
                        1920, 1080, 60000);
    if (ver >= 2) {
        wl_output_send_scale(r, 1);
        wl_output_send_done(r);
    }
}

/* ------------------------------------------------------------------ wl_seat
 * A single pointer seat so the guest is clickable. winewayland binds wl_seat,
 * calls get_pointer, and routes wl_pointer.enter/motion/button to the HWND for
 * the entered surface. We deliver events to g_visible_surface (what we blit to
 * the screen), scaled from Android touch coords by the app. */

static uint32_t now_ms(void) {
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint32_t)(ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
}

static void pointer_set_cursor(struct wl_client *c, struct wl_resource *r, uint32_t serial,
                               struct wl_resource *surface, int32_t hx, int32_t hy) {
    /* Cursor surfaces are excluded from presentation by their lack of a window role
     * (see surface_commit's presentable check), so nothing to do here. */
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
    struct wl_resource *p = wl_resource_create(c, &wl_pointer_interface,
                                               wl_resource_get_version(r), id);
    wl_resource_set_implementation(p, &pointer_impl, NULL, pointer_res_destroy);
    if (g_nptrs < MAX_PTRS) { g_ptrs[g_nptrs].ptr = p; g_ptrs[g_nptrs].focus = NULL; g_nptrs++; }
    WLOGI("wl_seat.get_pointer -> %p (nptrs=%d)", (void *)p, g_nptrs);
}
static void seat_get_keyboard(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct wl_resource *k = wl_resource_create(c, &wl_keyboard_interface,
                                               wl_resource_get_version(r), id);
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
    WLOGI("wl_seat.get_keyboard -> %p (nkbs=%d)", (void *)k, g_nkbs);
}
static void seat_get_touch(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct wl_resource *t = wl_resource_create(c, &wl_touch_interface,
                                               wl_resource_get_version(r), id);
    wl_resource_set_implementation(t, &touch_impl, NULL, NULL);
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
    fprintf(stderr, "[srv] client bound wl_seat v%u\n", ver);
}

/* Deliver one pointer event to the visible surface's client pointer. Compositor thread. */
static void deliver_pointer(const struct input_msg *m) {
    if (!g_visible_surface) return;
    struct wl_client *vc = wl_resource_get_client(g_visible_surface);
    struct seat_pointer *sp = NULL;
    for (int i = 0; i < g_nptrs; i++)
        if (wl_resource_get_client(g_ptrs[i].ptr) == vc) { sp = &g_ptrs[i]; break; }
    if (!sp) return;
    struct wl_resource *ptr = sp->ptr;
    int action = m->p1;
    /* Java sends coords in the 1920x1080 output space; we blit the surface stretched to fullscreen,
     * so map back to the surface's real (g_vis_w x g_vis_h) local space or clicks miss non-fullscreen
     * windows (e.g. the file manager). Fullscreen surfaces scale 1:1 (no-op). */
    int lx = (int)((long long)m->p2 * g_vis_w / 1920);
    int ly = (int)((long long)m->p3 * g_vis_h / 1080);
    wl_fixed_t fx = wl_fixed_from_int(lx), fy = wl_fixed_from_int(ly);
    uint32_t t = now_ms();
    WLOGI("pointer action=%d out=(%d,%d) surf=%dx%d -> local=(%d,%d)",
          action, m->p2, m->p3, g_vis_w, g_vis_h, lx, ly);
    if (sp->focus != g_visible_surface) {
        if (sp->focus)
            wl_pointer_send_leave(ptr, wl_display_next_serial(g_display), sp->focus);
        sp->focus = g_visible_surface;
        wl_pointer_send_enter(ptr, wl_display_next_serial(g_display), sp->focus, fx, fy);
    }
    wl_pointer_send_motion(ptr, t, fx, fy);
    if (action == 0)
        wl_pointer_send_button(ptr, wl_display_next_serial(g_display), t, BTN_LEFT,
                               WL_POINTER_BUTTON_STATE_PRESSED);
    else if (action == 2)
        wl_pointer_send_button(ptr, wl_display_next_serial(g_display), t, BTN_LEFT,
                               WL_POINTER_BUTTON_STATE_RELEASED);
    if (wl_resource_get_version(ptr) >= WL_POINTER_FRAME_SINCE_VERSION) wl_pointer_send_frame(ptr);
    wl_display_flush_clients(g_display);
}

/* Deliver one key event to the visible surface's client keyboard. p1=evdev keycode, p2=state. */
static void deliver_key(const struct input_msg *m) {
    if (!g_visible_surface) return;
    struct wl_client *vc = wl_resource_get_client(g_visible_surface);
    struct seat_keyboard *sk = NULL;
    for (int i = 0; i < g_nkbs; i++)
        if (wl_resource_get_client(g_kbs[i].kb) == vc) { sk = &g_kbs[i]; break; }
    if (!sk) return;
    struct wl_resource *kb = sk->kb;
    uint32_t t = now_ms();
    if (sk->focus != g_visible_surface) {
        struct wl_array keys; wl_array_init(&keys);
        if (sk->focus)
            wl_keyboard_send_leave(kb, wl_display_next_serial(g_display), sk->focus);
        sk->focus = g_visible_surface;
        wl_keyboard_send_enter(kb, wl_display_next_serial(g_display), sk->focus, &keys);
        /* Baseline modifiers = none. (Shift/Ctrl come through as their own key events; winewayland
         * tracks them via the keymap. A full xkb modifier mask is a later refinement.) */
        wl_keyboard_send_modifiers(kb, wl_display_next_serial(g_display), 0, 0, 0, 0);
        wl_array_release(&keys);
    }
    wl_keyboard_send_key(kb, wl_display_next_serial(g_display), t, (uint32_t)m->p1,
                         m->p2 ? WL_KEYBOARD_KEY_STATE_PRESSED : WL_KEYBOARD_KEY_STATE_RELEASED);
    wl_display_flush_clients(g_display);
}

/* wl event-loop callback: drain queued input events written by the Android UI thread. */
static int on_input_readable(int fd, uint32_t mask, void *data) {
    struct input_msg m;
    while (read(fd, &m, sizeof(m)) == (ssize_t)sizeof(m)) {
        if (m.type == 1) deliver_key(&m);
        else deliver_pointer(&m);
    }
    return 0;
}

/* Called from JNI (Android UI thread). Queues a pointer event; the compositor thread
 * dispatches it. x/y are in output space (0..1919, 0..1079). */
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
    WLOGI("send_key evdev=%d state=%d (nkbs=%d visible=%p)", evdev, state, g_nkbs,
          (void *)g_visible_surface);
}

/* ------------------------------------------------------------------ entry
 * Lib entry point (was main() in the standalone spike). Blocks in the wl event
 * loop, so the JNI wrapper runs it on a dedicated thread. XDG_RUNTIME_DIR must
 * be set by the caller before this runs. */

int banner_wayland_run(void) {
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
    WLOGI("XDG_RUNTIME_DIR=%s", rt ? rt : "(null)");
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
    const char *socket = "wayland-0";
    WLOGI("listening on %s/wayland-0", rt ? rt : "?");

    wl_global_create(display, &wl_compositor_interface, 6, NULL, bind_compositor);
    wl_global_create(display, &wl_subcompositor_interface, 1, NULL, bind_subcompositor);
    wl_global_create(display, &wp_viewporter_interface, 1, NULL, bind_viewporter);
    wl_display_init_shm(display); /* wl_shm global + pool/buffer handling */
    wl_global_create(display, &wl_output_interface, 2, NULL, bind_output);
    wl_global_create(display, &xdg_wm_base_interface, 1, NULL, bind_xdg_wm_base);
    wl_global_create(display, &zwp_linux_dmabuf_v1_interface, 3, NULL, bind_dmabuf);
    wl_global_create(display, &wl_seat_interface, 5, NULL, bind_seat);

    /* Input injection: the Android UI thread writes touch events to g_input_pipe[1];
     * the wl event loop drains them on this thread (deliver_pointer). */
    g_display = display;
    if (pipe2(g_input_pipe, O_CLOEXEC | O_NONBLOCK) == 0) {
        wl_event_loop_add_fd(wl_display_get_event_loop(display), g_input_pipe[0],
                             WL_EVENT_READABLE, on_input_readable, NULL);
    } else {
        WLOGE("input pipe creation failed");
    }

    fprintf(stderr, "[srv] bannerlator-wayland compositor up on WAYLAND_DISPLAY=%s\n",
            socket);
    fprintf(stderr, "[srv] globals: wl_compositor v6, wl_shm, wl_output v2, "
                    "xdg_wm_base v1, zwp_linux_dmabuf_v1 v3\n");
    fflush(stderr);

    wl_display_run(display); /* blocks, dispatches the event loop */

    wl_display_destroy(display);
    return 0;
}
