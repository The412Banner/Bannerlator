//! Serve mode: be the launcher the game is looking for.
//!
//! The conversation is short and always starts the same way. We greet the game with a challenge; it
//! answers, names an encryption version and tells us which title it is; we accept, and from the next
//! message on both sides encrypt. Then it asks questions. Only one of them decides whether the game
//! runs — `RequestLicense` — and the rest are answered with a plain success so the SDK stops waiting.
//!
//! # The two licence answers, and why both exist
//!
//! `RequestLicenseResponse` carries a single `License` attribute, and **an empty string is a
//! legitimate answer** — it is what a launcher returns for an offline launch. A non-empty value is a
//! Denuvo token, which only a title with Denuvo actually needs.
//!
//! So there are two ways to satisfy a game, and they cost wildly different amounts:
//!
//! - **Empty.** Costs nothing. No EA account, no network, no licence file. If the title has no
//!   Denuvo, this may be the whole answer.
//! - **Token.** Requires a real EA licence fetched and persisted for that title.
//!
//! [`LicenceStrategy::Auto`] serves a token when one is held and an empty licence otherwise, which
//! means the cheap path is tried by default and the expensive one takes over the moment it can. The
//! explicit variants exist so a launch can be pinned either way while we are still learning which
//! titles need which.
//!
//! # What this never does
//!
//! It never blocks a launch. Every unknown request is answered rather than ignored, a malformed
//! message ends the session instead of hanging it, and any failure leaves the caller free to put EA
//! Desktop back in the path.

use super::crypto;
use super::proto::{self, Element, Incoming};

/// The challenge nonce we open with.
///
/// Carried over from the implementation this was derived from, whose own note says its derivation is
/// unknown — it is a constant they observed working, not something either of us can regenerate. If
/// EA ever changes what it accepts here, this is the line that breaks, which is exactly why the
/// capture relay stays shipped: the transcript will show the refusal instead of leaving us guessing.
const CHALLENGE_NONCE: &str = "cacf897a20b6d612ad0c05e011df52bb";
const CHALLENGE_BUILD: &str = "release";
const CHALLENGE_VERSION: &str = "10,5,30,15625";

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub enum LicenceStrategy {
    /// Serve a held token if we have one, otherwise an empty licence. The default.
    #[default]
    Auto,
    /// Always answer empty, even when a token is held. The cheap path, pinned.
    AlwaysEmpty,
    /// Only answer with a real token; refuse rather than serve empty. Used to prove a title
    /// genuinely needs Denuvo rather than assuming it from a failure.
    RequireToken,
}

/// Where a real licence token comes from, when we have one.
///
/// A trait so the OOA fetch can arrive later without touching the state machine, and so tests can
/// drive both answers without a network.
pub trait LicenceSource: Send + Sync {
    /// Token for `content_id`, or `None` when none is held.
    fn token_for(&mut self, content_id: &str, request_ticket: &str) -> Option<String>;
}

/// A source that never holds a token — the empty-licence path on its own.
pub struct NoTokens;

impl LicenceSource for NoTokens {
    fn token_for(&mut self, _content_id: &str, _request_ticket: &str) -> Option<String> {
        None
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum Crypt {
    /// Handshake still in the clear.
    Off,
    /// Key agreed; arms after the message currently being sent goes out.
    Armed([u8; 16]),
    On([u8; 16]),
}

/// What the caller should do with the bytes a step produced.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum Step {
    /// Write these bytes to the game.
    Send(Vec<u8>),
    /// Nothing to say to this message.
    Nothing,
    /// End the session. The caller falls back.
    Close(String),
}

pub struct Session {
    strategy: LicenceStrategy,
    crypt: Crypt,
    content_id: String,
    title: String,
    /// Set once the game has proved it holds the shared key.
    handshaked: bool,
    licences: Box<dyn LicenceSource>,
    log: Vec<String>,
}

impl Session {
    pub fn new(strategy: LicenceStrategy, licences: Box<dyn LicenceSource>) -> Self {
        Self {
            strategy,
            crypt: Crypt::Off,
            content_id: String::new(),
            title: String::new(),
            handshaked: false,
            licences,
            log: Vec::new(),
        }
    }

    /// The greeting. Sent as soon as the game connects, before it says anything.
    pub fn greeting(&self) -> Vec<u8> {
        let challenge = Element::new("Challenge")
            .with_attr("build", CHALLENGE_BUILD)
            .with_attr("key", CHALLENGE_NONCE)
            .with_attr("version", CHALLENGE_VERSION);
        proto::frame(&proto::event(challenge), None)
    }

    /// Which title the game identified itself as, once the handshake is done.
    pub fn content_id(&self) -> &str {
        &self.content_id
    }

    pub fn title(&self) -> &str {
        &self.title
    }

    /// Whether the game completed the challenge. False after a session that closed early, which is
    /// the difference between "the game never talked to us" and "it talked and we failed it".
    pub fn handshaked(&self) -> bool {
        self.handshaked
    }

    /// A human-readable trace of what was asked and answered, for the launch log.
    pub fn log(&self) -> &[String] {
        &self.log
    }

    /// Feed one complete frame's payload; get back what to send.
    pub fn handle_frame(&mut self, payload: &[u8]) -> Step {
        let key = match self.crypt {
            Crypt::On(k) => Some(k),
            _ => None,
        };
        let xml = match proto::unframe(payload, key.as_ref()) {
            Some(xml) => xml,
            None => return Step::Close("could not decode frame".into()),
        };
        let msg = match Incoming::parse(&xml) {
            Some(msg) => msg,
            None => return Step::Close(format!("unparsable message: {}", truncate(&xml))),
        };
        if !msg.is_request() {
            // Events are informational; the SDK does not wait on a reply.
            return Step::Nothing;
        }
        self.log.push(format!("<- {}", msg.payload.name));

        let reply = match msg.payload.name.as_str() {
            "ChallengeResponse" => match self.accept_challenge(&msg.payload) {
                Ok(el) => el,
                Err(why) => return Step::Close(why),
            },
            "RequestLicense" => self.answer_licence(&msg.payload),
            // Everything else: acknowledge so the SDK proceeds. These are config, presence, friends,
            // overlay and progressive-install questions whose answers a launch does not depend on.
            _ => proto::error_success(),
        };

        self.log.push(format!("-> {}", reply.name));
        let out = proto::frame(&proto::response(&msg.id, reply), key.as_ref());

        // The reply that agreed the key still goes out in the clear; everything after is encrypted.
        if let Crypt::Armed(k) = self.crypt {
            self.crypt = Crypt::On(k);
        }
        Step::Send(out)
    }

    fn accept_challenge(&mut self, payload: &Element) -> Result<Element, String> {
        let response = payload.attr_ci("response").unwrap_or_default();
        if !crypto::check_challenge_response(response, CHALLENGE_NONCE) {
            return Err("challenge response did not verify".into());
        }

        let their_key = payload.attr_ci("key").unwrap_or_default();
        let accept = crypto::make_challenge_response(their_key);

        // The seed is taken from the ASCII of the hex string, not the bytes it encodes.
        let seed = match payload.attr_ci("version").unwrap_or_default() {
            "2" => 0u16,
            "3" => {
                let b = accept.as_bytes();
                if b.len() < 2 {
                    return Err("accept key too short to seed encryption".into());
                }
                ((b[0] as u16) << 8) | (b[1] as u16)
            }
            other => return Err(format!("unknown encryption version {other:?}")),
        };

        self.crypt = Crypt::Armed(crypto::make_lsx_key(seed));
        self.handshaked = true;
        self.content_id = payload.child("ContentId").map(|e| e.text.clone()).unwrap_or_default();
        self.title = payload.child("Title").map(|e| e.text.clone()).unwrap_or_default();
        self.log.push(format!(
            "handshake ok: title={:?} contentId={:?} encryption=v{}",
            self.title,
            self.content_id,
            payload.attr_ci("version").unwrap_or_default()
        ));

        Ok(Element::new("ChallengeAccepted").with_attr("response", accept))
    }

    fn answer_licence(&mut self, payload: &Element) -> Element {
        let ticket = payload.attr_ci("RequestTicket").unwrap_or_default().to_string();
        let content_id = if self.content_id.is_empty() {
            payload.attr_ci("ContentId").unwrap_or_default().to_string()
        } else {
            self.content_id.clone()
        };

        let token = match self.strategy {
            LicenceStrategy::AlwaysEmpty => None,
            LicenceStrategy::Auto | LicenceStrategy::RequireToken => {
                self.licences.token_for(&content_id, &ticket)
            }
        };

        match (token, self.strategy) {
            (Some(token), _) => {
                self.log.push("licence: served held token".into());
                Element::new("RequestLicenseResponse").with_attr("License", token)
            }
            (None, LicenceStrategy::RequireToken) => {
                // Deliberately still a well-formed answer. Refusing to reply would hang the launch;
                // an empty licence under RequireToken would hide the fact that we had no token.
                self.log.push("licence: no token held and token required — answering empty, launch may refuse".into());
                Element::new("RequestLicenseResponse").with_attr("License", "")
            }
            (None, _) => {
                self.log.push("licence: served empty (no Denuvo token needed, or none held)".into());
                Element::new("RequestLicenseResponse").with_attr("License", "")
            }
        }
    }
}

fn truncate(s: &str) -> String {
    if s.len() <= 120 {
        return s.to_string();
    }
    // Slice on a character boundary. This runs on a malformed message from the peer, which is
    // exactly where a stray multi-byte sequence is likely, and `&s[..120]` would panic on one —
    // turning a diagnostic into a crash inside a game launch.
    let cut = (0..=120).rev().find(|&i| s.is_char_boundary(i)).unwrap_or(0);
    format!("{}…", &s[..cut])
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Drive a session the way a game does, returning the reply payload element.
    fn exchange(session: &mut Session, xml: &str, key: Option<[u8; 16]>) -> Element {
        let framed = proto::frame(xml, key.as_ref());
        let (payload, _) = proto::take_frame(&framed).unwrap();
        match session.handle_frame(&payload) {
            Step::Send(bytes) => {
                let (out, _) = proto::take_frame(&bytes).unwrap();
                // A reply is framed with whatever key was live when the request arrived, which is
                // the same key the caller used to send it — the handshake pair is clear, the rest
                // encrypted. So the request's key is always the right one to read the reply with.
                let xml = proto::unframe(&out, key.as_ref()).unwrap();
                Incoming::parse(&xml).unwrap().payload
            }
            other => panic!("expected a reply, got {other:?}"),
        }
    }

    fn challenge_response(version: &str) -> String {
        let mut el = Element::new("ChallengeResponse")
            .with_attr("response", crypto::make_challenge_response(CHALLENGE_NONCE))
            .with_attr("key", "somekey")
            .with_attr("version", version);
        let mut cid = Element::new("ContentId");
        cid.text = "1035208".into();
        let mut title = Element::new("Title");
        title.text = "Need for Speed Payback".into();
        el = el.with_child(cid).with_child(title);
        format!(
            "<LSX><Request recipient=\"Game\" id=\"1\">{}</Request></LSX>",
            el.to_xml()
        )
    }

    #[test]
    fn greeting_is_a_plaintext_challenge() {
        let s = Session::new(LicenceStrategy::Auto, Box::new(NoTokens));
        let (payload, _) = proto::take_frame(&s.greeting()).unwrap();
        let xml = String::from_utf8(payload).unwrap();
        assert!(xml.contains("<Challenge"));
        assert!(xml.contains(CHALLENGE_NONCE));
        assert!(xml.contains(&format!("sender=\"{}\"", proto::CORE_SENDER)));
    }

    #[test]
    fn handshake_v2_keeps_the_fixed_key_and_learns_the_title() {
        let mut s = Session::new(LicenceStrategy::Auto, Box::new(NoTokens));
        let reply = exchange(&mut s, &challenge_response("2"), None);
        assert_eq!(reply.name, "ChallengeAccepted");
        assert_eq!(s.content_id(), "1035208");
        assert_eq!(s.title(), "Need for Speed Payback");
        assert_eq!(s.crypt, Crypt::On(crypto::CRYPTO_KEY));
    }

    #[test]
    fn handshake_v3_derives_a_different_key() {
        let mut s = Session::new(LicenceStrategy::Auto, Box::new(NoTokens));
        exchange(&mut s, &challenge_response("3"), None);
        assert!(matches!(s.crypt, Crypt::On(k) if k != crypto::CRYPTO_KEY));
    }

    #[test]
    fn a_wrong_challenge_answer_closes_rather_than_serving_anyone() {
        let mut s = Session::new(LicenceStrategy::Auto, Box::new(NoTokens));
        let xml = "<LSX><Request id=\"1\"><ChallengeResponse response=\"deadbeef\" key=\"k\" version=\"2\"/></Request></LSX>";
        let framed = proto::frame(xml, None);
        let (payload, _) = proto::take_frame(&framed).unwrap();
        assert!(matches!(s.handle_frame(&payload), Step::Close(_)));
    }

    #[test]
    fn unknown_encryption_version_closes_instead_of_guessing() {
        let mut s = Session::new(LicenceStrategy::Auto, Box::new(NoTokens));
        let framed = proto::frame(&challenge_response("9"), None);
        let (payload, _) = proto::take_frame(&framed).unwrap();
        match s.handle_frame(&payload) {
            Step::Close(why) => assert!(why.contains("unknown encryption")),
            other => panic!("expected close, got {other:?}"),
        }
    }

    #[test]
    fn licence_is_empty_when_no_token_is_held() {
        let mut s = Session::new(LicenceStrategy::Auto, Box::new(NoTokens));
        exchange(&mut s, &challenge_response("2"), None);
        let key = crypto::CRYPTO_KEY;
        let reply = exchange(
            &mut s,
            "<LSX><Request id=\"2\"><RequestLicense UserId=\"1\" RequestTicket=\"t\" TicketEngine=\"e\"/></Request></LSX>",
            Some(key),
        );
        assert_eq!(reply.name, "RequestLicenseResponse");
        assert_eq!(reply.attr("License"), Some(""));
    }

    struct OneToken(&'static str);
    impl LicenceSource for OneToken {
        fn token_for(&mut self, content_id: &str, _ticket: &str) -> Option<String> {
            assert_eq!(content_id, "1035208", "the held licence is looked up by title");
            Some(self.0.to_string())
        }
    }

    #[test]
    fn a_held_token_is_served_and_always_empty_overrides_it() {
        for (strategy, expect) in [
            (LicenceStrategy::Auto, "DENUVO-TOKEN"),
            (LicenceStrategy::AlwaysEmpty, ""),
        ] {
            let mut s = Session::new(strategy, Box::new(OneToken("DENUVO-TOKEN")));
            exchange(&mut s, &challenge_response("2"), None);
            let reply = exchange(
                &mut s,
                "<LSX><Request id=\"2\"><RequestLicense UserId=\"1\" RequestTicket=\"t\"/></Request></LSX>",
                Some(crypto::CRYPTO_KEY),
            );
            assert_eq!(reply.attr("License"), Some(expect), "strategy {strategy:?}");
        }
    }

    #[test]
    fn every_other_question_gets_a_success_so_the_sdk_moves_on() {
        let mut s = Session::new(LicenceStrategy::Auto, Box::new(NoTokens));
        exchange(&mut s, &challenge_response("2"), None);
        for req in ["GetConfig", "QueryEntitlements", "GetPresence", "ShowIGOWindow", "GetVoipStatus"] {
            let reply = exchange(
                &mut s,
                &format!("<LSX><Request id=\"5\"><{req} version=\"1\"/></Request></LSX>"),
                Some(crypto::CRYPTO_KEY),
            );
            assert_eq!(reply.name, "ErrorSuccess", "{req} must be answered, not ignored");
            assert_eq!(reply.attr("Code"), Some("0"));
        }
    }

    #[test]
    fn truncate_never_panics_on_multibyte_input() {
        let s = "é".repeat(200);
        assert!(truncate(&s).ends_with('…'));
        assert_eq!(truncate("short"), "short");
    }

    #[test]
    fn the_transcript_records_what_was_asked_and_answered() {
        let mut s = Session::new(LicenceStrategy::Auto, Box::new(NoTokens));
        exchange(&mut s, &challenge_response("2"), None);
        exchange(
            &mut s,
            "<LSX><Request id=\"2\"><RequestLicense UserId=\"1\" RequestTicket=\"t\"/></Request></LSX>",
            Some(crypto::CRYPTO_KEY),
        );
        let log = s.log().join("\n");
        assert!(log.contains("handshake ok"));
        assert!(log.contains("Need for Speed Payback"));
        assert!(log.contains("<- RequestLicense"));
        assert!(log.contains("served empty"));
    }
}
