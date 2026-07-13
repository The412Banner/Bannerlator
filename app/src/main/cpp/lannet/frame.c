#include "frame.h"
#include <string.h>

static void wr_be16(uint8_t *p, uint16_t v) { p[0] = v >> 8; p[1] = v & 0xFF; }
static uint16_t rd_be16(const uint8_t *p) { return (uint16_t)((p[0] << 8) | p[1]); }

size_t lannet_build(uint8_t *buf, uint8_t type, uint8_t role, uint8_t flags,
                    const char *room, const uint8_t *payload, uint16_t plen) {
    memset(buf, 0, LANNET_HDR_LEN);
    wr_be16(buf + 0, LANNET_MAGIC);
    buf[2] = LANNET_VERSION;
    buf[3] = type;
    buf[4] = role;
    buf[5] = flags;
    wr_be16(buf + 6, plen);
    strncpy((char *)buf + 8, room, LANNET_ROOM_LEN);
    if (payload && plen) memcpy(buf + LANNET_HDR_LEN, payload, plen);
    return LANNET_HDR_LEN + plen;
}

int lannet_parse(const uint8_t *buf, size_t len, struct lannet_hdr *h,
                 const uint8_t **payload, uint16_t *plen) {
    if (len < LANNET_HDR_LEN) return -1;
    h->magic = rd_be16(buf + 0);
    h->version = buf[2];
    h->type = buf[3];
    h->role = buf[4];
    h->flags = buf[5];
    h->payload_len = rd_be16(buf + 6);
    memcpy(h->room, buf + 8, LANNET_ROOM_LEN);
    if (h->magic != LANNET_MAGIC || h->version != LANNET_VERSION) return -1;
    if (len < LANNET_HDR_LEN + (size_t)h->payload_len) return -1;
    if (payload) *payload = buf + LANNET_HDR_LEN;
    if (plen) *plen = h->payload_len;
    return 0;
}
