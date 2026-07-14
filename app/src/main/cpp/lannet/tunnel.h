/*
 * tunnel — the lannet client pump. Bridges a tun fd to the relay:
 *   outbound: read IP packet from tun -> lsw_classify -> wrap in DATA -> relay
 *   inbound:  recv DATA from relay -> write payload packet to tun
 * Plus JOIN on open and periodic PING keepalive.
 *
 * tun_fd is any packet-granular fd: a real VpnService tun on Android, or a
 * SOCK_DGRAM socketpair in the host test. The pump creates no threads — the
 * caller runs lannet_tunnel_run() on a thread of its choosing (a JNI worker
 * thread on Android).
 */
#ifndef LANNET_TUNNEL_H
#define LANNET_TUNNEL_H

#include <stdint.h>
#include "proto.h"

typedef struct {
    int      tun_fd;
    int      sock_fd;      /* UDP socket connect()ed to the relay */
    uint32_t self_vip;     /* host-order */
    uint32_t peer_vip;
    uint32_t prefix_len;   /* e.g. 24 */
    uint32_t local_bcast;  /* underlying real-net directed bcast, host-order; 0 == none */
    uint8_t  role;
    char     room[LANNET_ROOM_LEN];
    volatile int running;
} lannet_tunnel;

/* Opens the relay socket, sends JOIN. Returns 0 or <0 on error. role =
 * LANNET_ROLE_HOST (vip .1) or LANNET_ROLE_CLIENT (vip .2) on 10.99.0.0/24.
 * local_bcast = underlying real-net directed broadcast (host-order, 0 == none). */
int  lannet_tunnel_open(lannet_tunnel *t, int tun_fd,
                        const char *relay_ip, int relay_port,
                        const char *room, uint8_t role, uint32_t local_bcast);

/* Blocking pump loop until lannet_tunnel_stop(). */
void lannet_tunnel_run(lannet_tunnel *t);

/* Signals the loop to exit (returns within ~1s). */
void lannet_tunnel_stop(lannet_tunnel *t);

/* Closes the relay socket (call after run() returns). */
void lannet_tunnel_close(lannet_tunnel *t);

#endif /* LANNET_TUNNEL_H */
