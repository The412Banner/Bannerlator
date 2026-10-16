#include <algorithm>
#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>

#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/joystick.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include <sys/inotify.h>
#include <sys/ioctl.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/sysmacros.h>
#include <sys/time.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <sys/un.h>
#include <unistd.h>

#include "../bannerin/include/bannerin.h"

#define EXPORT __attribute__((visibility("default"))) extern "C"

// bionic declares ioctl's request as int, glibc as unsigned long.
// This file is built twice: with the NDK for Wine, and against glibc for the Linux runtime.
// The glibc copy is preloaded into the native Steam client, which is what makes SDL see the app's pad as a real evdev device.
// Interposing a libc function with the wrong signature is a hard compile error on glibc.
// So the request type comes from the platform.
#if defined(__BIONIC__) || defined(__ANDROID__)
typedef int fakeinput_ioctl_request_t;
#else
typedef unsigned long fakeinput_ioctl_request_t;
#endif

static constexpr uint16_t GAMEPAD_VENDOR_ID_BASE = 0x1234;
static constexpr uint16_t GAMEPAD_PRODUCT_ID_BASE = 0x5678;
static constexpr uint16_t GAMEPAD_VERSION = 0x0110;
static constexpr const char *GAMEPAD_NAME_TEMPLATE = "Generic HID Gamepad %d";

// SDL and Steam key their controller mapping database on a GUID built from bus+vendor+product.
// The generic identity above is in no such database.
// So a client has to guess the layout, or ask the user to configure the pad by hand.
// FAKE_EVDEV_IDENTITY picks another identity from the table below; unset, empty or unknown keeps
// the generic one, which is what every already-working Windows title under Wine currently sees.
// An identity changes only what the pad says it is (EVIOCGID vendor/product, EVIOCGNAME and
// JSIOCGNAME); buttons, axes and force feedback are the same for every entry.
// Vendor/product are not offset per slot: a real pad reports the same ids on every port, and an
// offset id would be a GUID no mapping database knows.
// The app sets the variable from the container's "Controller identity" and mirrors this table in
// core/ControllerIdentity.java (DirectInput registry names, SDL mappings, launch log), so the two
// change together.
struct PadIdentity {
  const char *value;
  uint16_t vendor;
  uint16_t product;
  const char *name_template;
};
static constexpr PadIdentity PAD_IDENTITIES[] = {
    {"xbox360", 0x045E, 0x028E, "Xbox 360 Controller (%d)"},
    {"xboxone", 0x045E, 0x02EA, "Xbox One S Controller (%d)"},
    {"xboxseries", 0x045E, 0x0B12, "Xbox Series X Controller (%d)"},
    // The Steam Input virtual gamepad, as the Linux runtime presents it (fakeinput_steam.cpp).
    {"steamvirtual", 0x28DE, 0x11FF, "Microsoft X-Box 360 pad %d"},
    // pid.codes' shared test id: claimed by no vendor, SDL mapping or winebus list, so the pad
    // stays a plain DirectInput joystick.
    {"dinputpad", 0x1209, 0x0001, "USB Gamepad (%d)"},
    {"ds4", 0x054C, 0x09CC, "Wireless Controller (%d)"},
    {"dualsense", 0x054C, 0x0CE6, "DualSense Wireless Controller (%d)"},
    {"switchpro", 0x057E, 0x2009, "Pro Controller (%d)"},
    // 057E:2008 is the combined pair joycond creates; SDL types it as a Joy-Con pair.
    {"joyconpair", 0x057E, 0x2008, "Nintendo Switch Combined Joy-Cons (%d)"},
    {"steamdeck", 0x28DE, 0x1205, "Steam Deck Controller (%d)"},
};
static const PadIdentity *pad_identity = nullptr; // null = the generic identity

static const PadIdentity *find_pad_identity(const char *value) {
  if (!value || !*value)
    return nullptr;
  for (const PadIdentity &entry : PAD_IDENTITIES)
    if (!strcmp(entry.value, value))
      return &entry;
  return nullptr;
}

// The slot's identity from BANNERIN's control block. The app sets it for every slot and can change
// it in-game; the pad then reconnects and the new attach takes the new identity. False when the slot
// file carries none (-ENODATA, an older app): FAKE_EVDEV_IDENTITY / the generic identity apply.
static bool live_pad_identity(bannerin_handle handle, struct bannerin_identity *out) {
  return handle && bannerin_identity(handle, out, sizeof(*out)) == 0;
}

// The live identity's name into an EVIOCGNAME / JSIOCGNAME buffer (copied as text, never as a format).
static void copy_live_name(fakeinput_ioctl_request_t op, void *argp, const struct bannerin_identity &live) {
  size_t size = _IOC_SIZE(op);
  if (!argp || size == 0)
    return;
  snprintf(static_cast<char *>(argp), size, "%.*s", static_cast<int>(sizeof(live.name)), live.name);
}
static constexpr const char *GAMEPAD_PHYS_TEMPLATE = "usb-fakeinput/input%d";
static constexpr const char *GAMEPAD_UNIQ_TEMPLATE = "0000000000%02d";
static constexpr uint8_t GAMEPAD_AXIS_COUNT = 8;
static constexpr uint8_t GAMEPAD_BUTTON_COUNT = 11;
static constexpr unsigned int FAKE_INPUT_MAJOR = 13;
static constexpr unsigned int FAKE_INPUT_EVENT_MINOR_BASE = 64;
static constexpr unsigned int FAKE_INPUT_JS_MINOR_BASE = 0;

static_assert(sizeof(struct input_event) == BANNERIN_EVENT_SIZE,
              "bannerin writes the platform's struct input_event");

// One open pad fd. The fd itself belongs to bannerin (a real kernel fd whose readiness bannerin
// keeps current), so poll/select/epoll on it need no help from this library.
struct FakeController {
  char *event = nullptr;
  int slot = -1;
  bannerin_handle handle = 0;
};

// bannerin's canonical button and axis order (index -> evdev code).
static const uint16_t kPadButtonCodes[BANNERIN_BUTTONS] = BANNERIN_BUTTON_CODES;
static const uint16_t kPadAxisCodes[BANNERIN_AXES] = BANNERIN_AXIS_CODES;

static std::unordered_map<int, FakeController> controller_map;
static const char *hook_dir = nullptr;
static const char *udev_data_dir = nullptr;
static bool vibration_enabled = true;

static int (*my_open)(const char *, int, ...) = nullptr;
static int (*my_openat)(int, const char *, int, ...) = nullptr;
static int (*my_stat)(const char *, struct stat *) = nullptr;
static int (*my_fstat)(int fd, struct stat *buf) = nullptr;
static int (*my_access)(const char *, int) = nullptr;
static int (*my_faccessat)(int, const char *, int, int) = nullptr;
static int (*my_scandir)(const char *, struct dirent ***,
                         int (*)(const struct dirent *),
                         int (*)(const struct dirent **,
                                 const struct dirent **));
static int (*my_inotify_add_watch)(int, const char *, uint32_t);
static int (*my_close)(int);
static int (*my_poll)(struct pollfd *, nfds_t, int) = nullptr;
static int (*my_ppoll)(struct pollfd *, nfds_t, const struct timespec *,
                       const sigset_t *) = nullptr;
static int (*my_select)(int, fd_set *, fd_set *, fd_set *,
                        struct timeval *) = nullptr;
static ssize_t (*my_write)(int, const void *, size_t) = nullptr;

static std::unordered_map<int, struct ff_effect> ff_effects;
static int next_ff_id = 0;

namespace Logger {
int log_enabled;

void init() {
  log_enabled = getenv("FAKE_EVDEV_LOG") && atoi(getenv("FAKE_EVDEV_LOG"));
}

void log(const char *message, ...) {
  if (!log_enabled)
    return;

  va_list args;
  va_start(args, message);
  vfprintf(stderr, message, args);
  va_end(args);

  std::cerr.flush();
}
} // namespace Logger

// bannerin's diagnostics, under FAKE_EVDEV_LOG. Written with one raw write(2): bannerin may call
// this from its own helper thread, which must never wait on stdio's lock or re-enter a hook.
static void bannerin_log_line(int level, const char *line) {
  char out[512];
  int n = snprintf(out, sizeof(out), "fakeinput%s: %s\n", level ? " (warning)" : "",
                   line ? line : "");
  if (n <= 0)
    return;
  if (n >= static_cast<int>(sizeof(out)))
    n = sizeof(out) - 1;
  syscall(SYS_write, STDERR_FILENO, out, static_cast<size_t>(n));
}

__attribute__((constructor)) static void library_init() {
  if (!hook_dir)
    hook_dir = getenv("FAKE_EVDEV_DIR")
                   ? getenv("FAKE_EVDEV_DIR")
                   : "/data/data/com.termux/files/home/fake-input";
  udev_data_dir = getenv("FAKE_UDEV_DATA_DIR");
  vibration_enabled =
      getenv("FAKE_EVDEV_VIBRATION") && atoi(getenv("FAKE_EVDEV_VIBRATION"));

  pad_identity = find_pad_identity(getenv("FAKE_EVDEV_IDENTITY"));

  Logger::init();
  if (Logger::log_enabled)
    bannerin_set_logger(bannerin_log_line);
  bannerin_init();
}

__attribute__((visibility("hidden"))) static void
send_vibration(int strong, int weak, uint16_t duration_ms, uint16_t slot) {
  if (!vibration_enabled)
    return;

  int sock = socket(AF_UNIX, SOCK_STREAM, 0);
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
  send(sock, data, sizeof(data), 0);
  syscall(SYS_close, sock);
}

__attribute__((visibility("hidden"))) static void
check_ff_event(const struct input_event *ev, uint16_t slot) {
  if (ev->type != EV_FF)
    return;

  int id = ev->code;
  if (ev->value > 0) {
    auto it = ff_effects.find(id);
    if (it == ff_effects.end())
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
  if (asprintf(&fake_path, "%s/%s", hook_dir, event) < 0)
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
  return pathname && udev_data_dir && *udev_data_dir &&
         !strncmp(pathname, "/run/udev/data/c13:", 19);
}

__attribute__((visibility("hidden"))) char *
from_real_to_fake_udev_data_path(const char *pathname) {
  const char *name = strrchr(pathname, '/') + 1;
  char *fake_path = nullptr;
  if (asprintf(&fake_path, "%s/%s", udev_data_dir, name) < 0)
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

// Attach a guest open() of event<N>/js<N> to pad slot N. The fd returned is bannerin's; its first
// read is the slot's full current state.
__attribute__((visibility("hidden"))) static int
attach_fake_input(const char *event, int flags) {
  int slot = get_event_number(event);
  bannerin_handle handle = 0;
  int fd = bannerin_attach(slot, flags, &handle);
  if (fd < 0) {
    Logger::log("Could not attach %s (slot %d): %s\n", event, slot,
                strerror(-fd));
    errno = -fd;
    return -1;
  }

  auto stale = controller_map.find(fd);
  if (stale != controller_map.end()) {
    // The fd number was closed behind this library's back and has been reused. That handle can
    // no longer be detached (it would close the new fd), so only the table entry is replaced.
    Logger::log("Replacing stale controller entry for fd %d\n", fd);
    free(stale->second.event);
    controller_map.erase(stale);
  }

  FakeController controller = {};
  controller.event = strdup(event);
  controller.slot = slot;
  controller.handle = handle;
  controller_map[fd] = controller;

  Logger::log("Adding controller, fd %d event %s slot %d\n", fd, event, slot);
  return fd;
}

__attribute__((visibility("hidden"))) static void
copy_slot_ioctl_string(fakeinput_ioctl_request_t op, void *argp,
                       const char *format, int event_number) {
  size_t size = _IOC_SIZE(op);
  if (!argp || size == 0)
    return;

  snprintf(static_cast<char *>(argp), size, format, event_number);
}

// The handle behind a fake fd, or 0 for any other fd.
__attribute__((visibility("hidden"))) static bannerin_handle
fake_handle(int fd) {
  auto controller = controller_map.find(fd);
  return controller == controller_map.end() ? 0 : controller->second.handle;
}

// write/writev on an unplugged pad fail with ENODEV, as evdev does.
__attribute__((visibility("hidden"))) static bool
fake_fd_write_fails(bannerin_handle handle) {
  int removed = bannerin_is_removed(handle);
  if (removed == 0)
    return false;
  errno = removed > 0 ? ENODEV : -removed;
  return true;
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

  if (!my_open)
    *(void **)&my_open = dlsym(RTLD_NEXT, "open");

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
        fd = attach_fake_input(event, flags);
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
      pathname = hook_dir;
    }
  }

  if (hasMode)
    fd = my_open(pathname, flags, mode);
  else
    fd = my_open(pathname, flags);

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

  if (!my_openat)
    *(void **)&my_openat = dlsym(RTLD_NEXT, "openat");

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
        fd = attach_fake_input(event, flags);
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
      pathname = hook_dir;
    }
  }

  if (hasMode)
    fd = my_openat(dirfd, pathname, flags, mode);
  else
    fd = my_openat(dirfd, pathname, flags);

  if (fake_path)
    free(fake_path);

  return fd;
}

EXPORT int stat(const char *pathname, struct stat *statbuf) {
  if (!my_stat)
    *(void **)&my_stat = dlsym(RTLD_NEXT, "stat");

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
      pathname = hook_dir;
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
  if (!my_fstat)
    *(void **)&my_fstat = dlsym(RTLD_NEXT, "fstat");

  int ret = my_fstat(fd, buf);

  auto controller = controller_map.find(fd);
  if (ret == 0 && controller != controller_map.end()) {
    buf->st_mode = (buf->st_mode & ~S_IFMT) | S_IFCHR;
    buf->st_rdev = get_fake_input_rdev(controller->second.event);
  }

  return ret;
}

EXPORT int access(const char *pathname, int mode) {
  if (!my_access)
    *(void **)&my_access = dlsym(RTLD_NEXT, "access");

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
      pathname = hook_dir;
    }
  }

  int ret = my_access(pathname, mode);
  if (fake_path)
    free(fake_path);
  return ret;
}

EXPORT int faccessat(int dirfd, const char *pathname, int mode, int flags) {
  if (!my_faccessat)
    *(void **)&my_faccessat = dlsym(RTLD_NEXT, "faccessat");

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
      pathname = hook_dir;
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
  if (!my_scandir)
    *(void **)&my_scandir = dlsym(RTLD_NEXT, "scandir");

  if (dirp) {
    if (!strcmp(dirp, "/dev/input")) {
      dirp = hook_dir;
    } else if (udev_data_dir && !strcmp(dirp, "/run/udev/data")) {
      dirp = udev_data_dir;
    }
  }

  return my_scandir(dirp, namelist, filter, compar);
}

EXPORT int inotify_add_watch(int fd, const char *pathname, uint32_t mask) {
  if (!my_inotify_add_watch)
    *(void **)&my_inotify_add_watch = dlsym(RTLD_NEXT, "inotify_add_watch");

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
      pathname = hook_dir;
    }
  }

  int ret = my_inotify_add_watch(fd, pathname, mask);
  if (fake_path)
    free(fake_path);
  return ret;
}

EXPORT int ioctl(int fd, fakeinput_ioctl_request_t op, ...) {
  va_list va;
  void *argp;

  va_start(va, op);
  argp = va_arg(va, void *);
  va_end(va);

  auto controller = controller_map.find(fd);
  if (controller == controller_map.end()) {
    return syscall(SYS_ioctl, fd, op, argp);
  }

  int type = (op >> 8 & 0xFF);
  int number = (op >> 0 & 0xFF);
  const char *event = controller->second.event ? controller->second.event : "event0";
  int event_number = controller->second.slot;

  if (type == 0x45 && number == 0x1) {
    Logger::log("Hooking ioctl EVIOCGVERSION for event %s\n", event);
    int version = 65536;
    memcpy(argp, (void *)&version, sizeof(int));
    return 0;
  } else if (type == 0x45 && number == 0x2) {
    Logger::log("Hooking ioctl EVIOCGID for event %s\n", event);
    struct input_id id;
    memset(&id, 0, sizeof(id));
    struct bannerin_identity live;
    if (live_pad_identity(controller->second.handle, &live)) {
      id.bustype = live.bustype;
      id.vendor = live.vendor;
      id.product = live.product;
      id.version = live.version;
    } else {
      id.bustype = 0x03;
      // A real pad reports the same vendor/product on every port (see PAD_IDENTITIES).
      id.vendor = pad_identity ? pad_identity->vendor
                               : static_cast<uint16_t>(GAMEPAD_VENDOR_ID_BASE + event_number);
      id.product = pad_identity ? pad_identity->product
                                : static_cast<uint16_t>(GAMEPAD_PRODUCT_ID_BASE + event_number);
      id.version = GAMEPAD_VERSION;
    }
    memcpy(argp, (void *)&id, sizeof(id));
    return 0;
  } else if (type == 0x45 && number == 0x6) {
    Logger::log("Hooking ioctl EVIOCGNAME for event %s\n", event);
    struct bannerin_identity live;
    if (live_pad_identity(controller->second.handle, &live)) {
      copy_live_name(op, argp, live);
      return 0;
    }
    copy_slot_ioctl_string(op, argp,
                                  pad_identity ? pad_identity->name_template
                                               : GAMEPAD_NAME_TEMPLATE,
                                  event_number);
    return 0;
  } else if (type == 0x45 && number == 0x7) {
    Logger::log("Hooking ioctl EVIOCGPHYS for event %s\n", event);
    copy_slot_ioctl_string(op, argp, GAMEPAD_PHYS_TEMPLATE, event_number);
    return 0;
  } else if (type == 0x45 && number == 0x8) {
    Logger::log("Hooking ioctl EVIOCGUNIQ for event %s\n", event);
    copy_slot_ioctl_string(op, argp, GAMEPAD_UNIQ_TEMPLATE, event_number);
    return 0;
  } else if (type == 0x45 && number == 0x9) {
    Logger::log("Hooking ioctl EVIOCGPROP for event %s\n", event);
    return 0;
  } else if (type == 0x45 && number == 0x18) {
    Logger::log("Hooking ioctl EVIOCGKEY(len) for event %s\n", event);
    // The buttons held right now (an unplugged pad reads as none held).
    unsigned char bitmask[(KEY_MAX + 8) / 8] = {0};
    struct bannerin_state state;
    if (bannerin_current_state(controller->second.handle, &state) == 0) {
      for (int i = 0; i < BANNERIN_BUTTONS; i++)
        if (state.buttons[i])
          bitmask[kPadButtonCodes[i] / 8] |= 1u << (kPadButtonCodes[i] % 8);
    }
    size_t size = std::min<size_t>(_IOC_SIZE(op), sizeof(bitmask));
    if (argp && size)
      memcpy(argp, bitmask, size);
    return 0;
  } else if (type == 0x45 && number == 0x20) {
    Logger::log("Hooking ioctl EVIOCGBIT(0, len) for event %s\n", event);
    char bitmask[EV_MAX / 8] = {0};
    bitmask[EV_SYN / 8] |= (1 << (EV_SYN % 8));
    bitmask[EV_KEY / 8] |= (1 << (EV_KEY % 8));
    bitmask[EV_ABS / 8] |= (1 << (EV_ABS % 8));
    memcpy(argp, (void *)&bitmask, sizeof(bitmask));
    return 0;
  } else if (type == 0x45 && number == 0x21) {
    Logger::log("Hooking ioctl EVIOCGBIT(EV_KEY, len) for event %s\n", event);
    char bitmask[KEY_MAX / 8] = {0};
    const int xbox_buttons[] = {BTN_A,    BTN_B,      BTN_X,      BTN_Y,
                                BTN_TL,   BTN_TR,     BTN_SELECT, BTN_START,
                                BTN_MODE, BTN_THUMBL, BTN_THUMBR};
    for (int button : xbox_buttons)
      bitmask[button / 8] |= (1 << (button % 8));
    memcpy(argp, (void *)&bitmask, sizeof(bitmask));
    return 0;
  } else if (type == 0x45 && number == 0x22) {
    Logger::log("Hooking ioctl EVIOCGBIT(EV_REL, len) for event %s\n", event);
    char bitmask[REL_MAX / 8] = {0};
    memcpy(argp, (void *)&bitmask, sizeof(bitmask));
    return 0;
  } else if (type == 0x45 && number == 0x23) {
    Logger::log("Hooking ioctl EVIOCGBIT(EV_ABS, len) for event %s\n", event);
    char bitmask[ABS_MAX / 8] = {0};
    bitmask[ABS_X / 8] |= (1 << (ABS_X % 8));
    bitmask[ABS_Y / 8] |= (1 << (ABS_Y % 8));
    bitmask[ABS_RX / 8] |= (1 << (ABS_RX % 8));
    bitmask[ABS_RY / 8] |= (1 << (ABS_RY % 8));
    bitmask[ABS_GAS / 8] |= (1 << (ABS_GAS % 8));
    bitmask[ABS_BRAKE / 8] |= (1 << (ABS_BRAKE % 8));
    bitmask[ABS_HAT0X / 8] |= (1 << (ABS_HAT0X % 8));
    bitmask[ABS_HAT0Y / 8] |= (1 << (ABS_HAT0Y % 8));
    memcpy(argp, (void *)&bitmask, sizeof(bitmask));
    return 0;
  } else if (type == 0x45 && number == 0x35) {
    Logger::log("Hooking ioctl EVIOCGBIT(EV_FF, len) for event %s\n", event);
    char bitmask[FF_MAX / 8] = {0};
    bitmask[FF_RUMBLE / 8] |= (1 << (FF_RUMBLE % 8));
    bitmask[FF_PERIODIC / 8] |= (1 << (FF_PERIODIC % 8));
    memcpy(argp, (void *)&bitmask, sizeof(bitmask));
    return 0;
  } else if (type == 0x45 && number == 0x80) {
    struct ff_effect *effect = static_cast<struct ff_effect *>(argp);
    if (effect->id == -1)
      effect->id = next_ff_id++;
    ff_effects[effect->id] = *effect;

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
    ff_effects.erase(id);
    return 0;
  } else if (type == 0x45 && number == 0x84) {
    int max_effects = 16;
    memcpy(argp, &max_effects, sizeof(int));
    return 0;
  } else if (type == 0x45 && number >= 0x40 && number <= 0x51) {
    Logger::log("Hooking ioctl EVIOCGABS(ABS) for event %s\n", event);
    struct input_absinfo abs_info;
    memset(&abs_info, 0, sizeof(abs_info));
    if (number >= 0x40 && number <= 0x44) {
      abs_info.value = 0;
      abs_info.minimum = -32768;
      abs_info.maximum = 32767;
    } else if (number >= 0x49 && number <= 0x4A) {
      abs_info.value = 0;
      abs_info.minimum = 0;
      abs_info.maximum = 255;
    } else if (number >= 0x50 && number <= 0x51) {
      abs_info.value = 0;
      abs_info.minimum = -1;
      abs_info.maximum = 1;
    }
    // The axis's current value (neutral for an unplugged pad).
    struct bannerin_state state;
    if (bannerin_current_state(controller->second.handle, &state) == 0) {
      for (int i = 0; i < BANNERIN_AXES; i++)
        if (kPadAxisCodes[i] == number - 0x40)
          abs_info.value = state.axes[i];
    }
    memcpy(argp, (void *)&abs_info, sizeof(abs_info));
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
    struct bannerin_identity live;
    if (live_pad_identity(controller->second.handle, &live)) {
      copy_live_name(op, argp, live);
      return 0;
    }
    copy_slot_ioctl_string(op, argp,
                                  pad_identity ? pad_identity->name_template
                                               : GAMEPAD_NAME_TEMPLATE,
                                  event_number);
    return 0;
  } else {
    Logger::log("Unhandled evdev ioctl, type %d number %d\n", type, number);
    return syscall(SYS_ioctl, fd, op, argp);
  }
}

EXPORT int close(int fd) {
  if (!my_close)
    *(void **)&my_close = dlsym(RTLD_NEXT, "close");

  auto controller = controller_map.find(fd);
  if (controller != controller_map.end()) {
    Logger::log("Removing controller, fd %d event %s\n", controller->first,
                controller->second.event ? controller->second.event : "(unknown)");
    bannerin_handle handle = controller->second.handle;
    free(controller->second.event);
    controller_map.erase(controller);
    // bannerin owns the fd: detaching releases the handle and closes it.
    if (bannerin_detach(handle) == 0)
      return 0;
  }

  return my_close(fd);
}

EXPORT ssize_t read(int fd, void *buf, size_t count) {
  bannerin_handle handle = fake_handle(fd);
  if (handle) {
    if (count < BANNERIN_EVENT_SIZE) {
      errno = EINVAL;
      return -1;
    }
    // O_NONBLOCK as the fd has it now: the program may have changed it since open().
    int flags = fcntl(fd, F_GETFL);
    bool nonblock = flags >= 0 && (flags & O_NONBLOCK);
    // No table entry is referenced from here on, so a close() in another thread is safe: the
    // read then fails with EBADF.
    ssize_t n = bannerin_read(handle, buf, count, nonblock ? 1 : 0);
    if (n < 0) {
      errno = static_cast<int>(-n);
      return -1;
    }
    return n;
  }
  return syscall(SYS_read, fd, buf, count);
}

EXPORT ssize_t write(int fd, const void *buf, size_t count) {
  if (!my_write)
    *(void **)&my_write = dlsym(RTLD_NEXT, "write");

  auto controller = controller_map.find(fd);
  if (controller != controller_map.end()) {
    if (fake_fd_write_fails(controller->second.handle))
      return -1;

    const struct input_event *ev = nullptr;
    uint16_t slot = static_cast<uint16_t>(controller->second.slot);
    if (count == sizeof(struct input_event)) {
      ev = static_cast<const struct input_event *>(buf);
      check_ff_event(ev, slot);
    }

    return static_cast<ssize_t>(count);
  }
  return my_write(fd, buf, count);
}

EXPORT ssize_t writev(int fd, const struct iovec *iov, int iovcnt) {
  auto controller = controller_map.find(fd);
  if (controller != controller_map.end()) {
    if (fake_fd_write_fails(controller->second.handle))
      return -1;

    uint16_t slot = static_cast<uint16_t>(controller->second.slot);
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
  return syscall(SYS_writev, fd, iov, iovcnt);
}

// Pad fds are real kernel fds that bannerin keeps readable while input is pending and hung up
// once the pad is unplugged, so poll, ppoll and select need no emulation: they go straight to
// libc, with the caller's timeout and signal mask untouched.
EXPORT int poll(struct pollfd *fds, nfds_t nfds, int timeout) {
  if (!my_poll)
    *(void **)&my_poll = dlsym(RTLD_NEXT, "poll");
  if (my_poll)
    return my_poll(fds, nfds, timeout);
  struct timespec ts = {timeout / 1000, (timeout % 1000) * 1000000L};
  return syscall(SYS_ppoll, fds, nfds, timeout < 0 ? nullptr : &ts, nullptr,
                 sizeof(sigset_t));
}

EXPORT int ppoll(struct pollfd *fds, nfds_t nfds,
                 const struct timespec *timeout, const sigset_t *sigmask) {
  if (!my_ppoll)
    *(void **)&my_ppoll = dlsym(RTLD_NEXT, "ppoll");
  if (my_ppoll)
    return my_ppoll(fds, nfds, timeout, sigmask);
  return syscall(SYS_ppoll, fds, nfds, timeout, sigmask, sizeof(sigset_t));
}

EXPORT int select(int nfds, fd_set *readfds, fd_set *writefds,
                  fd_set *exceptfds, struct timeval *timeout) {
  if (!my_select)
    *(void **)&my_select = dlsym(RTLD_NEXT, "select");
  if (my_select)
    return my_select(nfds, readfds, writefds, exceptfds, timeout);
  errno = ENOSYS;
  return -1;
}
