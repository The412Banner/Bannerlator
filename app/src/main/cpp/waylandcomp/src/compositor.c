/*
 * bannerlator-wayland — milestone 1 compositor
 *
 * Goal: prove the SERVER HALF on-device. A real Wayland client (eventually
 * winewayland.drv) must be able to: connect, bind our globals, create a
 * surface, run the xdg-shell configure handshake, attach a buffer, and commit
 * — and we observe the commit. No rendering yet (that's milestone 2); attached
 * buffers are just logged and released so the client keeps producing frames.
 *
 * Globals advertised: wl_compositor, wl_shm (via wl_display_init_shm),
 * wl_output, xdg_wm_base.
 */
#define _POSIX_C_SOURCE 200809L
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <stdint.h>
#include <wayland-server.h>
#include "xdg-shell-server-protocol.h"
#include "linux-dmabuf-v1-server-protocol.h"
#include "vk_import.h"

/* ------------------------------------------------------------------ wl_surface */

struct surface {
    struct wl_resource *resource;
    struct wl_resource *pending_buffer; /* buffer from the most recent attach */
    struct wl_resource *xdg_surface;    /* set once role is assigned */
    int configured;
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
static void surface_commit(struct wl_client *c, struct wl_resource *r) {
    struct surface *s = wl_resource_get_user_data(r);
    fprintf(stderr, "[srv] surface.commit (buffer=%p)  <-- FRAME OBSERVED\n",
            (void *)s->pending_buffer);
    if (s->pending_buffer) {
        /* No renderer yet: release immediately so the client can reuse it. */
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
    free(wl_resource_get_user_data(r));
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
        p->fd[i] = -1; /* ownership moves to the buffer */
    }
    fprintf(stderr,
            "[srv]     ==> ZERO-COPY GPU BUFFER CROSSED THE SOCKET (risk #1 retired)\n");
    /* Prove we can import it into our own Vulkan device (once). */
    static int import_tried = 0;
    if (!import_tried && p->n_planes == 1) {
        import_tried = 1;
        vk_import_dmabuf(b->fd[0], format, b->modifier, w, h, p->stride[0],
                         p->offset[0]);
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

/* ------------------------------------------------------------------ main */

int main(void) {
    struct wl_display *display = wl_display_create();
    if (!display) {
        fprintf(stderr, "[srv] wl_display_create failed\n");
        return 1;
    }

    const char *socket = wl_display_add_socket_auto(display);
    if (!socket) {
        fprintf(stderr, "[srv] add_socket failed (XDG_RUNTIME_DIR set?)\n");
        return 1;
    }

    wl_global_create(display, &wl_compositor_interface, 6, NULL, bind_compositor);
    wl_display_init_shm(display); /* wl_shm global + pool/buffer handling */
    wl_global_create(display, &wl_output_interface, 2, NULL, bind_output);
    wl_global_create(display, &xdg_wm_base_interface, 1, NULL, bind_xdg_wm_base);
    wl_global_create(display, &zwp_linux_dmabuf_v1_interface, 3, NULL, bind_dmabuf);

    fprintf(stderr, "[srv] bannerlator-wayland compositor up on WAYLAND_DISPLAY=%s\n",
            socket);
    fprintf(stderr, "[srv] globals: wl_compositor v6, wl_shm, wl_output v2, "
                    "xdg_wm_base v1, zwp_linux_dmabuf_v1 v3\n");
    fflush(stderr);

    wl_display_run(display); /* blocks, dispatches the event loop */

    wl_display_destroy(display);
    return 0;
}
