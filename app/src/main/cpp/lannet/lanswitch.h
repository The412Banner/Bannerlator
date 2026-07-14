/*
 * lanswitch — the 2-node virtual-switch decision for an outbound tun packet.
 *
 * Given a raw IPv4 packet read from our VpnService tun, decide what the overlay
 * should do with it. This is the heart of "make two phones one subnet": LAN
 * discovery broadcasts fan out to the peer; direct-connect unicast to the peer's
 * virtual IP is forwarded; everything else is dropped (the VpnService is scoped
 * to Bannerlator and only the virtual subnet + broadcast/multicast is routed to
 * the tun, so a DROP here is a belt-and-braces guard).
 */
#ifndef LANNET_SWITCH_H
#define LANNET_SWITCH_H

#include <stddef.h>
#include <stdint.h>

typedef enum {
    LSW_DROP = 0,       /* not for the overlay */
    LSW_UNICAST = 1,    /* forward to the peer (dst == peer virtual IP) */
    LSW_BROADCAST = 2   /* fan out to all peers, re-inject as-is on the far tun */
} lsw_action;

/* All IPs are host-order uint32 (e.g. 10.99.0.1 == 0x0A630001).
 * self_vip/peer_vip: our and the peer's virtual IPs. prefix_len: e.g. 24.
 * local_bcast: the underlying real network's directed-broadcast (e.g.
 * 192.168.1.255), which many LAN games target instead of 255.255.255.255;
 * the VpnService routes it into the tun so we can catch it. 0 == none. */
lsw_action lsw_classify(const uint8_t *pkt, size_t len,
                        uint32_t self_vip, uint32_t peer_vip, uint32_t prefix_len,
                        uint32_t local_bcast);

/* Convenience: a.b.c.d -> host-order uint32. */
uint32_t lsw_ip(uint8_t a, uint8_t b, uint8_t c, uint8_t d);

#endif /* LANNET_SWITCH_H */
