#include "tunnel.h"
#include "frame.h"
#include "lanswitch.h"
#include <string.h>
#include <unistd.h>
#include <time.h>
#include <poll.h>
#include <arpa/inet.h>
#include <sys/socket.h>

#define KEEPALIVE_SEC 15
#define VNET_A 10
#define VNET_B 99
#define VNET_C 0

static long now_sec(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec;
}

static void send_join(lannet_tunnel *t) {
    uint8_t f[LANNET_HDR_LEN];
    size_t n = lannet_build(f, LANNET_JOIN, t->role, 0, t->room, NULL, 0);
    send(t->sock_fd, f, n, 0);
}

int lannet_tunnel_open(lannet_tunnel *t, int tun_fd,
                       const char *relay_ip, int relay_port,
                       const char *room, uint8_t role) {
    memset(t, 0, sizeof(*t));
    t->tun_fd = tun_fd;
    t->role = role;
    t->prefix_len = 24;
    /* host = .1, client = .2 on 10.99.0.0/24 */
    uint32_t host_ip = lsw_ip(VNET_A, VNET_B, VNET_C, 1);
    uint32_t cli_ip  = lsw_ip(VNET_A, VNET_B, VNET_C, 2);
    if (role == LANNET_ROLE_HOST) { t->self_vip = host_ip; t->peer_vip = cli_ip; }
    else                          { t->self_vip = cli_ip;  t->peer_vip = host_ip; }
    size_t rl = strlen(room); if (rl > LANNET_ROOM_LEN) rl = LANNET_ROOM_LEN;
    memcpy(t->room, room, rl); /* t is zeroed above */

    int s = socket(AF_INET, SOCK_DGRAM, 0);
    if (s < 0) return -1;
    struct sockaddr_in ra;
    memset(&ra, 0, sizeof(ra));
    ra.sin_family = AF_INET;
    ra.sin_port = htons((uint16_t)relay_port);
    if (inet_pton(AF_INET, relay_ip, &ra.sin_addr) != 1) { close(s); return -2; }
    if (connect(s, (struct sockaddr *)&ra, sizeof(ra)) < 0) { close(s); return -3; }
    t->sock_fd = s;
    t->running = 1;
    send_join(t);
    return 0;
}

void lannet_tunnel_run(lannet_tunnel *t) {
    uint8_t pkt[2048];
    uint8_t frame[2048 + LANNET_HDR_LEN];
    long last_ka = now_sec();

    while (t->running) {
        struct pollfd fds[2];
        fds[0].fd = t->tun_fd;  fds[0].events = POLLIN; fds[0].revents = 0;
        fds[1].fd = t->sock_fd; fds[1].events = POLLIN; fds[1].revents = 0;
        int r = poll(fds, 2, 1000);

        if (r > 0 && (fds[0].revents & POLLIN)) {
            /* outbound: a packet the game emitted on the tun */
            ssize_t n = read(t->tun_fd, pkt, sizeof(pkt));
            if (n > 0 && n <= LANNET_MAX_PAYLOAD) {
                lsw_action act = lsw_classify(pkt, (size_t)n,
                                              t->self_vip, t->peer_vip, t->prefix_len);
                if (act != LSW_DROP) {
                    uint8_t flags = (act == LSW_BROADCAST) ? LANNET_FLAG_BROADCAST : 0;
                    size_t fl = lannet_build(frame, LANNET_DATA, t->role, flags,
                                             t->room, pkt, (uint16_t)n);
                    send(t->sock_fd, frame, fl, 0);
                }
            }
        }

        if (r > 0 && (fds[1].revents & POLLIN)) {
            /* inbound: a peer's frame forwarded by the relay */
            ssize_t n = recv(t->sock_fd, frame, sizeof(frame), 0);
            if (n > 0) {
                struct lannet_hdr h;
                const uint8_t *payload; uint16_t plen;
                if (lannet_parse(frame, (size_t)n, &h, &payload, &plen) == 0
                    && h.type == LANNET_DATA && plen > 0) {
                    ssize_t w = write(t->tun_fd, payload, plen);
                    (void)w;
                }
            }
        }

        long now = now_sec();
        if (now - last_ka >= KEEPALIVE_SEC) {
            uint8_t f[LANNET_HDR_LEN];
            size_t n = lannet_build(f, LANNET_PING, t->role, 0, t->room, NULL, 0);
            send(t->sock_fd, f, n, 0);
            last_ka = now;
        }
    }
}

void lannet_tunnel_stop(lannet_tunnel *t) { t->running = 0; }
void lannet_tunnel_close(lannet_tunnel *t) { if (t->sock_fd > 0) close(t->sock_fd); }
