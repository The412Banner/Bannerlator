/*
 * bannerin.h - guest side of the BANNERIN controller-input transport.
 *
 * Linked into both libfakeinput.so builds (bionic for Wine, glibc for the Linux runtime) as the
 * static library libbannerin_guest.a. Plain C ABI; no C++ runtime, no allocation, no exported
 * symbols of its own (link with -Wl,--exclude-libs,libbannerin_guest.a), dependencies limited to
 * libc / libdl / pthread.
 *
 * MODEL
 *   The app publishes the complete current state of each pad slot (0..3) into one fixed-size file
 *   per slot, named by FAKE_EVDEV_MEMFD_PATHS ("<slot>=<path>[;<slot>=<path>...]"). Each guest fd
 *   the evdev layer hands out is backed by one handle. A handle turns state changes into evdev
 *   frames (struct input_event, 24 bytes on LP64):
 *     - first, and after any re-attach or resync: a full-state frame
 *         EV_KEY x 11 (BANNERIN_BUTTON_CODES order, current 0/1, BTN_MODE included),
 *         EV_ABS x 8 (BANNERIN_AXIS_CODES order, current value), SYN_REPORT;
 *     - then change frames, one per app publish:
 *         per changed button EV_MSC/MSC_SCAN(value = button code) + EV_KEY,
 *         changed axes in BANNERIN_AXIS_CODES order, SYN_REPORT.
 *   Button edges are never merged or reordered: a press and a release that both happened between
 *   two reads arrive as two frames, in order, across buttons too (a bounded per-slot edge journal
 *   replays them; a reader that falls further behind replays from per-button edge counters, so
 *   per-button order and the final state stay exact). Axes are coalesced to the latest value.
 *   Every handle is an independent consumer: readers never affect each other.
 *
 * AXIS PACING (BANNERIN_AXIS_HZ, read once by bannerin_init)
 *   A frame that carries only axis changes is emitted at most once per interval T per handle. A
 *   stick change less than T after the handle's last frame is held (the fd does not become
 *   readable) and delivered with the latest values when T has passed, so the rest position after
 *   a release reaches the guest within T. Button frames and full-state frames are never held: they
 *   go out at once, carry the current axis values and restart the interval. This keeps a guest HID
 *   stack that queues every report (Wine's queues 32 per handle and drops the oldest, and a game
 *   may take one per rendered frame) from being fed reports faster than the game drains them.
 *     unset or "auto"  frame credits: the app counts every frame the game itself presents (never
 *                      generated or HUD frames) into the slot file, and a handle may send ONE
 *                      stick-only frame per new game frame, and at most BANNERIN_AXIS_AUTO_MAX per
 *                      second (default 60, 20..240: some games draw far faster than they read
 *                      input and queue every report). Credits do not accumulate past one, so
 *                      the first movement after an idle spell goes out at once; a change waiting
 *                      for a credit is woken by the next frame (the app rings the bell only while a
 *                      wake thread waits), or delivered anyway after max(2 frame intervals, 50 ms)
 *                      if the game stalls. Without a fresh frame (< 250 ms old: menus, a GDI app,
 *                      a client UI, an older app) a fixed 60 Hz applies.
 *     N (30..1000)     a fixed N Hz (T = 1/N between stick-only frames).
 *     0                unpaced: one frame per stick update.
 *
 * LIVE CONTROL (API 2, slot files of format 1.5)
 *   The app can change the pacing during a session through the slot file's control block (session
 *   settings, the same in every slot): its axis_mode (1 auto, 2 fixed N, 3 unpaced), N and the auto
 *   ceiling override the environment above; axis_mode 0 leaves the environment in force. A change
 *   applies at the handle's next pacing decision (within the 4 ms re-check while input flows, even
 *   if a stick frame was held under a longer interval), is logged once per slot, and is read
 *   without ever blocking (a record caught mid-update is retried at the next decision; the last
 *   good one stays in force). bannerin_axis_hz() / bannerin_axis_auto() report the effective
 *   values. A file of format <= 1.4 (or with no valid control block) behaves exactly as above.
 *
 * CONTROLLER IDENTITY (API 2)
 *   The control block may also carry the identity the evdev layer should present for the slot
 *   (bus type, vendor, product, version, a short key and the device name). A handle captures it
 *   when it attaches, and again only when it re-attaches to a new slot file; it never changes
 *   under an open handle. bannerin_identity() returns a handle's, bannerin_slot_identity() the
 *   slot file's current one (e.g. to name a device node before any open). The app sets it before
 *   it creates the slot's discovery node. -ENODATA from either means "no identity": use your
 *   own default.
 *
 *   Canonical codes are delivered (triggers as ABS_BRAKE / ABS_GAS); any re-presentation (e.g. the
 *   Steam-virtual ABS_Z / ABS_RZ identity) is the evdev layer's job. Values are always inside the
 *   advertised ranges: sticks -32768..32767, triggers 0..255, hats -1..1.
 *
 * READINESS
 *   The fd returned by bannerin_attach() is a real kernel fd (an AF_UNIX socket used only as a
 *   level-triggered flag): it is readable exactly while the handle has undelivered events, and
 *   reports POLLHUP once the controller is unplugged. Plain poll/select/ppoll/epoll on it work
 *   with no interposer help, so mixed waits with other fds need no emulation; a helper thread per
 *   slot (lazily started, all signals blocked) keeps the flag current within about a millisecond
 *   of each publish, and also wakes at the moment a held (paced) stick frame falls due, so a
 *   reader blocked in poll(-1) still gets it on time. While input flows it re-checks every 4 ms in
 *   case a wake was missed; once nothing has been published or held for 250 ms it sleeps with a
 *   1 s timeout (the writer-liveness check), and a slot with no open handle never wakes at all. Do NOT read() it directly: deliver events with
 *   bannerin_read(). A raw read of it (e.g. after exec without O_CLOEXEC) yields at most a single
 *   byte, never anything shaped like an input_event.
 *
 * SELF-HEALING
 *   If the slot file is missing, foreign, truncated, replaced, released by the app (session end)
 *   or its writer process died, the handle stays present, shows a neutral pad (a full-state frame
 *   releasing anything held), and re-attaches by itself when a valid file appears (again starting
 *   with a full-state frame). Only an explicit unplug by the app (writer destroy()) removes it.
 *
 * THREADS, SIGNALS, FORK
 *   All functions are thread-safe and safe to call before the host's constructors run and after
 *   its static destructors (all state is static, nothing is ever torn down). No lock is held while
 *   sleeping or while calling the logger. Functions are not async-signal-safe. Across fork() the
 *   library re-arms itself in the child (pthread_atfork): the child keeps the same fd numbers,
 *   each with its own readiness and its own read position, and its first read is a full-state
 *   frame. Parent and child never share or split a stream.
 *
 * ERRORS
 *   Functions returning int/ssize_t return a negative errno on failure (never set errno).
 */
#ifndef BANNERIN_H
#define BANNERIN_H

#include <stddef.h>
#include <stdint.h>
#include <sys/types.h>

#ifdef __cplusplus
extern "C" {
#endif

#define BANNERIN_API_VERSION 2u
#define BANNERIN_MAX_SLOTS 4
#define BANNERIN_BUTTONS 11
#define BANNERIN_AXES 8
/* sizeof(struct input_event) on LP64 Linux; bannerin_read() writes this layout. */
#define BANNERIN_EVENT_SIZE 24

/* Canonical button order (index -> evdev code): BTN_A, BTN_B, BTN_X, BTN_Y, BTN_TL, BTN_TR,
 * BTN_SELECT, BTN_START, BTN_MODE, BTN_THUMBL, BTN_THUMBR. */
#define BANNERIN_BUTTON_CODES { 0x130, 0x131, 0x133, 0x134, 0x136, 0x137, 0x13a, 0x13b, 0x13c, 0x13d, 0x13e }
/* Canonical axis order (index -> evdev code): ABS_X, ABS_Y, ABS_RX, ABS_RY, ABS_BRAKE (left
 * trigger), ABS_GAS (right trigger), ABS_HAT0X, ABS_HAT0Y. */
#define BANNERIN_AXIS_CODES { 0x00, 0x01, 0x03, 0x04, 0x0a, 0x09, 0x10, 0x11 }
#define BANNERIN_AXIS_MIN { -32768, -32768, -32768, -32768, 0, 0, -1, -1 }
#define BANNERIN_AXIS_MAX { 32767, 32767, 32767, 32767, 255, 255, 1, 1 }

/* How a reader last paced stick-only frames (bannerin_stats.axis_mode, slot file r_axis_mode). */
#define BANNERIN_AXIS_MODE_CREDITS  1   /* one per new game frame (auto, fresh frame feed) */
#define BANNERIN_AXIS_MODE_FALLBACK 2   /* auto without a fresh frame feed: fixed 60 Hz */
#define BANNERIN_AXIS_MODE_FIXED    3   /* BANNERIN_AXIS_HZ = N */
#define BANNERIN_AXIS_MODE_UNPACED  4   /* BANNERIN_AXIS_HZ = 0 */
#define BANNERIN_AXIS_MODE_CEILING  5   /* auto, fresh feed, game faster than BANNERIN_AXIS_AUTO_MAX */

/* Opaque, never 0. A stale handle (after detach) is always refused with -EBADF. */
typedef uint32_t bannerin_handle;

/* A controller identity (API 2). Strings are always NUL-terminated. 92 bytes, 4-byte aligned. */
struct bannerin_identity {
    uint32_t gen;        /* the control gen it came from (changes when the app changes it) */
    uint16_t bustype;    /* evdev input_id: BUS_* */
    uint16_t vendor;
    uint16_t product;
    uint16_t version;
    char key[16];        /* short ASCII tag, e.g. "xbox360" */
    char name[64];       /* UTF-8 device name, e.g. "Xbox 360 Controller (0)" */
};

/* The slot's current state in canonical order (buttons 0/1). */
struct bannerin_state {
    int32_t buttons[BANNERIN_BUTTONS];
    int32_t axes[BANNERIN_AXES];
};

/* Process-wide reader counters (for FAKE_EVDEV_LOG diagnostics). */
struct bannerin_stats {
    uint64_t attaches;
    uint64_t detaches;
    uint64_t reattaches;      /* handles that moved to a new backing file */
    uint64_t orphaned;        /* handles that lost their backing (neutral pad until it returns) */
    uint64_t removals;        /* handles that saw an unplug */
    uint64_t writer_dead;     /* times a dead writer process was detected */
    uint64_t frames;
    uint64_t events;
    uint64_t taps;            /* press edges delivered */
    uint64_t full_frames;
    uint64_t journal_frames;  /* frames replayed from the edge journal */
    uint64_t counter_frames;  /* frames replayed from the per-button edge counters */
    uint64_t coalesced_taps;  /* taps dropped because one button fell > 64 taps behind */
    uint64_t resyncs;         /* full-state frames forced by inconsistent state */
    uint64_t helper_wakes;
    uint64_t helper_timeouts; /* 4 ms fallback re-checks */
    uint64_t ready_signals;
    uint64_t snapshot_busy;
    uint64_t fork_rebinds;
    uint64_t paced_frames;           /* axis-only frames held back by pacing, then delivered */
    uint64_t merged_axis_publishes;  /* publishes merged into a later frame instead of their own */
    uint64_t axis_hz;                /* the rate last paced to (credits: the game's frame rate) */
    uint64_t auto_fallbacks;         /* auto mode: times the frame feed went stale (fixed 60 Hz) */
    uint64_t axis_mode;              /* BANNERIN_AXIS_MODE_* last in force (0 = none yet) */
    uint64_t ctl_applied;            /* API 2: control records applied (live pacing changes) */
    uint64_t ctl_busy;               /* control reads that found the record mid-update (retried) */
    uint64_t identity_reads;         /* bannerin_identity / bannerin_slot_identity calls answered */
};

/* Counters stored in a slot file: its writer's and the sum over all of its readers. */
struct bannerin_slot_stats {
    uint64_t instance;
    uint64_t publishes;       /* state updates the app published into this file */
    uint64_t wake_calls;
    uint64_t woken;           /* sleepers the app's publishes woke */
    uint64_t unplugs;
    uint64_t edges;           /* button edges published (all time, this writer) */
    uint64_t reader_attaches;
    uint64_t reader_reattaches;
    uint64_t reader_taps;
    uint64_t reader_coalesced;        /* taps dropped (one button > 64 taps behind) */
    uint64_t reader_resyncs;
    uint64_t reader_frames;
    uint64_t reader_events;
    uint64_t reader_removals;
    uint32_t writer_pid;
    uint32_t retired;
    uint64_t reader_paced_frames;           /* zero in files written before format 1.1 */
    uint64_t reader_merged_axis_publishes;
    uint64_t reader_axis_hz;                /* format 1.2: rate a reader last paced to */
    uint64_t reader_auto_fallbacks;
    uint64_t frame_interval_ns;             /* the game's last observed frame interval (0 = none) */
    uint64_t frame_time_ns;                 /* CLOCK_MONOTONIC of the game's last frame */
    uint64_t frame_count;                   /* format 1.3: game frames counted (the credits) */
    uint64_t reader_axis_mode;              /* BANNERIN_AXIS_MODE_* a reader last paced in */
    uint64_t frame_waiters;                 /* wake threads waiting for the next game frame */
    uint64_t reader_helper_wakes;           /* wake-thread wake-ups, all reader processes */
    uint64_t reader_auto_max;               /* format 1.4: the auto ceiling in force (Hz) */
    uint64_t control_gen;                   /* format 1.5: the file's control gen (0 = no block) */
    uint64_t reader_ctl_gen_applied;        /* the control gen a reader last applied */
    uint64_t reader_id_gen_attached;        /* the gen whose identity the last attach took */
    uint64_t reader_identity_reads;         /* identity queries readers answered from this file */
};

/* level 0 = info, 1 = warning; line is NUL-terminated, valid only during the call. */
typedef void (*bannerin_log_fn)(int level, const char *line);

/* Parse FAKE_EVDEV_MEMFD_PATHS and BANNERIN_AXIS_HZ (once; later calls return the cached result). Returns the mask of
 * configured slots (bit N = slot N), 0 if none. Lazy and idempotent; the other functions call it
 * themselves, so calling it is optional. */
int bannerin_init(void);

/* Attach a new handle to `slot` for one guest open(). Honours O_NONBLOCK and O_CLOEXEC in
 * `open_flags` (other bits are ignored). On success stores the handle in *out_handle and returns
 * the guest fd (>= 0) to hand back to the program; its first data is a full-state frame and it is
 * already readable. A slot whose file does not exist yet still attaches (neutral pad) and starts
 * delivering input once the app creates it. Errors: -ENODEV (slot not in FAKE_EVDEV_MEMFD_PATHS
 * or out of range), -EMFILE (handle table or fd limit), other -errno from socket creation. */
int bannerin_attach(int slot, int open_flags, bannerin_handle *out_handle);

/* Release the handle AND close its guest fd (call it from the close() hook instead of passing the
 * close through). A read blocked in another thread on this handle is woken and returns -EBADF.
 * Returns 0 or -EBADF. */
int bannerin_detach(bannerin_handle h);

/* The guest fd of a handle (as returned by bannerin_attach), or -EBADF. */
int bannerin_fd(bannerin_handle h);

/* The slot of a handle, or -EBADF. */
int bannerin_slot(bannerin_handle h);

/* 1 once the app unplugged this slot's controller after the handle attached (sticky: a new
 * controller on the same slot needs a new open), 0 otherwise, -EBADF. While removed, the evdev
 * layer should fail read/write with ENODEV and report POLLHUP; the fd itself already does. */
int bannerin_is_removed(bannerin_handle h);

/* 1 if bannerin_read() would return events now, 0 if not, -ENODEV if removed, -EBADF. Consumes
 * nothing; cheap enough to call from a poll loop. */
int bannerin_has_pending(bannerin_handle h);

/* poll() revents for a handle given the requested `events`: POLLIN|POLLRDNORM (as requested)
 * when events are pending, POLLOUT|POLLWRNORM (as requested) always, POLLHUP|POLLERR once removed,
 * POLLNVAL for a bad handle. Use it to correct revents if the hook emulates poll/select. */
short bannerin_poll_revents(bannerin_handle h, short events);

/* Read whole struct input_event records into buf (len bytes; as many as fit, the rest stay
 * pending, a partly delivered frame continues on the next call). Returns the number of bytes
 * written (a multiple of BANNERIN_EVENT_SIZE), or:
 *   -EINVAL  len < BANNERIN_EVENT_SIZE
 *   -EAGAIN  nothing pending and nonblocking != 0
 *   -ENODEV  the controller was unplugged (pending events are dropped, as evdev does)
 *   -EBADF   bad handle, or detached by another thread while this call slept
 *   -EINTR   a signal (handler without SA_RESTART) interrupted the wait
 * With nonblocking == 0 it sleeps until input arrives; no lock is held while it sleeps. */
ssize_t bannerin_read(bannerin_handle h, void *buf, size_t len, int nonblocking);

/* The slot's state right now, consistent (never a mix of two publishes), for EVIOCGKEY /
 * EVIOCGABS. Neutral while the handle has no usable backing. Returns 0, -ENODEV, -EBADF. */
int bannerin_current_state(bannerin_handle h, struct bannerin_state *out);

/* Copy the process-wide counters (at most `size` bytes, for forward compatibility). Returns 0 or
 * -EINVAL. */
int bannerin_get_stats(struct bannerin_stats *out, size_t size);

/* Copy the counters stored in `slot`'s current file. Returns 0, -ENODEV (slot not configured),
 * or another -errno if the file is missing or not valid. */
int bannerin_slot_stats(int slot, struct bannerin_slot_stats *out, size_t size);

/* Route the library's diagnostics (attach, re-attach, orphaned, writer gone, unplug, resync,
 * coalesced taps) to fn; NULL silences them. Never called with any library lock held. */
void bannerin_set_logger(bannerin_log_fn fn);

/* The controller identity this handle presents: the one its slot file carried when the handle
 * attached to it (or re-attached to a new file). Copies at most `size` bytes (pass
 * sizeof(struct bannerin_identity)). Returns 0, -ENODATA (no identity: use your default),
 * -EBADF (bad handle), -EINVAL (out is NULL). */
int bannerin_identity(bannerin_handle h, struct bannerin_identity *out, size_t size);

/* The identity `slot`'s current file carries right now, for naming a device before any open.
 * Returns 0, -ENODATA (the file has no identity, or predates control blocks: use your default),
 * -ENODEV (slot not configured, or no live file for it), -EINVAL (out is NULL), or -EAGAIN if the
 * app kept the record mid-update through every attempt (practically never; try again). */
int bannerin_slot_identity(int slot, struct bannerin_identity *out, size_t size);

/* BANNERIN_API_VERSION of the linked library. */
uint32_t bannerin_api_version(void);

/* The stick-frame rate in force: the fixed rate, 0 when unpaced, or in auto mode the rate a reader
 * in this process last paced to (the game's frame rate under credits; 60 before any or without a
 * fresh frame feed). Effective value: the app's control block when it sets the pacing, else
 * BANNERIN_AXIS_HZ. */
uint32_t bannerin_axis_hz(void);

/* 1 if stick frames are paced by game frame credits (auto: from the control block, or
 * BANNERIN_AXIS_HZ unset / "auto" when the control block leaves it to the environment), else 0. */
int bannerin_axis_auto(void);

#ifdef __cplusplus
}
#endif

#endif /* BANNERIN_H */
