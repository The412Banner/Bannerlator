/*
 * lannet wire protocol (client <-> relay), UDP.
 *
 * P1 (relay-only MVP): both peers connect OUTBOUND to the relay, so no NAT
 * traversal is needed. The relay forwards frames between the members of a room.
 *
 * SECURITY SEAM: in P1 the DATA payload is the raw tunnelled IP packet in the
 * clear for the transport bring-up. Before any real release the payload MUST be
 * a Noise-IK-encrypted blob (relay sees only ciphertext). The framing below is
 * unchanged by that — Noise wraps `payload`, the header stays plaintext so the
 * relay can route. See lannet/README.md "crypto".
 */
#ifndef LANNET_PROTO_H
#define LANNET_PROTO_H

#include <stdint.h>

#define LANNET_MAGIC   0x4C4E             /* 'L''N' */
#define LANNET_VERSION 1
#define LANNET_ROOM_LEN 8                 /* 6-char code, NUL-padded to 8 */
#define LANNET_MAX_PAYLOAD 1500

enum lannet_msg_type {
    LANNET_JOIN = 1,   /* client registers (room, role); relay learns its endpoint */
    LANNET_DATA = 2,   /* tunnelled frame to forward to the other room member(s) */
    LANNET_PING = 3    /* keepalive / NAT-binding refresh */
};

enum lannet_role {
    LANNET_ROLE_HOST   = 1,   /* virtual 10.99.0.1 */
    LANNET_ROLE_CLIENT = 2    /* virtual 10.99.0.2 */
};

/* DATA flags */
#define LANNET_FLAG_BROADCAST 0x01        /* fan out to all other members */

/* Fixed 12-byte header, network byte order for the 16-bit fields. */
struct lannet_hdr {
    uint16_t magic;                       /* LANNET_MAGIC */
    uint8_t  version;                     /* LANNET_VERSION */
    uint8_t  type;                        /* enum lannet_msg_type */
    uint8_t  role;                        /* enum lannet_role (JOIN) */
    uint8_t  flags;                       /* DATA flags */
    uint16_t payload_len;                 /* bytes following the header (DATA) */
    char     room[LANNET_ROOM_LEN];       /* room code, NUL-padded */
};

#define LANNET_HDR_LEN (sizeof(struct lannet_hdr))

#endif /* LANNET_PROTO_H */
