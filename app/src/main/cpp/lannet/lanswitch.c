#include "lanswitch.h"

uint32_t lsw_ip(uint8_t a, uint8_t b, uint8_t c, uint8_t d) {
    return ((uint32_t)a << 24) | ((uint32_t)b << 16) | ((uint32_t)c << 8) | d;
}

static uint32_t rd_be32(const uint8_t *p) {
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | p[3];
}

lsw_action lsw_classify(const uint8_t *pkt, size_t len,
                        uint32_t self_vip, uint32_t peer_vip, uint32_t prefix_len,
                        uint32_t local_bcast) {
    if (len < 20) return LSW_DROP;               /* too short for an IPv4 header */
    if ((pkt[0] >> 4) != 4) return LSW_DROP;      /* IPv4 only for now */

    uint32_t dst = rd_be32(pkt + 16);            /* IPv4 dst is bytes 16..19 */

    /* limited broadcast */
    if (dst == 0xFFFFFFFFu) return LSW_BROADCAST;

    /* multicast 224.0.0.0/4 */
    if ((dst & 0xF0000000u) == 0xE0000000u) return LSW_BROADCAST;

    /* underlying real-network directed broadcast (e.g. 192.168.1.255) — the
     * common case for LAN games that don't use the limited-broadcast address.
     * The VpnService routes exactly this /32 into the tun for us to catch. */
    if (local_bcast != 0 && dst == local_bcast) return LSW_BROADCAST;

    /* subnet-directed broadcast for our virtual subnet (host bits all ones) */
    if (prefix_len < 32) {
        uint32_t hostmask = (prefix_len == 0) ? 0xFFFFFFFFu : (0xFFFFFFFFu >> prefix_len);
        uint32_t netmask = ~hostmask;
        if ((self_vip & netmask) == (dst & netmask) && (dst & hostmask) == hostmask)
            return LSW_BROADCAST;
    }

    /* direct-connect to the peer's virtual IP */
    if (dst == peer_vip) return LSW_UNICAST;

    return LSW_DROP;
}
