/* Android text -> Gamescope's Xwayland selection. Gamescope's Wayland backend does not
 * relay the outer compositor's clipboard. libX11 is loaded from the guest at runtime;
 * the small stable Xlib ABI below keeps this helper independent of cross X11 headers.
 * The private host file is atomically replaced, never put in session logs. */
#define _GNU_SOURCE
#include <sys/inotify.h>
#include <sys/stat.h>
#include <sys/prctl.h>
#include <signal.h>
#include <poll.h>
#include <fcntl.h>
#include <unistd.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <limits.h>

typedef struct _XDisplay Display;
typedef unsigned long Window, Atom, Time;
typedef struct {
    int type; unsigned long serial; int send_event; Display *display;
    Window owner, requestor; Atom selection, target, property; Time time;
} SelectionRequest;
typedef struct {
    int type; unsigned long serial; int send_event; Display *display;
    Window requestor; Atom selection, target, property; Time time;
} SelectionNotify;
typedef union { int type; SelectionRequest request; SelectionNotify selection; long pad[24]; } Event;

#define API(ret, name, args) static ret (*name) args
API(Display *, XOpenDisplay, (const char *));
API(Window, XDefaultRootWindow, (Display *));
API(Window, XCreateSimpleWindow, (Display *, Window, int, int, unsigned, unsigned, unsigned, unsigned long, unsigned long));
API(Atom, XInternAtom, (Display *, const char *, int));
API(int, XSetSelectionOwner, (Display *, Atom, Window, Time));
API(Window, XGetSelectionOwner, (Display *, Atom));
API(int, XChangeProperty, (Display *, Window, Atom, Atom, int, int, const unsigned char *, int));
API(int, XSendEvent, (Display *, Window, int, long, Event *));
API(int, XPending, (Display *));
API(int, XNextEvent, (Display *, Event *));
API(int, XFlush, (Display *));
API(int, XConnectionNumber, (Display *));
API(int, XCloseDisplay, (Display *));
API(void *, XSetErrorHandler, (int (*)(Display *, void *)));

/* Stay below X11's normal maximum property request. Never paste a truncated command. */
#define MAX_TEXT (64 * 1024)
static int ignore_x_error(Display *d, void *event) { (void)d; (void)event; return 0; }

/* The host renames complete files into place. The inode distinguishes copying the same
 * text again after a guest program has taken ownership of the clipboard. */
static int refresh(Display *d, Window w, Atom clipboard, const char *path,
                   char **text, size_t *len, ino_t *inode) {
    int fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return 0;
    struct stat st;
    if (fstat(fd, &st) || !S_ISREG(st.st_mode) || st.st_ino == *inode) { close(fd); return 0; }
    *inode = st.st_ino;
    if (st.st_size < 0 || st.st_size > MAX_TEXT) {
        fprintf(stderr, "== clipboard: text exceeds %d bytes, not pasted\n", MAX_TEXT);
        if (XGetSelectionOwner(d, clipboard) == w) XSetSelectionOwner(d, clipboard, 0, 0);
        XFlush(d);
        free(*text); *text = NULL; *len = 0;
        close(fd); return 0;
    }
    char *copy = malloc((size_t)st.st_size + 1);
    if (!copy) { close(fd); return 0; }
    size_t got = 0;
    while (got < (size_t)st.st_size) {
        ssize_t n = read(fd, copy + got, (size_t)st.st_size - got);
        if (n > 0) got += n;
        else if (n < 0 && errno == EINTR) continue;
        else break;
    }
    close(fd);
    if (got != (size_t)st.st_size) { free(copy); return 0; }
    copy[got] = 0;
    free(*text); *text = copy; *len = got;
    if (got) XSetSelectionOwner(d, clipboard, w, 0);
    else if (XGetSelectionOwner(d, clipboard) == w) XSetSelectionOwner(d, clipboard, 0, 0);
    XFlush(d);
    fprintf(stderr, "== clipboard: Android text available in Xwayland (%zu bytes)\n", got);
    return 1;
}

int main(int argc, char **argv) {
    if (argc != 2 || !getenv("DISPLAY")) return 1;
    pid_t parent_pid = getppid();
    if (parent_pid == 1 || prctl(PR_SET_PDEATHSIG, SIGTERM) || getppid() != parent_pid) return 1;
    void *lib = dlopen("libX11.so.6", RTLD_NOW | RTLD_LOCAL);
    if (!lib) { fprintf(stderr, "== clipboard: libX11 unavailable\n"); return 1; }
#define LOAD(name) do { *(void **)(&name) = dlsym(lib, #name); if (!name) return 1; } while (0)
    LOAD(XOpenDisplay); LOAD(XDefaultRootWindow); LOAD(XCreateSimpleWindow); LOAD(XInternAtom);
    LOAD(XSetSelectionOwner); LOAD(XGetSelectionOwner); LOAD(XChangeProperty); LOAD(XSendEvent);
    LOAD(XPending); LOAD(XNextEvent); LOAD(XFlush); LOAD(XConnectionNumber); LOAD(XCloseDisplay);
    LOAD(XSetErrorHandler);
    Display *d = XOpenDisplay(NULL);
    if (!d) { fprintf(stderr, "== clipboard: cannot open Xwayland\n"); return 1; }
    XSetErrorHandler(ignore_x_error); /* A paste requester can close its window at any time. */
    Window w = XCreateSimpleWindow(d, XDefaultRootWindow(d), 0, 0, 1, 1, 0, 0, 0);
    Atom clipboard = XInternAtom(d, "CLIPBOARD", 0);
    Atom targets = XInternAtom(d, "TARGETS", 0), utf8 = XInternAtom(d, "UTF8_STRING", 0);
    Atom plain = XInternAtom(d, "text/plain;charset=utf-8", 0), text_atom = XInternAtom(d, "TEXT", 0);
    Atom string = XInternAtom(d, "STRING", 0);
    char parent[PATH_MAX];
    if (strlen(argv[1]) >= sizeof(parent)) return 1;
    strcpy(parent, argv[1]); char *slash = strrchr(parent, '/');
    if (!slash) return 1;
    *slash = 0;
    int watch = inotify_init1(IN_CLOEXEC | IN_NONBLOCK);
    if (watch < 0 || inotify_add_watch(watch, parent, IN_MOVED_TO | IN_CLOSE_WRITE) < 0) return 1;
    char *text = NULL; size_t len = 0; ino_t inode = 0;
    refresh(d, w, clipboard, argv[1], &text, &len, &inode);
    struct pollfd fds[] = {{XConnectionNumber(d), POLLIN, 0}, {watch, POLLIN, 0}};
    for (;;) {
        while (XPending(d)) {
            Event e; XNextEvent(d, &e);
            if (e.type != 30) continue; /* SelectionRequest */
            SelectionRequest *r = &e.request;
            Atom property = r->property ? r->property : r->target;
            Event reply = {0};
            reply.selection = (SelectionNotify){31, 0, 1, d, r->requestor, r->selection, r->target, 0, r->time};
            if (r->selection == clipboard && text && len) {
                size_t ascii; for (ascii = 0; ascii < len && (unsigned char)text[ascii] < 128; ascii++) {}
                if (r->target == targets) {
                    Atom supported[] = {targets, utf8, plain, text_atom, string};
                    XChangeProperty(d, r->requestor, property, 4 /* XA_ATOM */, 32, 0,
                                    (const unsigned char *)supported, ascii == len ? 5 : 4);
                    reply.selection.property = property;
                } else if (r->target == utf8 || r->target == plain || r->target == text_atom) {
                    XChangeProperty(d, r->requestor, property, utf8, 8, 0, (const unsigned char *)text, (int)len);
                    reply.selection.property = property;
                } else if (r->target == string) {
                    /* STRING is Latin-1. Offer it only for ASCII rather than corrupt UTF-8. */
                    if (ascii == len) {
                        XChangeProperty(d, r->requestor, property, string, 8, 0, (const unsigned char *)text, (int)len);
                        reply.selection.property = property;
                    }
                }
            }
            XSendEvent(d, r->requestor, 0, 0, &reply); XFlush(d);
        }
        if (poll(fds, 2, -1) < 0) { if (errno == EINTR) continue; break; }
        if (fds[0].revents & (POLLERR | POLLHUP | POLLNVAL)) break;
        if (fds[1].revents & POLLIN) {
            char events[4096]; while (read(watch, events, sizeof(events)) > 0) {}
            refresh(d, w, clipboard, argv[1], &text, &len, &inode);
        }
    }
    free(text); close(watch); XCloseDisplay(d); dlclose(lib);
    return 0;
}
