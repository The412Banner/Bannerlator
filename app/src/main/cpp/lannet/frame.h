/* lannet frame build/parse helpers (shared by tunnel.c + tests). */
#ifndef LANNET_FRAME_H
#define LANNET_FRAME_H

#include <stddef.h>
#include <stdint.h>
#include "proto.h"

/* Build a frame into buf (must hold LANNET_HDR_LEN + plen). Returns total len. */
size_t lannet_build(uint8_t *buf, uint8_t type, uint8_t role, uint8_t flags,
                    const char *room, const uint8_t *payload, uint16_t plen);

/* Parse a frame's header. Returns 0 on success (setting h, payload, plen),
 * <0 if malformed. payload points into buf. */
int lannet_parse(const uint8_t *buf, size_t len, struct lannet_hdr *h,
                 const uint8_t **payload, uint16_t *plen);

#endif
