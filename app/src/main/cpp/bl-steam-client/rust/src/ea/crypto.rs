//! The obfuscation layer around LSX, reimplemented with no new dependencies.
//!
//! Three small things stand between a socket and a conversation the game will accept:
//!
//! 1. **A challenge.** The launcher greets the game with a nonce; the game returns it encrypted, and
//!    a launcher proves itself the same way. The cipher is AES-128-ECB under a *fixed, published*
//!    key — the bytes `0..=15`. It is a handshake, not a secret: anything holding the key can speak,
//!    and the key is sixteen counting numbers.
//! 2. **A session key.** Once agreed, later packets are encrypted under a key derived from a 16-bit
//!    seed through the Microsoft C runtime's `rand()`. Seed 0 means "keep using the fixed key".
//! 3. **A launch code.** `EARtPLaunchCode` is a number derived from today's date. It changes daily
//!    and is pure arithmetic — no server, no account.
//!
//! # Why this is hand-rolled
//!
//! The crate already carries `aes`, but ECB mode lives in a separate crate we do not have, and ECB
//! is only "encrypt each block on its own" — the mode with no chaining. Rather than add a
//! dependency for sixteen bytes at a time, the two ECB helpers here do the block loop and PKCS#7
//! padding directly. Same for hex and for today's date: a few lines each, against `std`.
//!
//! ECB is a poor way to encrypt anything real. That is not our call to make — the game speaks it, so
//! we speak it. Nothing secret travels this socket: it is on loopback, between two processes that
//! are already inside the same app sandbox.

use aes::cipher::{BlockDecrypt, BlockEncrypt, KeyInit};
use aes::Aes128;

/// The fixed key both ends start with: literally the bytes 0 through 15.
pub const CRYPTO_KEY: [u8; 16] = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15];

const PRIME_10K: u32 = 104_729;
const PRIME_20K: u32 = 224_737;
const PRIME_30K: u32 = 350_377;

// ── hex ────────────────────────────────────────────────────────────────────────────────────────

pub fn hex_encode(bytes: &[u8]) -> String {
    const D: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        out.push(D[(b >> 4) as usize] as char);
        out.push(D[(b & 0x0F) as usize] as char);
    }
    out
}

/// Decode hex, ignoring anything that is not a hex digit.
///
/// The tolerance is deliberate and matches the peer: payloads arrive wrapped in XML and may carry
/// stray whitespace or case. An odd number of digits is refused rather than silently truncated.
pub fn hex_decode(text: &str) -> Option<Vec<u8>> {
    let digits: Vec<u8> = text
        .bytes()
        .filter_map(|c| match c {
            b'0'..=b'9' => Some(c - b'0'),
            b'a'..=b'f' => Some(c - b'a' + 10),
            b'A'..=b'F' => Some(c - b'A' + 10),
            _ => None,
        })
        .collect();
    if digits.len() % 2 != 0 {
        return None;
    }
    Some(digits.chunks(2).map(|p| (p[0] << 4) | p[1]).collect())
}

// ── AES-128-ECB with PKCS#7 ────────────────────────────────────────────────────────────────────

pub fn encrypt(plain: &[u8], key: &[u8; 16]) -> Vec<u8> {
    let cipher = Aes128::new(key.into());
    // PKCS#7 always adds padding, a whole block when the input already fits — the peer strips by the
    // final byte's value, so omitting it on an exact multiple would eat 16 bytes of plaintext.
    let pad = 16 - (plain.len() % 16);
    let mut buf = Vec::with_capacity(plain.len() + pad);
    buf.extend_from_slice(plain);
    buf.extend(std::iter::repeat(pad as u8).take(pad));
    for block in buf.chunks_mut(16) {
        cipher.encrypt_block(block.into());
    }
    buf
}

pub fn decrypt(cipher_bytes: &[u8], key: &[u8; 16]) -> Option<Vec<u8>> {
    if cipher_bytes.is_empty() || cipher_bytes.len() % 16 != 0 {
        return None;
    }
    let cipher = Aes128::new(key.into());
    let mut buf = cipher_bytes.to_vec();
    for block in buf.chunks_mut(16) {
        cipher.decrypt_block(block.into());
    }
    let pad = *buf.last()? as usize;
    if pad == 0 || pad > 16 || pad > buf.len() {
        return None;
    }
    if !buf[buf.len() - pad..].iter().all(|&b| b as usize == pad) {
        return None;
    }
    buf.truncate(buf.len() - pad);
    Some(buf)
}

// ── challenge ──────────────────────────────────────────────────────────────────────────────────

/// Our answer to the game's challenge: encrypt it under the fixed key, hex-encoded.
pub fn make_challenge_response(challenge: &str) -> String {
    hex_encode(&encrypt(challenge.as_bytes(), &CRYPTO_KEY))
}

/// Verify a peer's answer to a challenge we issued.
pub fn check_challenge_response(response: &str, challenge: &str) -> bool {
    hex_decode(response)
        .and_then(|bytes| decrypt(&bytes, &CRYPTO_KEY))
        .map(|plain| plain == challenge.as_bytes())
        .unwrap_or(false)
}

// ── session key ────────────────────────────────────────────────────────────────────────────────

/// Microsoft's C runtime `rand()`. Reproduced exactly because the key derivation below depends on
/// its precise arithmetic, not on randomness.
#[derive(Default)]
struct CRandom {
    seed: u32,
}

impl CRandom {
    fn seed(&mut self, seed: u32) {
        self.seed = seed;
    }

    fn rand(&mut self) -> i32 {
        self.seed = self
            .seed
            .wrapping_mul(214_013)
            .wrapping_add(2_531_011);
        // NOTE: 0xFFFF, not the 0x7FFF a textbook MSVC `rand()` uses. The peer keeps the full low
        // 16 bits, so masking to 15 would derive a different session key on roughly half the draws
        // and the connection would fail somewhere far from here.
        ((self.seed >> 16) & 0xFFFF) as i32
    }
}

/// Derive the session key from the seed the peer names. Seed 0 keeps the fixed key.
pub fn make_lsx_key(seed: u16) -> [u8; 16] {
    if seed == 0 {
        return CRYPTO_KEY;
    }
    let mut rng = CRandom::default();
    rng.seed(7);
    let mixed = (rng.rand() as u32).wrapping_add(seed as u32);
    rng.seed(mixed);
    let mut key = [0u8; 16];
    for slot in key.iter_mut() {
        *slot = rng.rand() as u8;
    }
    key
}

// ── daily launch code ──────────────────────────────────────────────────────────────────────────

/// `EARtPLaunchCode` — arithmetic over today's UTC date. Changes daily, needs nothing external.
pub fn rtp_handshake(year: u32, month: u32, day: u32) -> u32 {
    let time = (PRIME_10K.wrapping_mul(year)) ^ (month.wrapping_mul(PRIME_20K)) ^ (day.wrapping_mul(PRIME_30K));
    time ^ (time << 16) ^ (time >> 16)
}

/// Today's code, from the system clock.
pub fn rtp_handshake_today() -> u32 {
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let (y, m, d) = civil_from_days((secs / 86_400) as i64);
    rtp_handshake(y as u32, m, d)
}

/// Days since 1970-01-01 → calendar date (Howard Hinnant's civil-date algorithm).
fn civil_from_days(z: i64) -> (i64, u32, u32) {
    let z = z + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = (z - era * 146_097) as u64;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365;
    let y = yoe as i64 + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (if m <= 2 { y + 1 } else { y }, m, d)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hex_round_trips_and_rejects_odd_input() {
        assert_eq!(hex_encode(&[0x00, 0x0f, 0xa5]), "000fa5");
        assert_eq!(hex_decode("000FA5").unwrap(), vec![0x00, 0x0f, 0xa5]);
        // Tolerates noise, refuses a half byte.
        assert_eq!(hex_decode("00 0f\na5").unwrap(), vec![0x00, 0x0f, 0xa5]);
        assert!(hex_decode("abc").is_none());
    }

    #[test]
    fn aes_ecb_round_trips_including_exact_block_multiples() {
        for text in ["", "a", "sixteen bytes!!!", "seventeen bytes!!"] {
            let ct = encrypt(text.as_bytes(), &CRYPTO_KEY);
            assert_eq!(ct.len() % 16, 0);
            // An exact multiple must gain a whole padding block, not zero.
            assert!(ct.len() > text.len());
            assert_eq!(decrypt(&ct, &CRYPTO_KEY).unwrap(), text.as_bytes());
        }
    }

    #[test]
    fn decrypt_refuses_corrupt_padding_rather_than_returning_garbage() {
        let mut ct = encrypt(b"hello", &CRYPTO_KEY);
        let last = ct.len() - 1;
        ct[last] ^= 0xFF;
        assert!(decrypt(&ct, &CRYPTO_KEY).is_none());
        assert!(decrypt(&[1, 2, 3], &CRYPTO_KEY).is_none());
    }

    #[test]
    fn challenge_response_verifies_against_itself() {
        let challenge = "a3f19b7c";
        let response = make_challenge_response(challenge);
        assert!(check_challenge_response(&response, challenge));
        assert!(!check_challenge_response(&response, "different"));
        assert!(!check_challenge_response("not hex at all!", challenge));
    }

    #[test]
    fn msvc_rand_matches_the_known_sequence() {
        // seed 7 -> 7*214013 + 2531011 = 4029102 = 0x3D7B2E; (>>16)&0x7FFF = 0x3D = 61.
        let mut rng = CRandom::default();
        rng.seed(7);
        assert_eq!(rng.rand(), 61);
    }

    #[test]
    fn seed_zero_keeps_the_fixed_key_and_others_do_not() {
        assert_eq!(make_lsx_key(0), CRYPTO_KEY);
        let k = make_lsx_key(1234);
        assert_ne!(k, CRYPTO_KEY);
        // Derivation is pure: the same seed must always give the same key.
        assert_eq!(k, make_lsx_key(1234));
    }

    #[test]
    fn civil_date_conversion_hits_known_days() {
        assert_eq!(civil_from_days(0), (1970, 1, 1));
        assert_eq!(civil_from_days(19_723), (2024, 1, 1)); // leap year boundary
        assert_eq!(civil_from_days(20_704), (2026, 9, 8));
    }

    #[test]
    fn launch_code_changes_day_to_day() {
        let a = rtp_handshake(2026, 9, 8);
        let b = rtp_handshake(2026, 9, 9);
        assert_ne!(a, b, "the code is supposed to roll daily");
        assert_eq!(a, rtp_handshake(2026, 9, 8));
    }
}
