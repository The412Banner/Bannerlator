/*
 * The fake evdev layer. Taken from WinNative (maxjivi05, main 0cfc070b + feature/wayland-gamescope
 * through f1f34cd7, 2026-09-14..19), GPL-3.0: the locking, the fork handlers, the shared device
 * table, the Steam virtual-gamepad identity and the Z/RZ trigger placement are that author's.
 * Added here: an opt-in Xbox 360 identity (FAKE_EVDEV_IDENTITY=xbox360), which is what gets SDL's
 * built-in mapping applied with no configuration when the pad is read by the Steam client itself;
 * and a stand-in for /dev/uinput (FAKE_EVDEV_UINPUT=1), so the virtual pads Steam Input makes for
 * a game become more of these nodes instead of vanishing; and the pad as a Steam Deck controller
 * over hidraw for the client (FAKE_EVDEV_DECK=1). (From Droid-Deck/DroidDeck #86, #102, #125, #146.)
 */
#include <algorithm>
#include <atomic>
#include <cerrno>
#include <climits>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/joystick.h>
#include <linux/uinput.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include <sys/eventfd.h>
#include <sys/inotify.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <time.h>
#include <unistd.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/syscall.h>
#include <sys/sysmacros.h>
#include <sys/time.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <sys/un.h>
#include <unistd.h>

#define EXPORT __attribute__((visibility("default"))) extern "C"

// glibc declares the request as unsigned long; bionic as int. The same source also serves the
// Linux runtime's preload (tools/linuxfs), where Steam and SDL run against glibc.
#ifdef __GLIBC__
typedef unsigned long ioctl_request_t;
#else
typedef int ioctl_request_t;
#endif

static constexpr uint16_t GAMEPAD_VENDOR_ID_BASE = 0x1234;
static constexpr uint16_t GAMEPAD_PRODUCT_ID_BASE = 0x5678;
static constexpr uint16_t GAMEPAD_VERSION = 0x0110;
static constexpr const char *GAMEPAD_NAME_TEMPLATE = "Generic HID Gamepad %d";
// The gamepad Steam Input presents to a game, and the name SDL reads its slot from.
static constexpr uint16_t STEAM_VIRTUAL_VENDOR_ID = 0x28de;
static constexpr uint16_t STEAM_VIRTUAL_PRODUCT_ID = 0x11ff;
static constexpr const char *STEAM_VIRTUAL_NAME_TEMPLATE = "Microsoft X-Box 360 pad %d";
// Opt-in identity for the process that reads the pad directly (the Steam client): SDL keys its
// mapping database on bus+vendor+product, and 045E:028E is the most widely mapped id there is.
// Not offset per slot: a real pad reports the same ids on every port.
static constexpr uint16_t X360_VENDOR_ID = 0x045E;
static constexpr uint16_t X360_PRODUCT_ID = 0x028E;
static constexpr const char *X360_NAME_TEMPLATE = "Xbox 360 Controller (%d)";
static constexpr const char *GAMEPAD_PHYS_TEMPLATE = "usb-fakeinput/input%d";
static constexpr const char *GAMEPAD_UNIQ_TEMPLATE = "0000000000%02d";
static constexpr uint8_t GAMEPAD_AXIS_COUNT = 8;
static constexpr uint8_t GAMEPAD_BUTTON_COUNT = 11;
static constexpr uint32_t FAKE_INPUT_RING_MAGIC = 0x46494252;
static constexpr uint32_t FAKE_INPUT_RING_VERSION = 2;
static constexpr uint32_t FAKE_INPUT_EVENT_SIZE = sizeof(struct input_event);
static constexpr uint32_t FAKE_INPUT_RING_CAPACITY = 4096;
static constexpr unsigned int FAKE_INPUT_MAJOR = 13;
static constexpr unsigned int FAKE_INPUT_EVENT_MINOR_BASE = 64;
static constexpr unsigned int FAKE_INPUT_JS_MINOR_BASE = 0;
// Nodes from event16 up are the virtual pads programs make through the /dev/uinput stand-in;
// below that are the app's own rings, one per slot.
static constexpr int UINPUT_EVENT_BASE = 16;
static constexpr int UINPUT_EVENT_LIMIT = 64;
static constexpr uint32_t UINPUT_DESCRIPTION_MAGIC = 0x46495544;

struct FakeInputRingHeader {
  uint32_t magic;             // 0
  uint32_t version;           // 4
  uint32_t event_size;        // 8
  uint32_t capacity;          // 12
  uint64_t write_seq;         // 16
  uint64_t generation;        // 24
  // Authoritative absolute-state snapshot, published by the writer under a
  // seqlock (odd snapshot_seq = write in progress). The reader replays it as a
  // full keyframe whenever the delta stream could have desynced (open, ring
  // overflow) so dropped events can recover without periodic duplicate input.
  uint64_t snapshot_seq;      // 32
  uint32_t snapshot_buttons;  // 40  bit i -> kSnapshotButtons[i] pressed
  int16_t snapshot_axes[8];   // 44  values in kSnapshotAxisCodes order
  uint32_t resync_seq;        // 60
};

static_assert(sizeof(FakeInputRingHeader) == 64,
              "fake input ring header must stay ABI-stable");

static constexpr size_t FAKE_INPUT_RING_HEADER_SIZE =
    sizeof(FakeInputRingHeader);
static constexpr size_t FAKE_INPUT_RING_SIZE =
    FAKE_INPUT_RING_HEADER_SIZE +
    (FAKE_INPUT_RING_CAPACITY * FAKE_INPUT_EVENT_SIZE);

// What a program told the /dev/uinput stand-in about the device it made, stored after the ring
// in that device's file so every process that opens the node answers the same identity.
struct UinputDescription {
  uint32_t magic;
  int32_t owner_pid;
  struct input_id id;
  uint32_t ff_effects_max;
  char name[UINPUT_MAX_NAME_SIZE];
  char phys[64];
  uint8_t evbits[(EV_MAX + 8) / 8];
  uint8_t keybits[(KEY_MAX + 8) / 8];
  uint8_t absbits[(ABS_MAX + 8) / 8];
  uint8_t ffbits[(FF_MAX + 8) / 8];
  struct input_absinfo absinfo[ABS_CNT];
};

static constexpr size_t UINPUT_FILE_SIZE = FAKE_INPUT_RING_SIZE + sizeof(UinputDescription);

struct FakeController {
  char *event = nullptr;
  // Set for a node made through the /dev/uinput stand-in; null for the app's own pads.
  const UinputDescription *uinput = nullptr;
  int slot = -1;
  FakeInputRingHeader *ring = nullptr;
  uint64_t read_seq = 0;
  uint64_t generation = 0;
  uint32_t resync_seq = 0;
  size_t mapping_size = 0;
  bool closed = false;
  bool needs_keyframe = true;

  ~FakeController() {
    if (ring) munmap(ring, mapping_size);
    free(event);
  }
  // Pending keyframe (full absolute-state baseline) currently streaming to the
  // guest. The axis/button values are captured from the snapshot when the
  // keyframe starts so the frame stays consistent across multi-read delivery.
  size_t keyframe_remaining = 0;
  int32_t keyframe_axes[8] = {0, 0, 0, 0, 0, 0, 0, 0};
  uint32_t keyframe_buttons = 0;
};

struct NeutralEventSpec {
  uint16_t type;
  uint16_t code;
};

// Event template for a full keyframe: every button, every axis/hat, then a
// SYN_REPORT, in this fixed order. The value carried by each event is filled
// from the authoritative snapshot (see keyframe_value); an all-zero snapshot
// yields the neutral baseline. Replayed on open and ring overflow so dropped
// events cannot leave a guest stuck.
static const NeutralEventSpec kNeutralEvents[] = {
    {EV_KEY, BTN_A},      {EV_KEY, BTN_B},      {EV_KEY, BTN_X},
    {EV_KEY, BTN_Y},      {EV_KEY, BTN_TL},     {EV_KEY, BTN_TR},
    {EV_KEY, BTN_SELECT}, {EV_KEY, BTN_START},  {EV_KEY, BTN_MODE},
    {EV_KEY, BTN_THUMBL}, {EV_KEY, BTN_THUMBR}, {EV_ABS, ABS_X},
    {EV_ABS, ABS_Y},      {EV_ABS, ABS_RX},     {EV_ABS, ABS_RY},
    {EV_ABS, ABS_GAS},    {EV_ABS, ABS_BRAKE},  {EV_ABS, ABS_HAT0X},
    {EV_ABS, ABS_HAT0Y},  {EV_SYN, SYN_REPORT},
};
static constexpr size_t kNeutralEventCount =
    sizeof(kNeutralEvents) / sizeof(kNeutralEvents[0]);

// Axis layout of FakeInputRingHeader::snapshot_axes (mirrors the Java writer).
static const uint16_t kSnapshotAxisCodes[8] = {
    ABS_X, ABS_Y, ABS_RX, ABS_RY, ABS_GAS, ABS_BRAKE, ABS_HAT0X, ABS_HAT0Y};
// Bit i of FakeInputRingHeader::snapshot_buttons maps to this button code.
static const uint16_t kSnapshotButtons[11] = {
    BTN_A,      BTN_B,      BTN_X,     BTN_Y,      BTN_TL,    BTN_TR,
    BTN_SELECT, BTN_START,  BTN_THUMBL, BTN_THUMBR,
    // Bit 10 is the Steam button: the client's in-game menu is bound to it, and SDL reports it
    // as button 8 from this position in the key bits.
    BTN_MODE};
static constexpr size_t kSnapshotButtonCount =
    sizeof(kSnapshotButtons) / sizeof(kSnapshotButtons[0]);

// An LD_PRELOAD interposer is called outside its own lifetime. The loader initialises libraries
// in dependency order, and the hooks below belong to whichever library gets there first: on the
// Linux runtime, libpython's initialiser runs before ours and calls close() and read(), so a hook
// fires before this library has been initialised at all. A file-scope std::unordered_map or
// std::recursive_mutex has no object yet at that moment - touching one is undefined behaviour,
// and in practice the process dies before main(). The same window reopens at exit, after static
// destructors have run. (Proton is a Python script, so this killed every game launched from the
// native Steam client, and the compatibility-tool registrar with it.)
//
// So everything with a constructor lives behind an accessor that builds it on first use and
// never destroys it. The leak is deliberate and bounded: one map or mutex for the process.
namespace Logger {
void init();
}

static void controller_fork_prepare();
static void controller_fork_parent();
static void controller_fork_child();

struct FakeConfig {
  const char *hook_dir;
  const char *udev_data_dir;
  bool vibration_enabled;
  bool xbox360_identity;
  bool uinput_enabled;
};

static FakeConfig &config() {
  static FakeConfig *cfg = [] {
    auto *c = new FakeConfig();
    const char *dir = getenv("FAKE_EVDEV_DIR");
    c->hook_dir = dir ? dir : "/data/data/com.termux/files/home/fake-input";
    c->udev_data_dir = getenv("FAKE_UDEV_DATA_DIR");
    c->vibration_enabled =
        getenv("FAKE_EVDEV_VIBRATION") && atoi(getenv("FAKE_EVDEV_VIBRATION"));
    const char *identity = getenv("FAKE_EVDEV_IDENTITY");
    c->xbox360_identity = identity && !strcmp(identity, "xbox360");
    c->uinput_enabled = getenv("FAKE_EVDEV_UINPUT") && atoi(getenv("FAKE_EVDEV_UINPUT"));
    pthread_atfork(controller_fork_prepare, controller_fork_parent,
                   controller_fork_child);
    Logger::init();
    return c;
  }();
  return *cfg;
}

static std::recursive_mutex &controller_mutex() {
  static auto *mutex = new std::recursive_mutex();
  return *mutex;
}

static std::unordered_map<int, std::shared_ptr<FakeController>> &controller_map() {
  static auto *map = new std::unordered_map<int, std::shared_ptr<FakeController>>();
  return *map;
}

static std::unordered_map<int, std::string> &ring_paths() {
  static auto *map = new std::unordered_map<int, std::string>();
  return *map;
}

static std::unordered_map<int, struct ff_effect> &ff_effects() {
  static auto *map = new std::unordered_map<int, struct ff_effect>();
  return *map;
}

static const char *fake_hook_dir() { return config().hook_dir; }
static const char *fake_udev_data_dir() { return config().udev_data_dir; }
static bool fake_vibration_enabled() { return config().vibration_enabled; }
static bool fake_xbox360_identity() { return config().xbox360_identity; }
static bool fake_uinput_enabled() { return config().uinput_enabled; }

static bool ring_paths_loaded = false;
static int next_ff_id = 0;

// fork() carries over only the calling thread, so a lock another thread was holding at that
// instant stays held in the child by a thread that is not there to release it. Every hook below
// takes controller_mutex, and the Steam client forks while its other threads are inside them, so
// the child blocked on its first open() and never reached exec: the spawn never completed, the
// client's main loop stalled past its own watchdog and it tore the session down.
//
// Taking the lock before the fork is what makes the copy consistent - no thread is part way
// through the tables it guards. The parent then unlocks it. The child cannot: a recursive mutex
// records the owning thread, the child's one thread has a new id, and unlocking one it does not
// own is refused, which would leave the lock held for good. It gets a fresh mutex instead, which
// is sound precisely because the fork was taken with the lock held.
static void controller_fork_prepare() { controller_mutex().lock(); }
static void controller_fork_parent() { controller_mutex().unlock(); }

static void controller_fork_child() {
  pthread_mutexattr_t attr;

  pthread_mutexattr_init(&attr);
  pthread_mutexattr_settype(&attr, PTHREAD_MUTEX_RECURSIVE);
  pthread_mutex_init(controller_mutex().native_handle(), &attr);
  pthread_mutexattr_destroy(&attr);
}

namespace Logger {
int log_enabled;
// FAKE_EVDEV_LOG_FILE: the session's pad.log, shared by every process the library is loaded in
// (the client, each game), so a pad report has the guest side in one file of the session folder.
// Opened with the raw syscall: open() is this library's own hook.
static int log_fd = -1;
static char log_name[17];

void init() {
  log_enabled = getenv("FAKE_EVDEV_LOG") && atoi(getenv("FAKE_EVDEV_LOG"));
  const char *path = getenv("FAKE_EVDEV_LOG_FILE");
  if (log_enabled && path && *path)
    log_fd = static_cast<int>(syscall(SYS_openat, AT_FDCWD, path, O_WRONLY | O_APPEND | O_CREAT | O_CLOEXEC, 0644));
  if (prctl(PR_GET_NAME, log_name) != 0) strcpy(log_name, "?");
}

void log(const char *message, ...) {
  if (!log_enabled)
    return;

  if (log_fd < 0) {
    va_list args;
    va_start(args, message);
    vfprintf(stderr, message, args);
    va_end(args);
    std::cerr.flush();
    return;
  }
  // One write per line: O_APPEND keeps lines from different processes whole.
  char line[512];
  struct timespec now;
  clock_gettime(CLOCK_REALTIME, &now);
  struct tm local;
  localtime_r(&now.tv_sec, &local);
  int n = snprintf(line, sizeof(line), "%02d:%02d:%02d.%03ld %d %s: ", local.tm_hour, local.tm_min,
                   local.tm_sec, now.tv_nsec / 1000000, getpid(), log_name);
  va_list args;
  va_start(args, message);
  if (n > 0 && n < static_cast<int>(sizeof(line)))
    n += vsnprintf(line + n, sizeof(line) - n, message, args);
  va_end(args);
  if (n <= 0) return;
  if (n >= static_cast<int>(sizeof(line))) {
    n = sizeof(line) - 1;
    line[n - 1] = '\n';
  }
  ssize_t ignored = write(log_fd, line, n);
  (void)ignored;
}
} // namespace Logger

__attribute__((constructor)) static void library_init() {
  // Warming the lazily built state, not owning it: when the loader runs this before anything
  // calls a hook - the ordinary case - the first hook finds everything already there. Nothing
  // below depends on it having run, which is the whole point.
  config();
  controller_mutex();
  controller_map();
}

__attribute__((visibility("hidden"))) static void
send_vibration(int strong, int weak, uint16_t duration_ms, uint16_t slot) {
  if (!fake_vibration_enabled())
    return;

  // Rumble is best effort. A full Android listener backlog must never park
  // winebus (or every input hook through controller_mutex()), and a closing
  // listener must not terminate the guest with SIGPIPE.
  int sock = socket(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
  if (sock < 0)
    return;

  struct sockaddr_un addr = {};
  addr.sun_family = AF_UNIX;
  const char *name = "winlator_vibration";
  memcpy(addr.sun_path + 1, name, strlen(name));
  socklen_t addrlen = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(name);

  if (connect(sock, reinterpret_cast<struct sockaddr *>(&addr), addrlen) < 0) {
    syscall(SYS_close, sock);
    return;
  }

  uint16_t data[4];
  data[0] = static_cast<uint16_t>(strong);
  data[1] = static_cast<uint16_t>(weak);
  data[2] = duration_ms;
  data[3] = slot;
  send(sock, data, sizeof(data), MSG_DONTWAIT | MSG_NOSIGNAL);
  syscall(SYS_close, sock);
}

__attribute__((visibility("hidden"))) static void
check_ff_event(const struct input_event *ev, uint16_t slot) {
  if (ev->type != EV_FF)
    return;

  int id = ev->code;
  if (ev->value > 0) {
    auto it = ff_effects().find(id);
    if (it == ff_effects().end())
      return;

    uint16_t duration = it->second.replay.length;
    if (it->second.type == FF_RUMBLE) {
      send_vibration(it->second.u.rumble.strong_magnitude,
                     it->second.u.rumble.weak_magnitude, duration, slot);
    } else if (it->second.type == FF_PERIODIC) {
      send_vibration(it->second.u.periodic.magnitude,
                     it->second.u.periodic.magnitude, duration, slot);
    }
  } else {
    send_vibration(0, 0, 0, slot);
  }
}

__attribute__((visibility("hidden"))) char *
from_real_to_fake_path(const char *pathname) {
  const char *event = strrchr(pathname, '/') + 1;
  char *fake_path = nullptr;
  if (asprintf(&fake_path, "%s/%s", fake_hook_dir(), event) < 0)
    fake_path = nullptr;
  return fake_path;
}

__attribute__((visibility("hidden"))) static bool path_exists(const char *path) {
  return path && faccessat(AT_FDCWD, path, F_OK, 0) == 0;
}

__attribute__((visibility("hidden"))) static bool
is_fake_input_node_path(const char *pathname) {
  return pathname && (!strncmp(pathname, "/dev/input/event", 16) ||
                      !strncmp(pathname, "/dev/input/js", 13));
}

__attribute__((visibility("hidden"))) static bool
is_fake_udev_data_path(const char *pathname) {
  return pathname && fake_udev_data_dir() && *fake_udev_data_dir() &&
         !strncmp(pathname, "/run/udev/data/c13:", 19);
}

__attribute__((visibility("hidden"))) char *
from_real_to_fake_udev_data_path(const char *pathname) {
  const char *name = strrchr(pathname, '/') + 1;
  char *fake_path = nullptr;
  if (asprintf(&fake_path, "%s/%s", fake_udev_data_dir(), name) < 0)
    fake_path = nullptr;
  return fake_path;
}

__attribute__((visibility("hidden"))) const char *
get_event(const char *pathname) {
  const char *event = strrchr(pathname, '/') + 1;
  return event;
}

__attribute__((visibility("hidden"))) int get_event_number(const char *event) {
  if (!event)
    return -1;

  const char *digits = event;
  while (*digits && (*digits < '0' || *digits > '9'))
    digits++;

  return *digits ? atoi(digits) : -1;
}

__attribute__((visibility("hidden"))) static dev_t
get_fake_input_rdev(const char *event) {
  int event_number = get_event_number(event);
  if (event_number < 0)
    return makedev(FAKE_INPUT_MAJOR, 0);

  if (!strncmp(event, "event", 5))
    return makedev(FAKE_INPUT_MAJOR, FAKE_INPUT_EVENT_MINOR_BASE + event_number);
  if (!strncmp(event, "js", 2))
    return makedev(FAKE_INPUT_MAJOR, FAKE_INPUT_JS_MINOR_BASE + event_number);

  return makedev(FAKE_INPUT_MAJOR, event_number);
}

__attribute__((visibility("hidden"))) static void load_ring_paths() {
  std::lock_guard<std::recursive_mutex> guard(controller_mutex());
  if (ring_paths_loaded)
    return;

  const char *spec = getenv("FAKE_EVDEV_MEMFD_PATHS");
  if (!spec || !*spec) {
    ring_paths_loaded = true;
    return;
  }

  char *copy = strdup(spec);
  if (!copy)
    return;

  char *saveptr = nullptr;
  for (char *token = strtok_r(copy, ";", &saveptr); token;
       token = strtok_r(nullptr, ";", &saveptr)) {
    char *equals = strchr(token, '=');
    if (!equals)
      continue;
    *equals = '\0';
    int slot = atoi(token);
    const char *path = equals + 1;
    if (slot >= 0 && *path)
      ring_paths()[slot] = path;
  }

  free(copy);
  ring_paths_loaded = true;
}

__attribute__((visibility("hidden"))) static std::string
get_ring_path_for_slot(int slot) {
  std::lock_guard<std::recursive_mutex> guard(controller_mutex());
  load_ring_paths();
  auto it = ring_paths().find(slot);
  return it == ring_paths().end() ? std::string() : it->second;
}

__attribute__((visibility("hidden"))) static uint64_t
ring_write_seq(const FakeInputRingHeader *ring) {
  return __atomic_load_n(&ring->write_seq, __ATOMIC_ACQUIRE);
}

__attribute__((visibility("hidden"))) static uint64_t
ring_generation(const FakeInputRingHeader *ring) {
  return __atomic_load_n(&ring->generation, __ATOMIC_ACQUIRE);
}

__attribute__((visibility("hidden"))) static uint32_t
ring_resync_seq(const FakeInputRingHeader *ring) {
  return __atomic_load_n(&ring->resync_seq, __ATOMIC_ACQUIRE);
}

__attribute__((visibility("hidden"))) static bool
ring_header_is_valid(const FakeInputRingHeader *ring) {
  return ring && ring->magic == FAKE_INPUT_RING_MAGIC &&
         ring->version == FAKE_INPUT_RING_VERSION &&
         ring->event_size == FAKE_INPUT_EVENT_SIZE &&
         ring->capacity == FAKE_INPUT_RING_CAPACITY;
}

struct SnapshotState {
  uint64_t sequence = 0;
  uint64_t write_seq = 0;
  uint64_t generation = 0;
  uint32_t resync_seq = 0;
  uint32_t buttons = 0;
  int32_t axes[8] = {};
};

static long long monotonic_ms();

// The writer protects the events, cursor and snapshot in one publication.
// A busy writer is retried later; inventing a neutral state would lose holds.
__attribute__((visibility("hidden"))) static bool
read_snapshot(const FakeInputRingHeader *ring, SnapshotState &out) {
  for (int attempt = 0; attempt < 8; attempt++) {
    uint64_t sequence = __atomic_load_n(&ring->snapshot_seq, __ATOMIC_ACQUIRE);
    if (sequence & 1ULL) continue;
    out.sequence = sequence;
    out.write_seq = ring_write_seq(ring);
    out.generation = ring_generation(ring);
    out.resync_seq = ring_resync_seq(ring);
    out.buttons = __atomic_load_n(&ring->snapshot_buttons, __ATOMIC_RELAXED);
    for (int i = 0; i < 8; i++)
      out.axes[i] = __atomic_load_n(&ring->snapshot_axes[i], __ATOMIC_RELAXED);
    __atomic_thread_fence(__ATOMIC_ACQUIRE);
    if (sequence == __atomic_load_n(&ring->snapshot_seq, __ATOMIC_RELAXED))
      return true;
  }
  return false;
}

// This baseline supersedes all deltas through its cursor. Replaying older
// deltas after it can reassert a control that the snapshot already released.
__attribute__((visibility("hidden"))) static void
capture_keyframe(FakeController &fake, const SnapshotState &snap) {
  fake.keyframe_buttons = snap.buttons;
  for (int i = 0; i < 8; i++) fake.keyframe_axes[i] = snap.axes[i];
  fake.keyframe_remaining = kNeutralEventCount;
  fake.read_seq = snap.write_seq;
  fake.resync_seq = snap.resync_seq;
  fake.needs_keyframe = false;
}

// Resolve the value a keyframe event should carry from the captured snapshot.
__attribute__((visibility("hidden"))) static int32_t
keyframe_value(const FakeController &fake, uint16_t type, uint16_t code) {
  if (type == EV_KEY) {
    for (size_t i = 0; i < kSnapshotButtonCount; i++)
      if (kSnapshotButtons[i] == code)
        return (fake.keyframe_buttons >> i) & 1u;
    return 0; // a code the snapshot word does not carry
  }
  if (type == EV_ABS) {
    for (int i = 0; i < 8; i++)
      if (kSnapshotAxisCodes[i] == code)
        return fake.keyframe_axes[i];
  }
  return 0; // SYN / unknown
}

// A virtual pad's ring and description live beside its node, hidden from anything that lists
// event* names: /dev/input/.uinput-event<n>.
__attribute__((visibility("hidden"))) static std::string uinput_ring_path(int node) {
  return std::string(fake_hook_dir()) + "/.uinput-event" + std::to_string(node);
}

__attribute__((visibility("hidden"))) static bool process_alive(pid_t pid) {
  return pid > 0 && (kill(pid, 0) == 0 || errno == EPERM);
}

__attribute__((visibility("hidden"))) static int
open_fake_input_ring(const char *event, int flags) {
  int slot = get_event_number(event);
  bool virtual_pad = slot >= UINPUT_EVENT_BASE;
  std::string ring_path = virtual_pad ? uinput_ring_path(slot) : get_ring_path_for_slot(slot);
  size_t mapping_size = virtual_pad ? UINPUT_FILE_SIZE : FAKE_INPUT_RING_SIZE;
  if (ring_path.empty()) {
    Logger::log("No input ring is configured for %s (slot %d)\n", event, slot);
    errno = ENODEV;
    return -1;
  }

  static auto my_open = reinterpret_cast<int (*)(const char *, int, ...)>(dlsym(RTLD_NEXT, "open"));

  int fd = my_open(ring_path.c_str(), O_RDWR | (flags & (O_NONBLOCK | O_CLOEXEC)));
  if (fd < 0)
    return -1;

  void *mapping = mmap(nullptr, mapping_size, PROT_READ, MAP_SHARED, fd, 0);
  if (mapping == MAP_FAILED) {
    int saved_errno = errno;
    syscall(SYS_close, fd);
    errno = saved_errno;
    return -1;
  }

  FakeInputRingHeader *ring =
      reinterpret_cast<FakeInputRingHeader *>(mapping);
  const UinputDescription *description =
      virtual_pad ? reinterpret_cast<const UinputDescription *>(
                        static_cast<const uint8_t *>(mapping) + FAKE_INPUT_RING_SIZE)
                  : nullptr;
  // A virtual pad whose maker has gone (a crashed client leaves its files) is not there.
  if (!ring_header_is_valid(ring) ||
      (description && (description->magic != UINPUT_DESCRIPTION_MAGIC ||
                       !process_alive(description->owner_pid)))) {
    Logger::log("Input ring %s has no valid header\n", ring_path.c_str());
    munmap(mapping, mapping_size);
    syscall(SYS_close, fd);
    errno = ENODEV;
    return -1;
  }

  auto controller = std::make_shared<FakeController>();
  controller->event = strdup(event);
  controller->slot = slot;
  controller->ring = ring;
  controller->uinput = description;
  controller->mapping_size = mapping_size;
  controller->generation = ring_generation(ring);
  // Establish the cursor at open so taps arriving before the first read stay
  // queued. If publication is busy, read() will finish establishing it later.
  SnapshotState snap;
  if (read_snapshot(ring, snap) && snap.generation == controller->generation)
    capture_keyframe(*controller, snap);
  {
    std::lock_guard<std::recursive_mutex> guard(controller_mutex());
    controller_map()[fd] = controller;
  }

  Logger::log("Adding ring-backed controller, fd %d event %s slot %d\n", fd,
              event, slot);
  return fd;
}

// Under the Steam client a pad it manages is hidden from the game: the client lists it in
// SDL_GAMECONTROLLER_IGNORE_DEVICES, its overlay refuses the game's open() of the node, and the
// game is meant to see Steam Input's virtual gamepad instead. That one is a uinput device, which
// does not exist here, so the game was left with no controller at all. With
// FAKE_EVDEV_STEAM_VIRTUAL set the pad carries the virtual gamepad's identity for every process
// but the client's own, which must keep seeing the pad as the physical one it reads.
// Whether this process is the Steam client itself. Asked once: a process never becomes another.
__attribute__((visibility("hidden"))) static bool is_steam_client() {
  static int known;
  if (known == 0) {
    char exe[PATH_MAX];
    ssize_t length = readlink("/proc/self/exe", exe, sizeof(exe) - 1);
    bool client = false;
    if (length > 0) {
      exe[length] = '\0';
      const char *name = strrchr(exe, '/');
      client = !strncmp(name ? name + 1 : exe, "steam", 5);
    }
    known = client ? 1 : -1;
  }
  return known == 1;
}

__attribute__((visibility("hidden"))) static bool presents_steam_virtual() {
  static int known;
  if (known == 0) {
    const char *enabled = getenv("FAKE_EVDEV_STEAM_VIRTUAL");
    known = enabled && atoi(enabled) && !is_steam_client() ? 1 : -1;
  }
  return known == 1;
}

// The virtual gamepad is an X-Box 360 pad, whose triggers are ABS_Z and ABS_RZ. Wine and SDL place
// a gamepad's axes by their position among the ones it advertises, so the triggers have to sit
// where that pad has them: as ABS_GAS and ABS_BRAKE they follow the sticks, the right stick
// lands on the left trigger, and a released right trigger reads as the right stick held up. That
// is every reader but the Steam client, which maps the pad from what it finds (controller.txt)
// and has always been shown ABS_GAS/ABS_BRAKE: a game Steam Input is off for reads this pad
// itself (the Xbox 360 setting), and so do the desktop's programs. A pad made through
// /dev/uinput is that pad for real, whoever reads it.
__attribute__((visibility("hidden"))) static bool
presents_xbox_triggers(const FakeController &fake) {
  return fake.uinput || !is_steam_client();
}

__attribute__((visibility("hidden"))) static uint16_t
presented_abs_code(const FakeController &fake, uint16_t code) {
  if (!presents_xbox_triggers(fake)) return code;
  if (code == ABS_BRAKE) return ABS_Z;
  if (code == ABS_GAS) return ABS_RZ;
  return code;
}

// The snapshot keeps the triggers in the ABS_GAS/ABS_BRAKE places either way.
__attribute__((visibility("hidden"))) static uint16_t snapshot_abs_code(uint16_t code) {
  if (code == ABS_Z) return ABS_BRAKE;
  if (code == ABS_RZ) return ABS_GAS;
  return code;
}

__attribute__((visibility("hidden"))) static uint16_t
ring_abs_code(const FakeController &fake, uint16_t code) {
  return presents_xbox_triggers(fake) ? snapshot_abs_code(code) : code;
}

__attribute__((visibility("hidden"))) static void
copy_slot_ioctl_string(int op, void *argp, const char *format, int event_number) {
  size_t size = _IOC_SIZE(op);
  if (!argp || size == 0)
    return;

  snprintf(static_cast<char *>(argp), size, format, event_number);
}

__attribute__((visibility("hidden"))) static bool is_fake_input_fd(int fd) {
  std::lock_guard<std::recursive_mutex> guard(controller_mutex());
  return controller_map().find(fd) != controller_map().end();
}

__attribute__((visibility("hidden"))) static bool fake_fd_is_stale(int fd) {
  std::lock_guard<std::recursive_mutex> guard(controller_mutex());
  auto controller = controller_map().find(fd);
  return controller != controller_map().end() &&
         ring_generation(controller->second->ring) != controller->second->generation;
}

// Caller holds controller_mutex().
static bool fake_has_unread_data(const FakeController &fake) {
  if (ring_generation(fake.ring) != fake.generation)
    return false;
  // Readiness never consumes resync requests or changes the read cursor.
  // Do not spin a nonblocking guest on a producer's unfinished publication.
  if (fake.keyframe_remaining > 0) return true;
  uint64_t sequence = __atomic_load_n(&fake.ring->snapshot_seq, __ATOMIC_ACQUIRE);
  if (sequence & 1ULL) return false;
  bool ready = fake.needs_keyframe ||
               ring_resync_seq(fake.ring) != fake.resync_seq ||
               ring_write_seq(fake.ring) != fake.read_seq;
  __atomic_thread_fence(__ATOMIC_ACQUIRE);
  return ready && sequence == __atomic_load_n(&fake.ring->snapshot_seq, __ATOMIC_RELAXED);
}

static bool fake_fd_has_unread_data(int fd) {
  std::lock_guard<std::recursive_mutex> guard(controller_mutex());
  auto it = controller_map().find(fd);
  return it != controller_map().end() && fake_has_unread_data(*it->second);
}

static short fake_poll_revents(const std::shared_ptr<FakeController> &fake, short events) {
  std::lock_guard<std::recursive_mutex> guard(controller_mutex());
  if (fake->closed) return POLLNVAL;
  if (ring_generation(fake->ring) != fake->generation) return POLLHUP;
  short ready = events & (POLLOUT | POLLWRNORM);
  if ((events & (POLLIN | POLLRDNORM)) && fake_has_unread_data(*fake))
    ready |= events & (POLLIN | POLLRDNORM);
  return ready;
}

__attribute__((visibility("hidden"))) static long long
timespec_to_ms(const struct timespec *timeout) {
  if (!timeout)
    return -1;
  return static_cast<long long>(timeout->tv_sec) * 1000LL +
         timeout->tv_nsec / 1000000LL;
}

__attribute__((visibility("hidden"))) static long long
timeval_to_ms(const struct timeval *timeout) {
  if (!timeout)
    return -1;
  return static_cast<long long>(timeout->tv_sec) * 1000LL +
         timeout->tv_usec / 1000LL;
}

__attribute__((visibility("hidden"))) static long long monotonic_ms() {
  struct timespec now = {};
  clock_gettime(CLOCK_MONOTONIC, &now);
  return static_cast<long long>(now.tv_sec) * 1000LL + now.tv_nsec / 1000000LL;
}

// ---- /dev/uinput, stood in for ----
//
// Under Steam, a game is meant to read Steam Input's output, not the pad: the client hides the
// physical pad from the game (SDL_GAMECONTROLLER_IGNORE_DEVICES, and its overlay refuses the
// open) and makes it a virtual X-Box 360 pad through /dev/uinput, which it then drives with the
// layout the player chose. The sandbox has no uinput the client may use, so the virtual pad never
// appeared and every game was left reading the physical pad as-is.
//
// So /dev/uinput is answered here. A gamepad the client makes becomes another of this library's
// nodes - /dev/input/event16 and up - backed by a ring file of the same layout the app writes,
// and the client's writes are that ring's events. Any process reading the node sees the identity
// the client gave it and gets exactly what the client wrote. A device that is not a gamepad (the
// client's virtual keyboard and mouse) is accepted and its events dropped, which is what happened
// to them before.
struct UinputWriter {
  UinputDescription description{};
  bool created = false;
  bool published = false;
  int node = -1;
  pid_t owner = 0;
  FakeInputRingHeader *ring = nullptr;
  UinputDescription *shared = nullptr;
  std::vector<struct input_event> pending;
  uint32_t buttons = 0;
  int16_t axes[8] = {};
};

static std::unordered_map<int, std::shared_ptr<UinputWriter>> &uinput_map() {
  static auto *map = new std::unordered_map<int, std::shared_ptr<UinputWriter>>();
  return *map;
}

__attribute__((visibility("hidden"))) static bool is_uinput_path(const char *pathname) {
  return pathname && (!strcmp(pathname, "/dev/uinput") || !strcmp(pathname, "/dev/input/uinput"));
}

__attribute__((visibility("hidden"))) static void set_bit(uint8_t *bits, size_t size, int bit) {
  if (bit >= 0 && static_cast<size_t>(bit) / 8 < size) bits[bit / 8] |= 1u << (bit % 8);
}

__attribute__((visibility("hidden"))) static bool test_bit(const uint8_t *bits, size_t size, int bit) {
  return bit >= 0 && static_cast<size_t>(bit) / 8 < size && (bits[bit / 8] >> (bit % 8)) & 1u;
}

// A real uinput fd reads EV_UINPUT requests and polls as writable. An eventfd that is never
// signalled does both: nothing to read, always writable, blocking or not as the caller asked.
__attribute__((visibility("hidden"))) static int open_uinput(int flags) {
  int fd = eventfd(0, ((flags & O_NONBLOCK) ? EFD_NONBLOCK : 0) | ((flags & O_CLOEXEC) ? EFD_CLOEXEC : 0));
  if (fd < 0) return -1;
  auto writer = std::make_shared<UinputWriter>();
  writer->owner = getpid();
  std::lock_guard<std::recursive_mutex> guard(controller_mutex());
  uinput_map()[fd] = writer;
  Logger::log("uinput: opened as fd %d\n", fd);
  return fd;
}

// The layout the client asks for only matters to readers; what makes it a pad is a south button
// and a left stick, which is also what every pad the ring format carries has.
__attribute__((visibility("hidden"))) static bool is_gamepad(const UinputDescription &d) {
  return test_bit(d.evbits, sizeof(d.evbits), EV_KEY) && test_bit(d.keybits, sizeof(d.keybits), BTN_SOUTH) &&
         test_bit(d.evbits, sizeof(d.evbits), EV_ABS) && test_bit(d.absbits, sizeof(d.absbits), ABS_X);
}

__attribute__((visibility("hidden"))) static void unpublish_uinput(UinputWriter &w) {
  if (!w.published) return;
  // Readers holding the mapping see the generation move and report the device gone; the node's
  // removal is what tells SDL and Wine through inotify.
  __atomic_add_fetch(&w.ring->generation, 1, __ATOMIC_RELEASE);
  std::string node = std::string(fake_hook_dir()) + "/event" + std::to_string(w.node);
  unlink(node.c_str());
  unlink((uinput_ring_path(w.node) + ".uevent").c_str());
  unlink(uinput_ring_path(w.node).c_str());
  munmap(w.ring, UINPUT_FILE_SIZE);
  Logger::log("uinput: removed event%d\n", w.node);
  w.ring = nullptr;
  w.shared = nullptr;
  w.published = false;
}

// A node number is claimed by creating its ring file exclusively, so two processes making pads
// at once cannot take the same one. A file left by a process that has gone is reclaimed.
__attribute__((visibility("hidden"))) static int claim_uinput_node(int *fd_out) {
  static auto my_open = reinterpret_cast<int (*)(const char *, int, ...)>(dlsym(RTLD_NEXT, "open"));
  for (int node = UINPUT_EVENT_BASE; node < UINPUT_EVENT_LIMIT; node++) {
    std::string path = uinput_ring_path(node);
    int fd = my_open(path.c_str(), O_RDWR | O_CREAT | O_EXCL | O_CLOEXEC, 0644);
    if (fd >= 0) {
      *fd_out = fd;
      return node;
    }
    if (errno != EEXIST) return -1;
    int existing = my_open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (existing < 0) continue;
    UinputDescription old{};
    bool stale = pread(existing, &old, sizeof(old), FAKE_INPUT_RING_SIZE) == sizeof(old) &&
                 old.magic == UINPUT_DESCRIPTION_MAGIC && !process_alive(old.owner_pid);
    syscall(SYS_close, existing);
    if (stale) {
      unlink((std::string(fake_hook_dir()) + "/event" + std::to_string(node)).c_str());
      unlink((path + ".uevent").c_str());
      unlink(path.c_str());
      node--;  // try the same number again
    }
  }
  errno = ENOSPC;
  return -1;
}

__attribute__((visibility("hidden"))) static int publish_uinput(UinputWriter &w) {
  static auto my_open = reinterpret_cast<int (*)(const char *, int, ...)>(dlsym(RTLD_NEXT, "open"));
  int fd = -1;
  int node = claim_uinput_node(&fd);
  if (node < 0) return -1;
  void *mapping = MAP_FAILED;
  if (ftruncate(fd, UINPUT_FILE_SIZE) == 0)
    mapping = mmap(nullptr, UINPUT_FILE_SIZE, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
  syscall(SYS_close, fd);
  std::string ring_path = uinput_ring_path(node);
  if (mapping == MAP_FAILED) {
    unlink(ring_path.c_str());
    return -1;
  }
  auto *ring = static_cast<FakeInputRingHeader *>(mapping);
  ring->magic = FAKE_INPUT_RING_MAGIC;
  ring->version = FAKE_INPUT_RING_VERSION;
  ring->event_size = FAKE_INPUT_EVENT_SIZE;
  ring->capacity = FAKE_INPUT_RING_CAPACITY;
  ring->generation = 1;
  auto *shared = reinterpret_cast<UinputDescription *>(static_cast<uint8_t *>(mapping) + FAKE_INPUT_RING_SIZE);
  *shared = w.description;
  shared->magic = UINPUT_DESCRIPTION_MAGIC;
  shared->owner_pid = w.owner;
  __atomic_thread_fence(__ATOMIC_RELEASE);

  // The identity Wine's HID bus reads through libudev (inputudev.c in libblsession), written
  // before the node so the first lookup finds it.
  char uevent[256];
  int length = snprintf(uevent, sizeof(uevent), "PRODUCT=%x/%x/%x/%x\nNAME=\"%s\"\nPHYS=\"%s\"\n",
                        shared->id.bustype, shared->id.vendor, shared->id.product, shared->id.version,
                        shared->name, shared->phys);
  int uevent_fd = my_open((ring_path + ".uevent").c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
  if (uevent_fd >= 0) {
    if (write(uevent_fd, uevent, std::min<size_t>(length, sizeof(uevent) - 1)) < 0) {}
    syscall(SYS_close, uevent_fd);
  }
  std::string node_path = std::string(fake_hook_dir()) + "/event" + std::to_string(node);
  int node_fd = my_open(node_path.c_str(), O_WRONLY | O_CREAT | O_CLOEXEC, 0644);
  if (node_fd < 0) {
    munmap(mapping, UINPUT_FILE_SIZE);
    unlink((ring_path + ".uevent").c_str());
    unlink(ring_path.c_str());
    return -1;
  }
  syscall(SYS_close, node_fd);
  w.node = node;
  w.ring = ring;
  w.shared = shared;
  w.published = true;
  Logger::log("uinput: \"%s\" %04x:%04x is /dev/input/event%d\n", shared->name, shared->id.vendor,
              shared->id.product, node);
  return 0;
}

// Same publication as the app's writer (FakeInputWriter.flushBufferToRing): the events, then the
// absolute state under its seqlock, then the cursor that makes both visible.
__attribute__((visibility("hidden"))) static void flush_uinput(UinputWriter &w) {
  if (!w.published || w.pending.empty()) {
    w.pending.clear();
    return;
  }
  FakeInputRingHeader *ring = w.ring;
  auto *events = reinterpret_cast<struct input_event *>(reinterpret_cast<uint8_t *>(ring) +
                                                        FAKE_INPUT_RING_HEADER_SIZE);
  uint64_t write_seq = __atomic_load_n(&ring->write_seq, __ATOMIC_RELAXED);
  struct timeval now = {};
  gettimeofday(&now, nullptr);
  for (struct input_event ev : w.pending) {
    ev.time = now;
    events[write_seq % FAKE_INPUT_RING_CAPACITY] = ev;
    write_seq++;
    if (ev.type == EV_KEY) {
      for (size_t i = 0; i < kSnapshotButtonCount; i++)
        if (kSnapshotButtons[i] == ev.code)
          w.buttons = ev.value ? (w.buttons | (1u << i)) : (w.buttons & ~(1u << i));
    } else if (ev.type == EV_ABS) {
      uint16_t code = snapshot_abs_code(ev.code);
      for (int i = 0; i < 8; i++)
        if (kSnapshotAxisCodes[i] == code)
          w.axes[i] = static_cast<int16_t>(std::max(-32768, std::min(32767, ev.value)));
    }
  }
  w.pending.clear();
  uint64_t sequence = __atomic_load_n(&ring->snapshot_seq, __ATOMIC_RELAXED);
  __atomic_store_n(&ring->snapshot_seq, sequence + 1, __ATOMIC_RELAXED);
  __atomic_thread_fence(__ATOMIC_RELEASE);
  __atomic_store_n(&ring->snapshot_buttons, w.buttons, __ATOMIC_RELAXED);
  for (int i = 0; i < 8; i++) __atomic_store_n(&ring->snapshot_axes[i], w.axes[i], __ATOMIC_RELAXED);
  __atomic_thread_fence(__ATOMIC_RELEASE);
  __atomic_store_n(&ring->snapshot_seq, sequence + 2, __ATOMIC_RELEASE);
  __atomic_store_n(&ring->write_seq, write_seq, __ATOMIC_RELEASE);
}

__attribute__((visibility("hidden"))) static ssize_t
write_uinput(UinputWriter &w, const void *buf, size_t count) {
  if (!w.created) {
    // The legacy description: written once, before UI_DEV_CREATE.
    if (count != sizeof(struct uinput_user_dev)) {
      errno = EINVAL;
      return -1;
    }
    const auto *legacy = static_cast<const struct uinput_user_dev *>(buf);
    memcpy(w.description.name, legacy->name, sizeof(w.description.name));
    w.description.name[sizeof(w.description.name) - 1] = '\0';
    w.description.id = legacy->id;
    w.description.ff_effects_max = legacy->ff_effects_max;
    for (int i = 0; i < ABS_CNT; i++) {
      w.description.absinfo[i].minimum = legacy->absmin[i];
      w.description.absinfo[i].maximum = legacy->absmax[i];
      w.description.absinfo[i].fuzz = legacy->absfuzz[i];
      w.description.absinfo[i].flat = legacy->absflat[i];
    }
    return static_cast<ssize_t>(count);
  }
  if (count % sizeof(struct input_event)) {
    errno = EINVAL;
    return -1;
  }
  const auto *events = static_cast<const struct input_event *>(buf);
  for (size_t i = 0; i < count / sizeof(struct input_event); i++) {
    w.pending.push_back(events[i]);
    // A frame ends at SYN_REPORT; a writer that never sends one still gets its events out.
    if ((events[i].type == EV_SYN && events[i].code == SYN_REPORT) || w.pending.size() >= 64)
      flush_uinput(w);
  }
  return static_cast<ssize_t>(count);
}

__attribute__((visibility("hidden"))) static int
ioctl_uinput(UinputWriter &w, ioctl_request_t op, void *argp) {
  if (_IOC_TYPE(op) != UINPUT_IOCTL_BASE) {
    errno = ENOTTY;
    return -1;
  }
  int number = _IOC_NR(op);
  int value = static_cast<int>(reinterpret_cast<intptr_t>(argp));
  UinputDescription &d = w.description;
  if (number == _IOC_NR(UI_GET_VERSION)) {
    *static_cast<unsigned int *>(argp) = 5;
    return 0;
  }
  if (number == _IOC_NR(UI_GET_SYSNAME(0))) {
    if (!w.created) {
      errno = ENOENT;
      return -1;
    }
    snprintf(static_cast<char *>(argp), _IOC_SIZE(op), "input%d", w.published ? w.node : 0);
    return 0;
  }
  if (number == _IOC_NR(UI_DEV_DESTROY)) {
    if (getpid() == w.owner) unpublish_uinput(w);
    w.created = false;
    return 0;
  }
  if (w.created && number != _IOC_NR(UI_BEGIN_FF_UPLOAD) && number != _IOC_NR(UI_END_FF_UPLOAD) &&
      number != _IOC_NR(UI_BEGIN_FF_ERASE) && number != _IOC_NR(UI_END_FF_ERASE)) {
    errno = EINVAL;  // the kernel refuses configuration once the device exists
    return -1;
  }
  switch (number) {
  case _IOC_NR(UI_SET_EVBIT): set_bit(d.evbits, sizeof(d.evbits), value); return 0;
  case _IOC_NR(UI_SET_KEYBIT): set_bit(d.keybits, sizeof(d.keybits), value); return 0;
  case _IOC_NR(UI_SET_ABSBIT): set_bit(d.absbits, sizeof(d.absbits), value); return 0;
  case _IOC_NR(UI_SET_FFBIT): set_bit(d.ffbits, sizeof(d.ffbits), value); return 0;
  case _IOC_NR(UI_SET_RELBIT):
  case _IOC_NR(UI_SET_MSCBIT):
  case _IOC_NR(UI_SET_LEDBIT):
  case _IOC_NR(UI_SET_SNDBIT):
  case _IOC_NR(UI_SET_SWBIT):
  case _IOC_NR(UI_SET_PROPBIT):
    return 0;
  case _IOC_NR(UI_SET_PHYS):
    snprintf(d.phys, sizeof(d.phys), "%s", static_cast<const char *>(argp));
    return 0;
  case _IOC_NR(UI_DEV_SETUP): {
    const auto *setup = static_cast<const struct uinput_setup *>(argp);
    d.id = setup->id;
    memcpy(d.name, setup->name, sizeof(d.name));
    d.name[sizeof(d.name) - 1] = '\0';
    d.ff_effects_max = setup->ff_effects_max;
    return 0;
  }
  case _IOC_NR(UI_ABS_SETUP): {
    const auto *setup = static_cast<const struct uinput_abs_setup *>(argp);
    if (setup->code >= ABS_CNT) {
      errno = EINVAL;
      return -1;
    }
    d.absinfo[setup->code] = setup->absinfo;
    return 0;
  }
  case _IOC_NR(UI_DEV_CREATE):
    w.created = true;
    if (!is_gamepad(d)) {
      Logger::log("uinput: \"%s\" %04x:%04x is not a gamepad; its events are dropped\n", d.name,
                  d.id.vendor, d.id.product);
      return 0;
    }
    if (publish_uinput(w) < 0) {
      Logger::log("uinput: could not publish \"%s\": %s\n", d.name, strerror(errno));
      w.created = false;
      errno = ENOMEM;
      return -1;
    }
    return 0;
  // Force feedback reaches the client only as EV_UINPUT requests on this fd, and none are ever
  // sent: a game's rumble on a virtual pad goes straight to the app (check_ff_event).
  case _IOC_NR(UI_BEGIN_FF_UPLOAD):
  case _IOC_NR(UI_END_FF_UPLOAD):
  case _IOC_NR(UI_BEGIN_FF_ERASE):
  case _IOC_NR(UI_END_FF_ERASE):
    errno = EINVAL;
    return -1;
  default:
    Logger::log("uinput: unhandled ioctl %d\n", number);
    errno = EINVAL;
    return -1;
  }
}

// Only the process that made the device takes it down: a forked child closing its inherited
// copy of the fd (as a spawn does before exec) must not remove the parent's pad.
__attribute__((visibility("hidden"))) static void close_uinput(int fd) {
  std::lock_guard<std::recursive_mutex> guard(controller_mutex());
  auto it = uinput_map().find(fd);
  if (it == uinput_map().end()) return;
  if (getpid() == it->second->owner) unpublish_uinput(*it->second);
  uinput_map().erase(it);
}

// ---- The pad as a Steam Deck controller ----
//
// On a Deck, and on the handhelds InputPlumber serves, the Steam client reads the built-in
// controller itself as a Steam Deck controller: a vendor HID device it talks to over hidraw, which
// is what gives it the Quick Access button, the back grips, gyro and trackpads, and Steam Input's
// full treatment of the pad. With FAKE_EVDEV_DECK set the app's pad in ring slot 0 is presented to
// the client that way: /dev/hidraw16, found through the sysfs tree the app binds into the session
// (SteamDeckPad), streams the Deck's 64-byte state report every 4 ms, as the real one does, built
// from the ring's absolute snapshot; its feature reports are answered as InputPlumber answers them
// for a virtual Deck. The evdev nodes of the app's pads are then withdrawn - the client would
// otherwise count the pad twice - and games get Steam Input's virtual pad (the /dev/uinput stand-in).
//
// A read returns one report and never blocks past the next one, so the fd is one end of a
// SOCK_SEQPACKET socket pair with a thread writing reports into the other end: poll, select and
// epoll then work on it with no help from the hooks here.
static constexpr const char *DECK_HIDRAW_PATH = "/dev/hidraw16";
static constexpr unsigned int DECK_HIDRAW_MAJOR = 240;
static constexpr unsigned int DECK_HIDRAW_MINOR = 16;
static constexpr int DECK_REPORT_BYTES = 64;
static constexpr int DECK_REPORT_INTERVAL_US = 4000;
static constexpr const char *DECK_NAME = "Valve Software Steam Deck Controller";
static constexpr const char *DECK_SERIAL = "BANNERLATOR01";

// InputPlumber, src/drivers/steam_deck/report_descriptor.rs (CONTROLLER_DESCRIPTOR): one 64-byte
// vendor input report and one 64-byte feature report, no report ids.
static const uint8_t kDeckReportDescriptor[] = {
    0x06, 0xff, 0xff, 0x09, 0x01, 0xa1, 0x01, 0x09, 0x02, 0x09, 0x03, 0x15, 0x00,
    0x26, 0xff, 0x00, 0x75, 0x08, 0x95, 0x40, 0x81, 0x02, 0x09, 0x06, 0x09, 0x07,
    0x15, 0x00, 0x26, 0xff, 0x00, 0x75, 0x08, 0x95, 0x40, 0xb1, 0x02, 0xc0};

// Feature report ids (SDL, src/joystick/hidapi/steam/controller_constants.h).
static constexpr uint8_t DECK_GET_ATTRIBUTES_VALUES = 0x83;
static constexpr uint8_t DECK_GET_STRING_ATTRIBUTE = 0xAE;
static constexpr uint8_t DECK_GET_CHIP_ID = 0xBA;
static constexpr uint8_t DECK_TRIGGER_RUMBLE_CMD = 0xEB;

// Button bits of the state report (SDL, SDL_hidapi_steamdeck.c): the low word, then the high.
static constexpr uint32_t DECK_L_R2 = 0x00000001, DECK_L_L2 = 0x00000002, DECK_L_R1 = 0x00000004,
                          DECK_L_L1 = 0x00000008, DECK_L_Y = 0x00000010, DECK_L_B = 0x00000020,
                          DECK_L_X = 0x00000040, DECK_L_A = 0x00000080, DECK_L_UP = 0x00000100,
                          DECK_L_RIGHT = 0x00000200, DECK_L_LEFT = 0x00000400,
                          DECK_L_DOWN = 0x00000800, DECK_L_VIEW = 0x00001000,
                          DECK_L_STEAM = 0x00002000, DECK_L_MENU = 0x00004000,
                          DECK_L_L3 = 0x00400000, DECK_L_R3 = 0x04000000;
static constexpr uint32_t DECK_L_L5 = 0x00008000, DECK_L_R5 = 0x00010000,
                          DECK_L_LEFT_PAD = 0x00020000, DECK_L_RIGHT_PAD = 0x00040000,
                          DECK_L_LEFT_TOUCH = 0x00080000, DECK_L_RIGHT_TOUCH = 0x00100000;
static constexpr uint32_t DECK_H_L4 = 0x00000200, DECK_H_R4 = 0x00000400;
static constexpr uint32_t DECK_H_QAM = 0x00040000;
// Bit 11 of the ring snapshot's button word: the Quick Access button, which an Xbox pad does not
// have and the evdev nodes therefore never report (FakeInputWriter.SNAPSHOT_IDX_QAM).
static constexpr uint32_t SNAPSHOT_QAM_BIT = 1u << 11;

struct DeckHidraw {
  int peer = -1;
  FakeInputRingHeader *ring = nullptr;
  size_t mapping_size = 0;
  uint8_t pending_feature = 0;
};

// What the Deck has and an Xbox pad does not, after the ring's events: motion and the back grips
// and trackpads, already in the Deck's axes and units, under a seqlock (FakeInputWriter.writeMotion /
// writeDeckControls). Absent from a ring file written without one, which leaves them at rest.
struct DeckImu {
  uint32_t magic;  // IMU1 once written
  uint32_t reserved;
  uint64_t seq;    // odd while being written
  int16_t accel[3];
  int16_t gyro[3];
  int16_t pads[4];         // left X, Y, right X, Y: -32768..32767, Y up
  uint16_t pressure[2];    // left, right: 0..32767
  uint32_t controls;       // DECK_EXTRA_* bits
};
// DeckImu::controls; the app's writer uses the same bits.
static constexpr uint32_t DECK_EXTRA_L4 = 1, DECK_EXTRA_R4 = 2, DECK_EXTRA_L5 = 4,
                          DECK_EXTRA_R5 = 8, DECK_EXTRA_LPAD_TOUCH = 16,
                          DECK_EXTRA_RPAD_TOUCH = 32, DECK_EXTRA_LPAD_CLICK = 64,
                          DECK_EXTRA_RPAD_CLICK = 128;

struct DeckExtras {
  int16_t accel[3] = {};
  int16_t gyro[3] = {};
  int16_t pads[4] = {};
  uint16_t pressure[2] = {};
  uint32_t controls = 0;
};
static constexpr uint32_t DECK_IMU_MAGIC = 0x31554D49;
static constexpr size_t DECK_IMU_BLOCK_SIZE = 64;
static_assert(sizeof(DeckImu) <= DECK_IMU_BLOCK_SIZE, "the IMU block is 64 bytes in the ring file");

__attribute__((visibility("hidden"))) static bool read_deck_extras(const DeckHidraw &deck, DeckExtras &out) {
  if (deck.mapping_size < FAKE_INPUT_RING_SIZE + DECK_IMU_BLOCK_SIZE) return false;
  const auto *imu = reinterpret_cast<const DeckImu *>(reinterpret_cast<const uint8_t *>(deck.ring) +
                                                      FAKE_INPUT_RING_SIZE);
  for (int attempt = 0; attempt < 4; attempt++) {
    uint64_t seq = __atomic_load_n(&imu->seq, __ATOMIC_ACQUIRE);
    if (seq & 1) continue;
    if (__atomic_load_n(&imu->magic, __ATOMIC_RELAXED) != DECK_IMU_MAGIC) return false;
    DeckExtras read;
    for (int i = 0; i < 3; i++) {
      read.accel[i] = __atomic_load_n(&imu->accel[i], __ATOMIC_RELAXED);
      read.gyro[i] = __atomic_load_n(&imu->gyro[i], __ATOMIC_RELAXED);
    }
    for (int i = 0; i < 4; i++) read.pads[i] = __atomic_load_n(&imu->pads[i], __ATOMIC_RELAXED);
    for (int i = 0; i < 2; i++) read.pressure[i] = __atomic_load_n(&imu->pressure[i], __ATOMIC_RELAXED);
    read.controls = __atomic_load_n(&imu->controls, __ATOMIC_RELAXED);
    __atomic_thread_fence(__ATOMIC_ACQUIRE);
    if (seq == __atomic_load_n(&imu->seq, __ATOMIC_RELAXED)) {
      out = read;
      return true;
    }
  }
  return false;
}

static std::unordered_map<int, std::shared_ptr<DeckHidraw>> &deck_map() {
  static auto *map = new std::unordered_map<int, std::shared_ptr<DeckHidraw>>();
  return *map;
}

static bool fake_deck_enabled() {
  static int enabled = -1;
  if (enabled < 0) enabled = getenv("FAKE_EVDEV_DECK") && atoi(getenv("FAKE_EVDEV_DECK")) ? 1 : 0;
  return enabled == 1;
}

// The Deck is the client's alone: a game that found it would have the pad twice, once through it
// and once through Steam Input's virtual pad.
__attribute__((visibility("hidden"))) static bool process_is_steam_client() {
  static int known;
  if (known == 0) {
    char exe[PATH_MAX];
    ssize_t length = readlink("/proc/self/exe", exe, sizeof(exe) - 1);
    bool client = false;
    if (length > 0) {
      exe[length] = '\0';
      const char *name = strrchr(exe, '/');
      client = !strcmp(name ? name + 1 : exe, "steam");
    }
    known = client ? 1 : -1;
  }
  return known == 1;
}

// What the client starts with, so a pad log says whether it was offered a Deck at all.
__attribute__((constructor)) static void log_client_start() {
  config();
  if (process_is_steam_client())
    Logger::log("loaded in the Steam client: deck %s, ring slot 0 %s\n", fake_deck_enabled() ? "on" : "off",
                get_ring_path_for_slot(0).empty() ? "(none configured)" : get_ring_path_for_slot(0).c_str());
}

__attribute__((visibility("hidden"))) static bool is_deck_hidraw_path(const char *pathname) {
  return pathname && fake_deck_enabled() && !strcmp(pathname, DECK_HIDRAW_PATH);
}

// The app's own pads are the Deck now; their evdev nodes are withdrawn from everyone.
__attribute__((visibility("hidden"))) static bool is_withdrawn_pad_path(const char *pathname) {
  if (!fake_deck_enabled() || !pathname || strncmp(pathname, "/dev/input/", 11)) return false;
  const char *event = strrchr(pathname, '/') + 1;
  if (strncmp(event, "event", 5) && strncmp(event, "js", 2)) return false;
  int number = get_event_number(event);
  return number >= 0 && number < UINPUT_EVENT_BASE;
}

static inline void put16(uint8_t *at, int value) {
  at[0] = static_cast<uint8_t>(value & 0xff);
  at[1] = static_cast<uint8_t>((value >> 8) & 0xff);
}

static inline void put32(uint8_t *at, uint32_t value) {
  for (int i = 0; i < 4; i++) at[i] = static_cast<uint8_t>(value >> (8 * i));
}

// The Deck's state report (SDL, controller_structs.h: ValveInReportHeader_t then
// SteamDeckStatePacket_t) from the ring's snapshot: kSnapshotButtons order, kSnapshotAxisCodes
// order, sticks as evdev has them (Y down), triggers 0..255.
__attribute__((visibility("hidden"))) static void
build_deck_report(uint8_t *report, uint32_t packet, uint32_t buttons, const int32_t *axes,
                  const DeckExtras &extras) {
  memset(report, 0, DECK_REPORT_BYTES);
  report[0] = 0x01;  // report version
  report[2] = 0x09;  // ID_CONTROLLER_DECK_STATE
  report[3] = DECK_REPORT_BYTES;
  put32(report + 4, packet);
  static const uint32_t kButtonBits[11] = {DECK_L_A,    DECK_L_B,    DECK_L_X,    DECK_L_Y,
                                           DECK_L_L1,   DECK_L_R1,   DECK_L_VIEW, DECK_L_MENU,
                                           DECK_L_L3,   DECK_L_R3,   DECK_L_STEAM};
  uint32_t low = 0, high = 0;
  for (int i = 0; i < 11; i++)
    if (buttons & (1u << i)) low |= kButtonBits[i];
  if (buttons & SNAPSHOT_QAM_BIT) high |= DECK_H_QAM;
  int32_t hat_x = axes[6], hat_y = axes[7];
  if (hat_y < 0) low |= DECK_L_UP;
  if (hat_y > 0) low |= DECK_L_DOWN;
  if (hat_x < 0) low |= DECK_L_LEFT;
  if (hat_x > 0) low |= DECK_L_RIGHT;
  int32_t left_trigger = axes[5], right_trigger = axes[4];  // ABS_BRAKE, ABS_GAS
  if (left_trigger > 127) low |= DECK_L_L2;
  if (right_trigger > 127) low |= DECK_L_R2;
  uint32_t controls = extras.controls;
  if (controls & DECK_EXTRA_L4) high |= DECK_H_L4;
  if (controls & DECK_EXTRA_R4) high |= DECK_H_R4;
  if (controls & DECK_EXTRA_L5) low |= DECK_L_L5;
  if (controls & DECK_EXTRA_R5) low |= DECK_L_R5;
  if (controls & DECK_EXTRA_LPAD_TOUCH) low |= DECK_L_LEFT_TOUCH;
  if (controls & DECK_EXTRA_RPAD_TOUCH) low |= DECK_L_RIGHT_TOUCH;
  if (controls & DECK_EXTRA_LPAD_CLICK) low |= DECK_L_LEFT_PAD;
  if (controls & DECK_EXTRA_RPAD_CLICK) low |= DECK_L_RIGHT_PAD;
  put32(report + 8, low);
  put32(report + 12, high);
  for (int i = 0; i < 4; i++) put16(report + 16 + i * 2, extras.pads[i]);  // s{Left,Right}PadX/Y
  for (int i = 0; i < 3; i++) {
    put16(report + 24 + i * 2, extras.accel[i]);  // sAccelX/Y/Z
    put16(report + 30 + i * 2, extras.gyro[i]);   // sGyroX/Y/Z
  }
  put16(report + 56, extras.pressure[0]);  // sPressurePadLeft
  put16(report + 58, extras.pressure[1]);  // sPressurePadRight
  put16(report + 44, std::min(32767, left_trigger * 32767 / 255));
  put16(report + 46, std::min(32767, right_trigger * 32767 / 255));
  auto stick = [](int32_t value) { return std::max(-32767, std::min(32767, value)); };
  put16(report + 48, stick(axes[0]));
  put16(report + 50, stick(-axes[1]));
  put16(report + 52, stick(axes[2]));
  put16(report + 54, stick(-axes[3]));
}

__attribute__((visibility("hidden"))) static void *deck_report_thread(void *arg) {
  auto *deck = static_cast<std::shared_ptr<DeckHidraw> *>(arg);
  std::shared_ptr<DeckHidraw> self = *deck;
  delete deck;
  uint32_t packet = 0;
  SnapshotState snap;
  uint32_t buttons = 0;
  int32_t axes[8] = {};
  DeckExtras extras;
  for (;;) {
    if (read_snapshot(self->ring, snap) && snap.generation == ring_generation(self->ring)) {
      buttons = snap.buttons;
      memcpy(axes, snap.axes, sizeof(axes));
    }
    read_deck_extras(*self, extras);
    uint8_t report[DECK_REPORT_BYTES];
    build_deck_report(report, ++packet, buttons, axes, extras);
    // A reader that fell behind loses reports rather than getting stale ones later; a reader that
    // has closed ends the stream.
    if (send(self->peer, report, sizeof(report), MSG_DONTWAIT | MSG_NOSIGNAL) < 0 &&
        errno != EAGAIN && errno != EWOULDBLOCK)
      break;
    struct timespec interval = {0, DECK_REPORT_INTERVAL_US * 1000L};
    nanosleep(&interval, nullptr);
  }
  syscall(SYS_close, self->peer);
  munmap(self->ring, self->mapping_size);
  return nullptr;
}

__attribute__((visibility("hidden"))) static int open_deck_hidraw(int flags) {
  static auto my_open = reinterpret_cast<int (*)(const char *, int, ...)>(dlsym(RTLD_NEXT, "open"));
  if (!process_is_steam_client()) {
    // Only the client reads the Deck; a game sees Steam Input's virtual pad instead.
    Logger::log("deck: %s refused: not the Steam client\n", DECK_HIDRAW_PATH);
    errno = ENOENT;
    return -1;
  }
  std::string ring_path = get_ring_path_for_slot(0);
  int ring_fd = ring_path.empty() ? -1 : my_open(ring_path.c_str(), O_RDONLY | O_CLOEXEC);
  if (ring_fd < 0) {
    Logger::log("deck: %s refused: ring %s not openable (%s)\n", DECK_HIDRAW_PATH,
                ring_path.empty() ? "(none configured)" : ring_path.c_str(), strerror(errno));
    errno = ENODEV;
    return -1;
  }
  // The IMU block is mapped only where the file has one: past its end a mapping faults.
  struct stat ring_stat;
  size_t mapping_size = FAKE_INPUT_RING_SIZE;
  if (fstat(ring_fd, &ring_stat) == 0 &&
      static_cast<size_t>(ring_stat.st_size) >= FAKE_INPUT_RING_SIZE + DECK_IMU_BLOCK_SIZE)
    mapping_size = FAKE_INPUT_RING_SIZE + DECK_IMU_BLOCK_SIZE;
  void *mapping = mmap(nullptr, mapping_size, PROT_READ, MAP_SHARED, ring_fd, 0);
  syscall(SYS_close, ring_fd);
  if (mapping == MAP_FAILED || !ring_header_is_valid(static_cast<FakeInputRingHeader *>(mapping))) {
    Logger::log("deck: %s refused: ring %s %s\n", DECK_HIDRAW_PATH, ring_path.c_str(),
                mapping == MAP_FAILED ? "could not be mapped" : "has no valid header");
    if (mapping != MAP_FAILED) munmap(mapping, mapping_size);
    errno = ENODEV;
    return -1;
  }
  int pair[2];
  if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, pair) < 0) {
    Logger::log("deck: %s refused: socketpair failed (%s)\n", DECK_HIDRAW_PATH, strerror(errno));
    munmap(mapping, mapping_size);
    return -1;
  }
  // Room for a few reports only, so what the client reads is never more than a few ms old.
  int buffer = DECK_REPORT_BYTES * 4;
  setsockopt(pair[1], SOL_SOCKET, SO_SNDBUF, &buffer, sizeof(buffer));
  if (!(flags & O_CLOEXEC)) fcntl(pair[0], F_SETFD, 0);
  if (flags & O_NONBLOCK) fcntl(pair[0], F_SETFL, fcntl(pair[0], F_GETFL) | O_NONBLOCK);
  auto deck = std::make_shared<DeckHidraw>();
  deck->peer = pair[1];
  deck->ring = static_cast<FakeInputRingHeader *>(mapping);
  deck->mapping_size = mapping_size;
  {
    std::lock_guard<std::recursive_mutex> guard(controller_mutex());
    deck_map()[pair[0]] = deck;
  }
  pthread_t thread;
  auto *arg = new std::shared_ptr<DeckHidraw>(deck);
  if (pthread_create(&thread, nullptr, deck_report_thread, arg) != 0) {
    delete arg;
    std::lock_guard<std::recursive_mutex> guard(controller_mutex());
    deck_map().erase(pair[0]);
    syscall(SYS_close, pair[0]);
    syscall(SYS_close, pair[1]);
    munmap(mapping, mapping_size);
    errno = ENOMEM;
    return -1;
  }
  pthread_detach(thread);
  Logger::log("deck: %s opened as fd %d\n", DECK_HIDRAW_PATH, pair[0]);
  return pair[0];
}

// libudev (systemd's sd-device) accepts a device only where its directory is on sysfs, which it
// checks with fstatfs on the directory it resolved. The Deck's sysfs is the app's own files bound
// into /sys, so those paths are said to be sysfs; nothing else is touched.
static constexpr long SYSFS_FS_MAGIC = 0x62656572;

// FAKE_DECK_SYSFS_LISTING: stand-ins for the listings of /sys, /sys/class and /sys/bus
// (SteamDeckPad.listingDir), for a client that is not allowed to list the real ones.
__attribute__((visibility("hidden"))) static const char *deck_listing_dir() {
  static const char *dir = getenv("FAKE_DECK_SYSFS_LISTING");
  return dir && *dir ? dir : nullptr;
}

__attribute__((visibility("hidden"))) static bool is_deck_sysfs_path(const char *path) {
  if (!path) return false;
  if (!strncmp(path, "/sys/devices/bannerlator", 24) || !strncmp(path, "/sys/class/hidraw", 17)) return true;
  const char *listing = deck_listing_dir();
  return listing && !strncmp(path, listing, strlen(listing));
}

__attribute__((visibility("hidden"))) static bool is_deck_sysfs_fd(int fd) {
  char link[64], path[PATH_MAX];
  snprintf(link, sizeof(link), "/proc/self/fd/%d", fd);
  ssize_t length = readlink(link, path, sizeof(path) - 1);
  if (length <= 0) return false;
  path[length] = '\0';
  return is_deck_sysfs_path(path);
}

// Once per process: proof the client's enumeration reached the Deck's sysfs at all. No line here
// and no open of /dev/hidraw16 means the client never looked; a line here and no open means it
// looked and turned the device down.
__attribute__((visibility("hidden"))) static void note_deck_sysfs(const char *how, int result) {
  static std::atomic<bool> noted{false};
  if (!noted.exchange(true))
    Logger::log("deck: sysfs checked (%s%s)\n", how, result == 0 ? "" : ", failed");
}

EXPORT int fstatfs(int fd, struct statfs *buf) {
  static auto real = reinterpret_cast<int (*)(int, struct statfs *)>(dlsym(RTLD_NEXT, "fstatfs"));
  int result = real(fd, buf);
  if (fake_deck_enabled() && is_deck_sysfs_fd(fd)) {
    note_deck_sysfs("fstatfs", result);
    if (result == 0) buf->f_type = SYSFS_FS_MAGIC;
  }
  return result;
}

EXPORT int statfs(const char *path, struct statfs *buf) {
  static auto real = reinterpret_cast<int (*)(const char *, struct statfs *)>(dlsym(RTLD_NEXT, "statfs"));
  int result = real(path, buf);
  if (fake_deck_enabled() && is_deck_sysfs_path(path)) {
    note_deck_sysfs(path, result);
    if (result == 0) buf->f_type = SYSFS_FS_MAGIC;
  }
  return result;
}

#ifdef __GLIBC__
EXPORT int fstatfs64(int fd, struct statfs64 *buf) {
  return fstatfs(fd, reinterpret_cast<struct statfs *>(buf));
}

EXPORT int statfs64(const char *path, struct statfs64 *buf) {
  return statfs(path, reinterpret_cast<struct statfs *>(buf));
}
#endif

__attribute__((visibility("hidden"))) static int copy_ioctl_string(ioctl_request_t op, void *argp, const char *value) {
  size_t size = _IOC_SIZE(op);
  if (!argp || !size) return 0;
  snprintf(static_cast<char *>(argp), size, "%s", value);
  return static_cast<int>(std::min(size, strlen(value) + 1));
}

// Feature reports carry the report id (0) first, then the Valve message: type, length, payload.
// The client repeats some feature reports (settings, rumble) for as long as it has the pad; each
// is logged the first time and then at 2, 4, 8... so the log shows what it asked and how often
// without a line per report.
__attribute__((visibility("hidden"))) static void log_deck_feature(const char *what, uint8_t feature) {
  static std::atomic<unsigned> counts[2][256];
  unsigned n = ++counts[what[0] == 's'][feature];
  if ((n & (n - 1)) == 0) Logger::log("deck: feature 0x%02x %s (x%u)\n", feature, what, n);
}

__attribute__((visibility("hidden"))) static int
deck_feature(DeckHidraw &deck, ioctl_request_t op, uint8_t *buf, bool set) {
  size_t size = _IOC_SIZE(op);
  if (!buf || size < 2) {
    errno = EINVAL;
    return -1;
  }
  if (set) {
    deck.pending_feature = buf[1];
    if (buf[1] == DECK_TRIGGER_RUMBLE_CMD && size >= 10) {
      int left = buf[6] | buf[7] << 8, right = buf[8] | buf[9] << 8;
      send_vibration(left, right, left || right ? 1000 : 0, 0);
    }
    log_deck_feature("set", buf[1]);
    return static_cast<int>(size);
  }
  uint8_t reply[DECK_REPORT_BYTES + 1] = {};
  switch (deck.pending_feature) {
  case DECK_GET_ATTRIBUTES_VALUES: {
    // InputPlumber's capture from a real Deck (steam_deck_uhid.rs, handle_get_report).
    static const uint8_t kAttributes[] = {0x00, 0x83, 0x2d, 0x01, 0x05, 0x12, 0x00, 0x00, 0x02,
                                          0x00, 0x00, 0x00, 0x00, 0x0a, 0x2b, 0x12, 0xa9, 0x62,
                                          0x04, 0xad, 0xf1, 0xe4, 0x65, 0x09, 0x2e, 0x00, 0x00,
                                          0x00, 0x0b, 0xa0, 0x0f, 0x00, 0x00, 0x0d, 0x00, 0x00,
                                          0x00, 0x00, 0x0c, 0x00, 0x00, 0x00, 0x00, 0x0e};
    memcpy(reply, kAttributes, sizeof(kAttributes));
    break;
  }
  case DECK_GET_STRING_ATTRIBUTE:
    reply[1] = DECK_GET_STRING_ATTRIBUTE;
    reply[2] = 0x14;
    reply[3] = 0x01;
    memcpy(reply + 4, DECK_SERIAL, strlen(DECK_SERIAL));
    break;
  case DECK_GET_CHIP_ID:
    reply[1] = DECK_GET_CHIP_ID;
    reply[2] = 0x11;
    memcpy(reply + 4, "BANNERLATORCHIP", 15);
    break;
  default:
    reply[1] = deck.pending_feature;
    break;
  }
  log_deck_feature("read", deck.pending_feature);
  size_t length = std::min(size, sizeof(reply));
  memcpy(buf, reply, length);
  return static_cast<int>(length);
}

__attribute__((visibility("hidden"))) static int
ioctl_deck(DeckHidraw &deck, ioctl_request_t op, void *argp) {
  if (_IOC_TYPE(op) != 'H') {
    errno = ENOTTY;
    return -1;
  }
  switch (_IOC_NR(op)) {
  case 0x01:  // HIDIOCGRDESCSIZE
    *static_cast<int *>(argp) = sizeof(kDeckReportDescriptor);
    return 0;
  case 0x02: {  // HIDIOCGRDESC: struct hidraw_report_descriptor { __u32 size; __u8 value[4096]; }
    auto *descriptor = static_cast<uint8_t *>(argp);
    uint32_t size;
    memcpy(&size, descriptor, sizeof(size));
    memcpy(descriptor + 4, kDeckReportDescriptor, std::min<size_t>(size, sizeof(kDeckReportDescriptor)));
    return 0;
  }
  case 0x03: {  // HIDIOCGRAWINFO: struct hidraw_devinfo { __u32 bustype; __s16 vendor, product; }
    struct {
      uint32_t bustype;
      int16_t vendor;
      int16_t product;
    } info = {BUS_USB, static_cast<int16_t>(0x28de), static_cast<int16_t>(0x1205)};
    memcpy(argp, &info, sizeof(info));
    return 0;
  }
  case 0x04: return copy_ioctl_string(op, argp, DECK_NAME);                 // HIDIOCGRAWNAME
  case 0x05: return copy_ioctl_string(op, argp, "usb-bannerlator-1/input2");  // HIDIOCGRAWPHYS
  case 0x08: return copy_ioctl_string(op, argp, DECK_SERIAL);               // HIDIOCGRAWUNIQ
  case 0x06: return deck_feature(deck, op, static_cast<uint8_t *>(argp), true);   // HIDIOCSFEATURE
  case 0x07: return deck_feature(deck, op, static_cast<uint8_t *>(argp), false);  // HIDIOCGFEATURE
  default:
    Logger::log("deck: unhandled hidraw ioctl 0x%02x\n", _IOC_NR(op));
    errno = EINVAL;
    return -1;
  }
}

// A device scan that fails on a phone and not on the Thor: libudev gives up its whole scan on any
// sysfs directory it cannot read for a reason other than its absence, and a phone's SELinux policy
// denies directories a permissive device lets through. The client's /sys failures that are not
// ENOENT are logged, each path once, so pad.log names the directory that stopped the scan.
__attribute__((visibility("hidden"))) static std::string note_sys_failure(int dirfd, const char *pathname) {
  int saved_errno = errno;
  std::string path;
  if (saved_errno == ENOENT || !pathname || !process_is_steam_client()) {
    errno = saved_errno;
    return path;
  }
  if (pathname[0] == '/' || dirfd == AT_FDCWD) {
    path = pathname;
  } else {
    char link[64], dir[PATH_MAX];
    snprintf(link, sizeof(link), "/proc/self/fd/%d", dirfd);
    ssize_t length = readlink(link, dir, sizeof(dir) - 1);
    if (length <= 0) {
      errno = saved_errno;
      return path;
    }
    path.assign(dir, length);
    path += '/';
    path += pathname;
  }
  if (path.compare(0, 5, "/sys/") != 0 && path != "/sys") {
    errno = saved_errno;
    return {};
  }
  static std::mutex mutex;
  static std::vector<std::string> *seen = new std::vector<std::string>();
  bool first = false;
  {
    std::lock_guard<std::mutex> guard(mutex);
    if (seen->size() < 64 && std::find(seen->begin(), seen->end(), path) == seen->end()) {
      seen->push_back(path);
      first = true;
    }
  }
  if (first) Logger::log("sysfs: %s not readable (%s)\n", path.c_str(), strerror(saved_errno));
  errno = saved_errno;
  return path;
}

// An enforcing SELinux policy (every retail phone; the Thor only with it forced on) refuses an app
// a listing of /sys, /sys/class and /sys/bus. libudev lists /sys/class to find the subsystems to
// descend into and gives up its whole scan when it cannot, so the client never reached hidraw and
// found no Deck. The client is listed the stand-ins instead - hidraw under class - and opens
// /sys/class/hidraw itself, which is ours and readable. Only on that refusal, only for the client.
__attribute__((visibility("hidden"))) static std::string deck_listing_for(const char *path) {
  const char *listing = deck_listing_dir();
  if (!path || !listing || errno != EACCES || !fake_deck_enabled() || !process_is_steam_client()) return {};
  std::string p(path);
  while (p.size() > 1 && p.back() == '/') p.pop_back();
  int which = p == "/sys" ? 0 : p == "/sys/class" ? 1 : p == "/sys/bus" ? 2 : -1;
  if (which < 0) return {};
  static const char *const names[] = {"sys", "class", "bus"};
  static std::atomic<int> logged{0};
  if (!(logged.fetch_or(1 << which) & (1 << which)))
    Logger::log("sysfs: %s listed from the Deck's stand-in\n", p.c_str());
  return std::string(listing) + "/" + names[which];
}

#ifdef __GLIBC__
// sd-device's path chase now requires a mount ID, which statx only supplies from Linux 5.8.
// Older handheld kernels still publish the real ID in fdinfo. Scope this compatibility path to
// libudev/libsystemd in the Deck-reading Steam client, and never invent a unique mount ID.
__attribute__((visibility("hidden"))) static bool deck_device_library(void *caller) {
  Dl_info info;
  if (!dladdr(caller, &info) || !info.dli_fname) return false;
  const char *name = strrchr(info.dli_fname, '/');
  name = name ? name + 1 : info.dli_fname;
  return !strncmp(name, "libudev.so", 10) || !strncmp(name, "libsystemd.so", 13);
}

__attribute__((visibility("hidden"))) static bool deck_mount_id_from_fdinfo(
    int dirfd, const char *path, int flags, struct statx *buf) {
  static auto real_openat = reinterpret_cast<decltype(&::openat)>(dlsym(RTLD_NEXT, "openat"));
  static auto real_fstat = reinterpret_cast<decltype(&::fstat)>(dlsym(RTLD_NEXT, "fstat"));
  int target;
  if (!path || !*path) {
    if (!(flags & AT_EMPTY_PATH)) return false;
    target = dirfd == AT_FDCWD ? real_openat(AT_FDCWD, ".", O_PATH | O_CLOEXEC) :
                                syscall(SYS_fcntl, dirfd, F_DUPFD_CLOEXEC, 0);
  } else {
    target = real_openat(dirfd, path, O_PATH | O_CLOEXEC |
                        ((flags & AT_SYMLINK_NOFOLLOW) ? O_NOFOLLOW : 0));
  }
  if (target < 0) return false;
  struct stat st;
  // Pin the inode and check it still matches the successful statx before adding information.
  bool same = real_fstat(target, &st) == 0 && (buf->stx_mask & STATX_INO) &&
              st.st_ino == buf->stx_ino && major(st.st_dev) == buf->stx_dev_major &&
              minor(st.st_dev) == buf->stx_dev_minor;
  char name[64];
  snprintf(name, sizeof(name), "/proc/self/fdinfo/%d", target);
  int info = same ? real_openat(AT_FDCWD, name, O_RDONLY | O_CLOEXEC) : -1;
  char value[512];
  ssize_t length = info >= 0 ? syscall(SYS_read, info, value, sizeof(value) - 1) : -1;
  if (info >= 0) syscall(SYS_close, info);
  syscall(SYS_close, target);
  if (length < 0) return false;
  value[length] = '\0';
  for (const char *line = value; line && *line;) {
    unsigned long long id;
    if (sscanf(line, "mnt_id: %llu", &id) == 1 && id > 0) {
      buf->stx_mnt_id = id;
      buf->stx_mask |= STATX_MNT_ID;
      return true;
    }
    const char *next = strchr(line, '\n');
    line = next ? next + 1 : nullptr;
  }
  return false;
}

EXPORT int statx(int dirfd, const char *path, int flags, unsigned mask, struct statx *buf) {
  static auto real = reinterpret_cast<decltype(&::statx)>(dlsym(RTLD_NEXT, "statx"));
  int result = real(dirfd, path, flags, mask, buf);
  int saved_errno = errno;
  constexpr unsigned unique_mount_id = 0x4000;  // STATX_MNT_ID_UNIQUE, newer than build headers.
  if (result == 0 && (mask & STATX_MNT_ID) &&
      !(buf->stx_mask & (STATX_MNT_ID | unique_mount_id)) && fake_deck_enabled() &&
      process_is_steam_client() && deck_device_library(__builtin_return_address(0))) {
    bool recovered = deck_mount_id_from_fdinfo(dirfd, path, flags, buf);
    static std::atomic<bool> noted_success{false}, noted_failure{false};
    if (!(recovered ? noted_success : noted_failure).exchange(true))
      Logger::log("deck discovery: statx mount id %s for %s (returned mask=0x%x id=%llu)\n",
                  recovered ? "recovered from fdinfo" : "unavailable in fdinfo",
                  path && *path ? path : "(fd)", buf->stx_mask,
                  static_cast<unsigned long long>(buf->stx_mnt_id));
  }
  errno = saved_errno;
  return result;
}
#endif

EXPORT DIR *opendir(const char *name) {
  static auto real = reinterpret_cast<DIR *(*)(const char *)>(dlsym(RTLD_NEXT, "opendir"));
  DIR *dir = real(name);
  if (!dir) {
    note_sys_failure(AT_FDCWD, name);
    std::string listing = deck_listing_for(name);
    if (!listing.empty()) dir = real(listing.c_str());
  }
  return dir;
}

EXPORT int open(const char *pathname, int flags, ...) {
  va_list va;
  mode_t mode;
  int fd;
  bool hasMode;

  va_start(va, flags);

  hasMode = flags & O_CREAT;

  if (hasMode) {
    mode = va_arg(va, mode_t);
  }

  va_end(va);

  static auto my_open = reinterpret_cast<int (*)(const char *, int, ...)>(dlsym(RTLD_NEXT, "open"));

  if (fake_uinput_enabled() && is_uinput_path(pathname)) return open_uinput(flags);
  if (is_deck_hidraw_path(pathname)) return open_deck_hidraw(flags);
  if (is_withdrawn_pad_path(pathname)) {
    errno = ENOENT;
    return -1;
  }

  char *fake_path = nullptr;
  const char *event = nullptr;
  if (pathname) {
    if (is_fake_input_node_path(pathname)) {
      event = get_event(pathname);
      fake_path = from_real_to_fake_path(pathname);
      if (!fake_path) {
        errno = ENOMEM;
        return -1;
      }
      if (path_exists(fake_path)) {
        fd = open_fake_input_ring(event, flags);
        if (fd >= 0) {
          free(fake_path);
          return fd;
        }
        int saved_errno = errno;
        free(fake_path);
        errno = saved_errno;
        return -1;
      }
      pathname = fake_path;
    } else if (is_fake_udev_data_path(pathname)) {
      fake_path = from_real_to_fake_udev_data_path(pathname);
      if (!fake_path) {
        errno = ENOMEM;
        return -1;
      }
      pathname = fake_path;
    } else if (!strcmp(pathname, "/dev/input")) {
      pathname = fake_hook_dir();
    }
  }

  if (hasMode)
    fd = my_open(pathname, flags, mode);
  else
    fd = my_open(pathname, flags);
  if (fd < 0) {
    std::string listing = deck_listing_for(note_sys_failure(AT_FDCWD, pathname).c_str());
    if (!listing.empty()) fd = my_open(listing.c_str(), flags);
  }

  if (fake_path)
    free(fake_path);

  return fd;
}

EXPORT int openat(int dirfd, const char *pathname, int flags, ...) {
  va_list va;
  mode_t mode;
  int fd;
  bool hasMode;

  va_start(va, flags);

  hasMode = flags & O_CREAT;

  if (hasMode) {
    mode = va_arg(va, mode_t);
  }

  va_end(va);

  static auto my_openat = reinterpret_cast<int (*)(int, const char *, int, ...)>(dlsym(RTLD_NEXT, "openat"));

  if (fake_uinput_enabled() && is_uinput_path(pathname)) return open_uinput(flags);
  if (is_deck_hidraw_path(pathname)) return open_deck_hidraw(flags);
  if (is_withdrawn_pad_path(pathname)) {
    errno = ENOENT;
    return -1;
  }

  char *fake_path = nullptr;
  const char *event = nullptr;
  if (pathname) {
    if (is_fake_input_node_path(pathname)) {
      event = get_event(pathname);
      fake_path = from_real_to_fake_path(pathname);
      if (!fake_path) {
        errno = ENOMEM;
        return -1;
      }
      if (path_exists(fake_path)) {
        fd = open_fake_input_ring(event, flags);
        if (fd >= 0) {
          free(fake_path);
          return fd;
        }
        int saved_errno = errno;
        free(fake_path);
        errno = saved_errno;
        return -1;
      }
      pathname = fake_path;
    } else if (is_fake_udev_data_path(pathname)) {
      fake_path = from_real_to_fake_udev_data_path(pathname);
      if (!fake_path) {
        errno = ENOMEM;
        return -1;
      }
      pathname = fake_path;
    } else if (!strcmp(pathname, "/dev/input")) {
      pathname = fake_hook_dir();
    }
  }

  if (hasMode)
    fd = my_openat(dirfd, pathname, flags, mode);
  else
    fd = my_openat(dirfd, pathname, flags);
  if (fd < 0) {
    std::string listing = deck_listing_for(note_sys_failure(dirfd, pathname).c_str());
    if (!listing.empty()) fd = my_openat(AT_FDCWD, listing.c_str(), flags);
  }

  if (fake_path)
    free(fake_path);

  return fd;
}

EXPORT int stat(const char *pathname, struct stat *statbuf) {
  static auto my_stat = reinterpret_cast<decltype(&::stat)>(dlsym(RTLD_NEXT, "stat"));

  if (is_withdrawn_pad_path(pathname) || (is_deck_hidraw_path(pathname) && !process_is_steam_client())) {
    errno = ENOENT;
    return -1;
  }
  if (is_deck_hidraw_path(pathname)) {
    memset(statbuf, 0, sizeof(*statbuf));
    statbuf->st_mode = S_IFCHR | 0666;
    statbuf->st_rdev = makedev(DECK_HIDRAW_MAJOR, DECK_HIDRAW_MINOR);
    return 0;
  }

  const char *event = nullptr;
  char *fake_path = nullptr;

  if (pathname) {
    if (is_fake_input_node_path(pathname)) {
      event = get_event(pathname);
      fake_path = from_real_to_fake_path(pathname);
      if (!fake_path) {
        errno = ENOMEM;
        return -1;
      }
      pathname = fake_path;
    } else if (is_fake_udev_data_path(pathname)) {
      fake_path = from_real_to_fake_udev_data_path(pathname);
      if (!fake_path) {
        errno = ENOMEM;
        return -1;
      }
      pathname = fake_path;
    } else if (!strcmp(pathname, "/dev/input")) {
      pathname = fake_hook_dir();
    }
  }

  int ret = my_stat(pathname, statbuf);

  if (ret == 0 && event && get_event_number(event) >= 0) {
    statbuf->st_mode = (statbuf->st_mode & ~S_IFMT) | S_IFCHR;
    statbuf->st_rdev = get_fake_input_rdev(event);
  }

  if (fake_path)
    free(fake_path);

  return ret;
}

EXPORT int fstat(int fd, struct stat *buf) {
  static auto my_fstat = reinterpret_cast<decltype(&::fstat)>(dlsym(RTLD_NEXT, "fstat"));

  int ret = my_fstat(fd, buf);

  std::lock_guard<std::recursive_mutex> guard(controller_mutex());
  if (ret == 0 && deck_map().count(fd)) {
    buf->st_mode = (buf->st_mode & ~S_IFMT) | S_IFCHR;
    buf->st_rdev = makedev(DECK_HIDRAW_MAJOR, DECK_HIDRAW_MINOR);
    return ret;
  }
  auto controller = controller_map().find(fd);
  if (ret == 0 && controller != controller_map().end()) {
    buf->st_mode = (buf->st_mode & ~S_IFMT) | S_IFCHR;
    buf->st_rdev = get_fake_input_rdev(controller->second->event);
  }

  return ret;
}

EXPORT int access(const char *pathname, int mode) {
  static auto my_access = reinterpret_cast<decltype(&::access)>(dlsym(RTLD_NEXT, "access"));
  if (fake_uinput_enabled() && is_uinput_path(pathname)) return 0;
  if (is_deck_hidraw_path(pathname) && process_is_steam_client()) return 0;
  if (is_withdrawn_pad_path(pathname) || is_deck_hidraw_path(pathname)) {
    errno = ENOENT;
    return -1;
  }

  char *fake_path = nullptr;
  if (pathname) {
    if (is_fake_input_node_path(pathname)) {
      fake_path = from_real_to_fake_path(pathname);
      if (!fake_path) {
        errno = ENOMEM;
        return -1;
      }
      pathname = fake_path;
    } else if (is_fake_udev_data_path(pathname)) {
      fake_path = from_real_to_fake_udev_data_path(pathname);
      if (!fake_path) {
        errno = ENOMEM;
        return -1;
      }
      pathname = fake_path;
    } else if (!strcmp(pathname, "/dev/input")) {
      pathname = fake_hook_dir();
    }
  }

  int ret = my_access(pathname, mode);
  if (fake_path)
    free(fake_path);
  return ret;
}

EXPORT int faccessat(int dirfd, const char *pathname, int mode, int flags) {
  static auto my_faccessat = reinterpret_cast<decltype(&::faccessat)>(dlsym(RTLD_NEXT, "faccessat"));
  if (fake_uinput_enabled() && is_uinput_path(pathname)) return 0;
  if (is_deck_hidraw_path(pathname) && process_is_steam_client()) return 0;
  if (is_withdrawn_pad_path(pathname) || is_deck_hidraw_path(pathname)) {
    errno = ENOENT;
    return -1;
  }

  char *fake_path = nullptr;
  if (pathname) {
    if (is_fake_input_node_path(pathname)) {
      fake_path = from_real_to_fake_path(pathname);
      if (!fake_path) {
        errno = ENOMEM;
        return -1;
      }
      pathname = fake_path;
    } else if (is_fake_udev_data_path(pathname)) {
      fake_path = from_real_to_fake_udev_data_path(pathname);
      if (!fake_path) {
        errno = ENOMEM;
        return -1;
      }
      pathname = fake_path;
    } else if (!strcmp(pathname, "/dev/input")) {
      pathname = fake_hook_dir();
    }
  }

  int ret = my_faccessat(dirfd, pathname, mode, flags);
  if (fake_path)
    free(fake_path);
  return ret;
}

EXPORT int scandir(const char *dirp, struct dirent ***namelist,
                   int (*filter)(const struct dirent *),
                   int (*compar)(const struct dirent **,
                                 const struct dirent **)) {
  static auto my_scandir = reinterpret_cast<decltype(&::scandir)>(dlsym(RTLD_NEXT, "scandir"));

  if (dirp) {
    if (!strcmp(dirp, "/dev/input")) {
      dirp = fake_hook_dir();
    } else if (fake_udev_data_dir() && !strcmp(dirp, "/run/udev/data")) {
      dirp = fake_udev_data_dir();
    }
  }

  return my_scandir(dirp, namelist, filter, compar);
}

EXPORT int inotify_add_watch(int fd, const char *pathname, uint32_t mask) {
  static auto my_inotify_add_watch = reinterpret_cast<decltype(&::inotify_add_watch)>(dlsym(RTLD_NEXT, "inotify_add_watch"));

  char *fake_path = nullptr;
  if (pathname) {
    if (is_fake_input_node_path(pathname)) {
      fake_path = from_real_to_fake_path(pathname);
      if (!fake_path) {
        errno = ENOMEM;
        return -1;
      }
      pathname = fake_path;
    } else if (!strcmp(pathname, "/dev/input")) {
      pathname = fake_hook_dir();
    }
  }

  int ret = my_inotify_add_watch(fd, pathname, mask);
  if (fake_path)
    free(fake_path);
  return ret;
}

template <size_t N>
static int copy_ioctl_bits(int op, void *destination, const unsigned char (&bits)[N]) {
  size_t size = std::min<size_t>(_IOC_SIZE(op), N);
  if (size) memcpy(destination, bits, size);
  return static_cast<int>(size);
}

static bool wait_snapshot(std::shared_ptr<FakeController> fake,
                          std::unique_lock<std::recursive_mutex> &guard,
                          SnapshotState &snap) {
  for (;;) {
    if (fake->closed) {
      errno = EBADF;
      return false;
    }
    if (ring_generation(fake->ring) != fake->generation) {
      errno = ENODEV;
      return false;
    }
    if (read_snapshot(fake->ring, snap)) return true;
    // Enumeration queries must not fail merely because Android is publishing
    // an input frame. Other hooks (including Binder) can proceed while we wait.
    guard.unlock();
    struct timespec wait = {0, 1000000};
    int result = nanosleep(&wait, nullptr);
    int saved_errno = errno;
    guard.lock();
    if (result < 0) {
      errno = saved_errno;
      return false;
    }
  }
}

EXPORT int ioctl(int fd, ioctl_request_t op, ...) {
  va_list va;
  void *argp;

  va_start(va, op);
  argp = va_arg(va, void *);
  va_end(va);

  // The lock must not be held across the passthrough: binder's transport is a
  // blocking ioctl(BINDER_WRITE_READ), so a parked binder pool thread would own
  // controller_mutex() for as long as it waits and deadlock every other ioctl.
  std::unique_lock<std::recursive_mutex> guard(controller_mutex());
  auto maker = uinput_map().find(fd);
  if (maker != uinput_map().end()) return ioctl_uinput(*maker->second, op, argp);
  auto deck = deck_map().find(fd);
  if (deck != deck_map().end()) return ioctl_deck(*deck->second, op, argp);
  auto controller = controller_map().find(fd);
  if (controller == controller_map().end()) {
    guard.unlock();
    return syscall(SYS_ioctl, fd, op, argp);
  }

  int type = (op >> 8 & 0xFF);
  int number = (op >> 0 & 0xFF);
  const char *event = controller->second->event ? controller->second->event : "event0";
  int event_number = controller->second->slot;
  // A virtual pad answers with what its maker described, not with the app's pad identity.
  const UinputDescription *made = controller->second->uinput;

  if (type == 0x45 && number == 0x1) {
    Logger::log("Hooking ioctl EVIOCGVERSION for event %s\n", event);
    int version = 65536;
    memcpy(argp, (void *)&version, sizeof(int));
    return 0;
  } else if (type == 0x45 && number == 0x2) {
    Logger::log("Hooking ioctl EVIOCGID for event %s\n", event);
    struct input_id id;
    memset(&id, 0, sizeof(id));
    id.bustype = 0x03;
    if (made) {
      id = made->id;
    } else if (presents_steam_virtual()) {
      id.vendor = STEAM_VIRTUAL_VENDOR_ID;
      id.product = STEAM_VIRTUAL_PRODUCT_ID;
    } else if (fake_xbox360_identity()) {
      id.vendor = X360_VENDOR_ID;
      id.product = X360_PRODUCT_ID;
    } else {
      id.vendor = static_cast<uint16_t>(GAMEPAD_VENDOR_ID_BASE + event_number);
      id.product = static_cast<uint16_t>(GAMEPAD_PRODUCT_ID_BASE + event_number);
    }
    if (!made) id.version = GAMEPAD_VERSION;
    memcpy(argp, (void *)&id, sizeof(id));
    return 0;
  } else if (type == 0x45 && number == 0x6) {
    Logger::log("Hooking ioctl EVIOCGNAME for event %s\n", event);
    if (made) {
      if (argp && _IOC_SIZE(op)) snprintf(static_cast<char *>(argp), _IOC_SIZE(op), "%s", made->name);
      return 0;
    }
    copy_slot_ioctl_string(op, argp,
                           presents_steam_virtual() ? STEAM_VIRTUAL_NAME_TEMPLATE
                           : (fake_xbox360_identity() ? X360_NAME_TEMPLATE : GAMEPAD_NAME_TEMPLATE),
                           event_number);
    return 0;
  } else if (type == 0x45 && number == 0x7) {
    Logger::log("Hooking ioctl EVIOCGPHYS for event %s\n", event);
    if (made) {
      if (argp && _IOC_SIZE(op)) snprintf(static_cast<char *>(argp), _IOC_SIZE(op), "%s", made->phys);
      return 0;
    }
    copy_slot_ioctl_string(op, argp, GAMEPAD_PHYS_TEMPLATE, event_number);
    return 0;
  } else if (type == 0x45 && number == 0x8) {
    Logger::log("Hooking ioctl EVIOCGUNIQ for event %s\n", event);
    if (made) {
      if (argp && _IOC_SIZE(op)) static_cast<char *>(argp)[0] = '\0';
      return 0;
    }
    copy_slot_ioctl_string(op, argp, GAMEPAD_UNIQ_TEMPLATE, event_number);
    return 0;
  } else if (type == 0x45 && number == 0x9) {
    unsigned char bitmask[(INPUT_PROP_MAX + 8) / 8] = {};
    return copy_ioctl_bits(op, argp, bitmask);
  } else if (type == 0x45 && number == 0x18) {
    SnapshotState snap;
    if (!wait_snapshot(controller->second, guard, snap)) return -1;
    unsigned char bitmask[(KEY_MAX + 8) / 8] = {};
    for (size_t i = 0; i < kSnapshotButtonCount; ++i) {
      if (snap.buttons & (1u << i))
        bitmask[kSnapshotButtons[i] / 8] |= 1u << (kSnapshotButtons[i] % 8);
    }
    return copy_ioctl_bits(op, argp, bitmask);
  } else if (type == 0x45 && number == 0x20) {
    Logger::log("Hooking ioctl EVIOCGBIT(0, len) for event %s\n", event);
    if (made) return copy_ioctl_bits(op, argp, made->evbits);
    unsigned char bitmask[(EV_MAX + 8) / 8] = {};
    bitmask[EV_SYN / 8] |= (1 << (EV_SYN % 8));
    bitmask[EV_KEY / 8] |= (1 << (EV_KEY % 8));
    bitmask[EV_ABS / 8] |= (1 << (EV_ABS % 8));
    return copy_ioctl_bits(op, argp, bitmask);
  } else if (type == 0x45 && number == 0x21) {
    Logger::log("Hooking ioctl EVIOCGBIT(EV_KEY, len) for event %s\n", event);
    if (made) return copy_ioctl_bits(op, argp, made->keybits);
    unsigned char bitmask[(KEY_MAX + 8) / 8] = {};
    const int xbox_buttons[] = {BTN_A,    BTN_B,      BTN_X,      BTN_Y,
                                BTN_TL,   BTN_TR,     BTN_SELECT, BTN_START,
                                BTN_MODE, BTN_THUMBL, BTN_THUMBR};
    for (int button : xbox_buttons)
      bitmask[button / 8] |= (1 << (button % 8));
    return copy_ioctl_bits(op, argp, bitmask);
  } else if (type == 0x45 && number == 0x22) {
    Logger::log("Hooking ioctl EVIOCGBIT(EV_REL, len) for event %s\n", event);
    unsigned char bitmask[(REL_MAX + 8) / 8] = {};
    return copy_ioctl_bits(op, argp, bitmask);
  } else if (type == 0x45 && number == 0x23) {
    Logger::log("Hooking ioctl EVIOCGBIT(EV_ABS, len) for event %s\n", event);
    if (made) return copy_ioctl_bits(op, argp, made->absbits);
    unsigned char bitmask[(ABS_MAX + 8) / 8] = {};
    bitmask[ABS_X / 8] |= (1 << (ABS_X % 8));
    bitmask[ABS_Y / 8] |= (1 << (ABS_Y % 8));
    bitmask[ABS_RX / 8] |= (1 << (ABS_RX % 8));
    bitmask[ABS_RY / 8] |= (1 << (ABS_RY % 8));
    for (uint16_t trigger : {ABS_GAS, ABS_BRAKE}) {
      uint16_t code = presented_abs_code(*controller->second, trigger);
      bitmask[code / 8] |= (1 << (code % 8));
    }
    bitmask[ABS_HAT0X / 8] |= (1 << (ABS_HAT0X % 8));
    bitmask[ABS_HAT0Y / 8] |= (1 << (ABS_HAT0Y % 8));
    return copy_ioctl_bits(op, argp, bitmask);
  } else if (type == 0x45 && number == 0x35) {
    Logger::log("Hooking ioctl EVIOCGBIT(EV_FF, len) for event %s\n", event);
    if (made) return copy_ioctl_bits(op, argp, made->ffbits);
    unsigned char bitmask[(FF_MAX + 8) / 8] = {};
    bitmask[FF_RUMBLE / 8] |= (1 << (FF_RUMBLE % 8));
    bitmask[FF_PERIODIC / 8] |= (1 << (FF_PERIODIC % 8));
    return copy_ioctl_bits(op, argp, bitmask);
  } else if (type == 0x45 && number == 0x80) {
    struct ff_effect *effect = static_cast<struct ff_effect *>(argp);
    if (effect->id == -1)
      effect->id = next_ff_id++;
    ff_effects()[effect->id] = *effect;

    uint16_t duration = effect->replay.length;
    uint16_t slot = static_cast<uint16_t>(event_number);
    if (effect->type == FF_RUMBLE) {
      send_vibration(effect->u.rumble.strong_magnitude,
                     effect->u.rumble.weak_magnitude, duration, slot);
    } else if (effect->type == FF_PERIODIC) {
      send_vibration(effect->u.periodic.magnitude, effect->u.periodic.magnitude,
                     duration, slot);
    }
    return 0;
  } else if (type == 0x45 && number == 0x81) {
    int id = (intptr_t)argp;
    ff_effects().erase(id);
    return 0;
  } else if (type == 0x45 && number == 0x84) {
    int max_effects = 16;
    memcpy(argp, &max_effects, sizeof(int));
    return 0;
  } else if (type == 0x45 && number >= 0x40 && number <= 0x51) {
    Logger::log("Hooking ioctl EVIOCGABS(ABS) for event %s\n", event);
    struct input_absinfo abs_info;
    memset(&abs_info, 0, sizeof(abs_info));
    uint16_t code = ring_abs_code(*controller->second, number - 0x40);
    if (made) {
      abs_info = made->absinfo[number - 0x40];
    } else if (code == ABS_GAS || code == ABS_BRAKE) {
      abs_info.value = 0;
      abs_info.minimum = 0;
      abs_info.maximum = 255;
    } else if (code <= ABS_RY) {
      abs_info.value = 0;
      abs_info.minimum = -32768;
      abs_info.maximum = 32767;
    } else if (code == ABS_HAT0X || code == ABS_HAT0Y) {
      abs_info.value = 0;
      abs_info.minimum = -1;
      abs_info.maximum = 1;
    }
    SnapshotState snap;
    if (!wait_snapshot(controller->second, guard, snap)) return -1;
    for (int i = 0; i < 8; ++i)
      if (kSnapshotAxisCodes[i] == code) abs_info.value = snap.axes[i];
    memcpy(argp, &abs_info, std::min<size_t>(_IOC_SIZE(op), sizeof(abs_info)));
    return 0;
  } else if (type == 0x45 && number == 0x90) {
    Logger::log("Hooking ioctl EVIOCGRAB for event %s\n", event);
    return 0;
  } else if (type == 0x6A && number == 0x1) {
    Logger::log("Hooking ioctl JSIOCGVERSION for event %s\n", event);
    int version = JS_VERSION;
    memcpy(argp, (void *)&version, sizeof(version));
    return 0;
  } else if (type == 0x6A && number == 0x11) {
    Logger::log("Hooking ioctl JSIOCGAXES for event %s\n", event);
    uint8_t axes = GAMEPAD_AXIS_COUNT;
    memcpy(argp, (void *)&axes, sizeof(axes));
    return 0;
  } else if (type == 0x6A && number == 0x12) {
    Logger::log("Hooking ioctl JSIOCGBUTTONS for event %s\n", event);
    uint8_t buttons = GAMEPAD_BUTTON_COUNT;
    memcpy(argp, (void *)&buttons, sizeof(buttons));
    return 0;
  } else if (type == 0x6A && number == 0x13) {
    Logger::log("Hooking ioctl JSIOCGNAME(len) for event %s\n", event);
    copy_slot_ioctl_string(op, argp,
                           presents_steam_virtual() ? STEAM_VIRTUAL_NAME_TEMPLATE
                           : (fake_xbox360_identity() ? X360_NAME_TEMPLATE : GAMEPAD_NAME_TEMPLATE),
                           event_number);
    return 0;
  } else {
    Logger::log("Unhandled evdev ioctl, type %d number %d\n", type, number);
    guard.unlock();
    return syscall(SYS_ioctl, fd, op, argp);
  }
}

EXPORT int close(int fd) {
  static auto my_close = reinterpret_cast<decltype(&::close)>(dlsym(RTLD_NEXT, "close"));

  close_uinput(fd);
  std::unique_lock<std::recursive_mutex> guard(controller_mutex());
  deck_map().erase(fd);
  auto controller = controller_map().find(fd);
  if (controller != controller_map().end()) {
    Logger::log("Removing controller, fd %d event %s\n", controller->first,
                controller->second->event ? controller->second->event : "(unknown)");
    controller->second->closed = true;
    controller_map().erase(fd);
  }
  guard.unlock();

  return my_close(fd);
}

// Longest wait between ring polls in read(), poll() and select(), in milliseconds.
// The backoff starts at 1 ms and doubles up to this cap, so a press reaches the reader within about 2 ms instead of up to 16 ms.
// The cost is more wakeups while a reader blocks on an idle fake pad (about 500 a second instead of about 60).
static constexpr int kMaxRingWaitMs = 2;

EXPORT ssize_t read(int fd, void *buf, size_t count) {
  std::unique_lock<std::recursive_mutex> guard(controller_mutex());
  auto controller = controller_map().find(fd);
  if (controller == controller_map().end()) {
    guard.unlock();
    return syscall(SYS_read, fd, buf, count);
  }
  // Keep the mapping alive across waits, even if close() removes the fd and
  // another open reuses its number. Every state access still holds the lock.
  auto handle = controller->second;
  FakeController &fake = *handle;
  if (count < FAKE_INPUT_EVENT_SIZE) {
    errno = EINVAL;
    return -1;
  }
  int flags = fcntl(fd, F_GETFL);
  bool nonblock = flags >= 0 && (flags & O_NONBLOCK);
  long backoff_ns = 1000 * 1000;

  for (;;) {
    if (fake.closed) {
      errno = EBADF;
      return -1;
    }
    if (ring_generation(fake.ring) != fake.generation) {
      errno = ENODEV;
      return -1;
    }
    for (int attempt = 0; attempt < 8; ++attempt) {
      size_t requested = count / FAKE_INPUT_EVENT_SIZE;
      if (fake.keyframe_remaining == 0) {
        SnapshotState snap;
        if (!read_snapshot(fake.ring, snap)) break;
        if (snap.generation != fake.generation) {
          errno = ENODEV;
          return -1;
        }
        if (fake.needs_keyframe || snap.resync_seq != fake.resync_seq ||
            snap.write_seq < fake.read_seq ||
            snap.write_seq - fake.read_seq > FAKE_INPUT_RING_CAPACITY) {
          capture_keyframe(fake, snap);
        } else {
          size_t events = std::min<uint64_t>(requested, snap.write_seq - fake.read_seq);
          if (events == 0) break;
          const uint8_t *ring_events = reinterpret_cast<const uint8_t *>(fake.ring) +
                                       FAKE_INPUT_RING_HEADER_SIZE;
          for (size_t i = 0; i < events; ++i) {
            size_t index = (fake.read_seq + i) % FAKE_INPUT_RING_CAPACITY;
            struct input_event ev;
            memcpy(&ev, ring_events + index * FAKE_INPUT_EVENT_SIZE, FAKE_INPUT_EVENT_SIZE);
            if (ev.type == EV_ABS) ev.code = presented_abs_code(fake, ev.code);
            memcpy(static_cast<uint8_t *>(buf) + i * FAKE_INPUT_EVENT_SIZE, &ev,
                   FAKE_INPUT_EVENT_SIZE);
          }
          // A producer can lap the reader while it copies. Discard that copy
          // instead of delivering torn/overwritten events or losing a release.
          __atomic_thread_fence(__ATOMIC_ACQUIRE);
          if (snap.sequence != __atomic_load_n(&fake.ring->snapshot_seq, __ATOMIC_RELAXED))
            continue;
          fake.read_seq += events;
          return static_cast<ssize_t>(events * FAKE_INPUT_EVENT_SIZE);
        }
      }

      // Finish a captured frame before handling a newer resync. Its counter is
      // acknowledged only when that newer snapshot is actually captured.
      size_t events = std::min(requested, fake.keyframe_remaining);
      struct timeval now = {};
      gettimeofday(&now, nullptr);
      for (size_t i = 0; i < events; ++i) {
        size_t index = kNeutralEventCount - fake.keyframe_remaining;
        struct input_event ev = {};
        ev.time = now;
        ev.type = kNeutralEvents[index].type;
        ev.code = kNeutralEvents[index].code;
        ev.value = keyframe_value(fake, ev.type, ev.code);
        if (ev.type == EV_ABS) ev.code = presented_abs_code(fake, ev.code);
        memcpy(static_cast<uint8_t *>(buf) + i * FAKE_INPUT_EVENT_SIZE,
               &ev, FAKE_INPUT_EVENT_SIZE);
        --fake.keyframe_remaining;
      }
      return static_cast<ssize_t>(events * FAKE_INPUT_EVENT_SIZE);
    }
    if (nonblock) {
      errno = EAGAIN;
      return -1;
    }
    guard.unlock();
    struct timespec sleep_time = {0, backoff_ns};
    int result = nanosleep(&sleep_time, nullptr);
    guard.lock();
    if (result < 0) return -1;
    if (backoff_ns < kMaxRingWaitMs * 1000 * 1000) backoff_ns *= 2;
  }
}

EXPORT ssize_t write(int fd, const void *buf, size_t count) {
  static auto my_write = reinterpret_cast<decltype(&::write)>(dlsym(RTLD_NEXT, "write"));

  std::unique_lock<std::recursive_mutex> guard(controller_mutex());
  auto made = uinput_map().find(fd);
  if (made != uinput_map().end()) return write_uinput(*made->second, buf, count);
  if (deck_map().count(fd)) return static_cast<ssize_t>(count);
  auto controller = controller_map().find(fd);
  if (controller != controller_map().end()) {
    if (fake_fd_is_stale(fd)) {
      errno = ENODEV;
      return -1;
    }

    const struct input_event *ev = nullptr;
    uint16_t slot = static_cast<uint16_t>(controller->second->slot);
    if (count == sizeof(struct input_event)) {
      ev = static_cast<const struct input_event *>(buf);
      check_ff_event(ev, slot);
    }

    return static_cast<ssize_t>(count);
  }
  guard.unlock();
  return my_write(fd, buf, count);
}

EXPORT ssize_t writev(int fd, const struct iovec *iov, int iovcnt) {
  std::unique_lock<std::recursive_mutex> guard(controller_mutex());
  if (deck_map().count(fd)) {
    ssize_t total = 0;
    for (int i = 0; i < iovcnt; i++) total += static_cast<ssize_t>(iov[i].iov_len);
    return total;
  }
  auto made = uinput_map().find(fd);
  if (made != uinput_map().end()) {
    // One description or whole events per vector, which is how uinput writers write.
    ssize_t total = 0;
    for (int i = 0; i < iovcnt; i++) {
      ssize_t written = write_uinput(*made->second, iov[i].iov_base, iov[i].iov_len);
      if (written < 0) return total ? total : -1;
      total += written;
    }
    return total;
  }
  auto controller = controller_map().find(fd);
  if (controller != controller_map().end()) {
    if (fake_fd_is_stale(fd)) {
      errno = ENODEV;
      return -1;
    }

    uint16_t slot = static_cast<uint16_t>(controller->second->slot);
    ssize_t total = 0;
    for (int i = 0; i < iovcnt; i++) {
      if (iov[i].iov_len == sizeof(struct input_event)) {
        const struct input_event *ev =
            static_cast<const struct input_event *>(iov[i].iov_base);
        check_ff_event(ev, slot);
      }
      total += static_cast<ssize_t>(iov[i].iov_len);
    }
    return total;
  }
  guard.unlock();
  return syscall(SYS_writev, fd, iov, iovcnt);
}

static int poll_fake(struct pollfd *fds, nfds_t nfds, int timeout,
                     const sigset_t *sigmask) {
  static auto my_poll = reinterpret_cast<decltype(&::poll)>(dlsym(RTLD_NEXT, "poll"));
  static auto my_ppoll = reinterpret_cast<decltype(&::ppoll)>(dlsym(RTLD_NEXT, "ppoll"));

  bool has_fake_fds = false;
  std::vector<struct pollfd> real_fds;
  real_fds.reserve(nfds);
  std::vector<std::shared_ptr<FakeController>> fake_fds(nfds);
  {
    std::lock_guard<std::recursive_mutex> guard(controller_mutex());
    for (nfds_t i = 0; i < nfds; i++) {
      real_fds.push_back(fds[i]);
      auto it = controller_map().find(fds[i].fd);
      if (it != controller_map().end()) {
        fake_fds[i] = it->second;
        has_fake_fds = true;
        real_fds[i].fd = -1;
        real_fds[i].revents = 0;
      }
    }
  }

  if (!has_fake_fds) {
    if (sigmask) {
      struct timespec wait = {timeout / 1000, (timeout % 1000) * 1000000L};
      return my_ppoll(fds, nfds, timeout < 0 ? nullptr : &wait, sigmask);
    }
    return my_poll(fds, nfds, timeout);
  }

  const long long deadline_ms = timeout < 0 ? -1 : monotonic_ms() + timeout;
  int backoff_ms = 1;

  while (true) {
    int ready = 0;

    for (nfds_t i = 0; i < nfds; i++)
      fds[i].revents = 0;

    for (nfds_t i = 0; i < nfds; i++) {
      if (!fake_fds[i]) continue;
      short revents = fake_poll_revents(fake_fds[i], fds[i].events);
      fds[i].revents = revents;
      if (revents)
        ready++;
    }

    int real_timeout = ready > 0 ? 0 : [&] {
      if (timeout == 0) return 0;
      int remaining = deadline_ms < 0
                          ? backoff_ms
                          : std::min(backoff_ms, (int)(deadline_ms - monotonic_ms()));
      return std::max(remaining, 0);
    }();

    int real_ready;
    if (sigmask) {
      struct timespec wait = {real_timeout / 1000, (real_timeout % 1000) * 1000000L};
      real_ready = my_ppoll(real_fds.data(), nfds, &wait, sigmask);
    } else {
      real_ready = my_poll(real_fds.data(), nfds, real_timeout);
    }
    if (real_ready < 0) return -1;
    if (real_ready > 0) {
      for (nfds_t i = 0; i < nfds; i++) {
        if (!fake_fds[i]) {
          fds[i].revents = real_fds[i].revents;
          if (fds[i].revents)
            ready++;
        }
      }
    }

    if (ready == 0 && real_timeout > 0) {
      for (nfds_t i = 0; i < nfds; i++) {
        if (!fake_fds[i]) continue;
        short revents = fake_poll_revents(fake_fds[i], fds[i].events);
        fds[i].revents = revents;
        if (revents)
          ready++;
      }
    }

    if (ready > 0)
      return ready;

    if (timeout == 0)
      return 0;

    if (deadline_ms >= 0 && monotonic_ms() >= deadline_ms)
      return 0;

    if (backoff_ms < kMaxRingWaitMs)
      backoff_ms *= 2;
  }
}

EXPORT int poll(struct pollfd *fds, nfds_t nfds, int timeout) {
  return poll_fake(fds, nfds, timeout, nullptr);
}

EXPORT int ppoll(struct pollfd *fds, nfds_t nfds,
                 const struct timespec *timeout, const sigset_t *sigmask) {
  static auto my_ppoll = reinterpret_cast<decltype(&::ppoll)>(dlsym(RTLD_NEXT, "ppoll"));
  if (timeout && (timeout->tv_sec < 0 || timeout->tv_nsec < 0 || timeout->tv_nsec >= 1000000000L)) {
    errno = EINVAL;
    return -1;
  }
  for (nfds_t i = 0; i < nfds; ++i) {
    if (!is_fake_input_fd(fds[i].fd)) continue;
    if (!sigmask)
      return poll_fake(fds, nfds, static_cast<int>(timespec_to_ms(timeout)), nullptr);
    // Keep signals pending between polling slices. Each kernel ppoll applies
    // the requested mask atomically with its wait, just like a single ppoll.
    sigset_t all_signals, original_mask;
    sigfillset(&all_signals);
    int error = pthread_sigmask(SIG_SETMASK, &all_signals, &original_mask);
    if (error) {
      errno = error;
      return -1;
    }
    int result = poll_fake(fds, nfds, static_cast<int>(timespec_to_ms(timeout)), sigmask);
    int saved_errno = errno;
    pthread_sigmask(SIG_SETMASK, &original_mask, nullptr);
    errno = saved_errno;
    return result;
  }
  return my_ppoll(fds, nfds, timeout, sigmask);
}

EXPORT int select(int nfds, fd_set *readfds, fd_set *writefds,
                  fd_set *exceptfds, struct timeval *timeout) {
  static auto my_select = reinterpret_cast<decltype(&::select)>(dlsym(RTLD_NEXT, "select"));

  fd_set original_readfds;
  fd_set original_writefds;
  fd_set original_exceptfds;
  fd_set real_readfds;
  fd_set real_writefds;
  fd_set real_exceptfds;
  bool has_fake_fds = false;

  if (readfds) {
    original_readfds = *readfds;
    real_readfds = *readfds;
  } else {
    FD_ZERO(&original_readfds);
    FD_ZERO(&real_readfds);
  }
  if (writefds) {
    original_writefds = *writefds;
    real_writefds = *writefds;
  } else {
    FD_ZERO(&original_writefds);
    FD_ZERO(&real_writefds);
  }
  if (exceptfds) {
    original_exceptfds = *exceptfds;
    real_exceptfds = *exceptfds;
  } else {
    FD_ZERO(&original_exceptfds);
    FD_ZERO(&real_exceptfds);
  }

  for (int fd = 0; fd < nfds; fd++) {
    if (!is_fake_input_fd(fd))
      continue;
    has_fake_fds = true;
    FD_CLR(fd, &real_readfds);
    FD_CLR(fd, &real_writefds);
    FD_CLR(fd, &real_exceptfds);
  }

  if (!has_fake_fds)
    return my_select ? my_select(nfds, readfds, writefds, exceptfds, timeout)
                     : -1;

  const long long timeout_ms = timeval_to_ms(timeout);
  const long long deadline_ms =
      timeout_ms < 0 ? -1 : monotonic_ms() + timeout_ms;
  int backoff_ms = 1;

  while (true) {
    int ready = 0;

    if (readfds)
      FD_ZERO(readfds);
    if (writefds)
      FD_ZERO(writefds);
    if (exceptfds)
      FD_ZERO(exceptfds);

    for (int fd = 0; fd < nfds; fd++) {
      if (!is_fake_input_fd(fd))
        continue;
      if (readfds && FD_ISSET(fd, &original_readfds) && fake_fd_is_stale(fd)) {
        FD_SET(fd, readfds);
        ready++;
      } else if (readfds && FD_ISSET(fd, &original_readfds) &&
                 fake_fd_has_unread_data(fd)) {
        FD_SET(fd, readfds);
        ready++;
      }
    }

    int wait_ms = ready > 0 ? 0 : [&] {
      if (timeout_ms == 0) return 0;
      int remaining = deadline_ms < 0
                          ? backoff_ms
                          : std::min(backoff_ms, (int)(deadline_ms - monotonic_ms()));
      return std::max(remaining, 0);
    }();
    struct timeval wait_tv = {wait_ms / 1000, (wait_ms % 1000) * 1000};

    fd_set iter_readfds = real_readfds;
    fd_set iter_writefds = real_writefds;
    fd_set iter_exceptfds = real_exceptfds;

    int real_ready =
        my_select
            ? my_select(nfds, readfds ? &iter_readfds : nullptr,
                        writefds ? &iter_writefds : nullptr,
                        exceptfds ? &iter_exceptfds : nullptr, &wait_tv)
            : 0;

    if (real_ready < 0) return -1;
    if (real_ready > 0) {
      for (int fd = 0; fd < nfds; fd++) {
        if (readfds && FD_ISSET(fd, &iter_readfds)) {
          FD_SET(fd, readfds);
          ready++;
        }
        if (writefds && FD_ISSET(fd, &iter_writefds)) {
          FD_SET(fd, writefds);
          ready++;
        }
        if (exceptfds && FD_ISSET(fd, &iter_exceptfds)) {
          FD_SET(fd, exceptfds);
          ready++;
        }
      }
    }

    if (ready == 0 && wait_ms > 0) {
      for (int fd = 0; fd < nfds; fd++) {
        if (!is_fake_input_fd(fd))
          continue;
        if (readfds && FD_ISSET(fd, &original_readfds) && fake_fd_is_stale(fd)) {
          FD_SET(fd, readfds);
          ready++;
        } else if (readfds && FD_ISSET(fd, &original_readfds) &&
                   fake_fd_has_unread_data(fd)) {
          FD_SET(fd, readfds);
          ready++;
        }
      }
    }

    if (ready > 0)
      return ready;

    if (timeout_ms == 0)
      return 0;

    if (deadline_ms >= 0 && monotonic_ms() >= deadline_ms)
      return 0;

    if (backoff_ms < kMaxRingWaitMs)
      backoff_ms *= 2;
  }
}

#ifdef __GLIBC__
// Steam's binaries were linked against an older glibc and reach the calls above through these
// names instead.
static mode_t open_mode(int flags, va_list va) {
  return (flags & (O_CREAT | O_TMPFILE)) ? va_arg(va, mode_t) : 0;
}

EXPORT int open64(const char *pathname, int flags, ...) {
  va_list va;
  va_start(va, flags);
  mode_t mode = open_mode(flags, va);
  va_end(va);
  return open(pathname, flags, mode);
}

EXPORT int openat64(int dirfd, const char *pathname, int flags, ...) {
  va_list va;
  va_start(va, flags);
  mode_t mode = open_mode(flags, va);
  va_end(va);
  return openat(dirfd, pathname, flags, mode);
}

EXPORT int stat64(const char *pathname, struct stat64 *statbuf) {
  return stat(pathname, reinterpret_cast<struct stat *>(statbuf));
}

EXPORT int fstat64(int fd, struct stat64 *buf) {
  return fstat(fd, reinterpret_cast<struct stat *>(buf));
}

EXPORT int __xstat(int version, const char *pathname, struct stat *statbuf) {
  (void)version;
  return stat(pathname, statbuf);
}

EXPORT int __xstat64(int version, const char *pathname, struct stat64 *statbuf) {
  (void)version;
  return stat(pathname, reinterpret_cast<struct stat *>(statbuf));
}

EXPORT int __fxstat(int version, int fd, struct stat *buf) {
  (void)version;
  return fstat(fd, buf);
}

EXPORT int __fxstat64(int version, int fd, struct stat64 *buf) {
  (void)version;
  return fstat(fd, reinterpret_cast<struct stat *>(buf));
}
#endif
