//! LSX message model, and just enough XML to speak it.
//!
//! Every message on this socket is one `<LSX>` element wrapping exactly one of three envelopes,
//! each wrapping exactly one payload:
//!
//! ```text
//! <LSX><Event sender="EALS"><Challenge build=".." key=".." version=".."/></Event></LSX>
//! <LSX><Request recipient=".." id="7"><RequestLicense UserId=".." RequestTicket=".."/></Request></LSX>
//! <LSX><Response sender="EALS" id="7"><RequestLicenseResponse License=".."/></Response></LSX>
//! ```
//!
//! Messages are terminated by a NUL byte, and once the handshake agrees a key the whole XML string
//! is encrypted and hex-encoded before that NUL.
//!
//! # Why a hand-rolled parser
//!
//! A general XML crate would be a new dependency for a grammar this small: elements, attributes,
//! optional text, no namespaces, no comments, no CDATA, no processing instructions, no DTDs. The
//! parser below refuses anything outside that grammar rather than trying to interpret it, which is
//! the right failure for a protocol where a surprise means we have misread the peer — not something
//! to paper over. Everything it does understand is round-tripped by the tests.

use std::fmt::Write as _;

/// One XML element: the whole model, since the grammar nests at most three deep.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct Element {
    pub name: String,
    pub attrs: Vec<(String, String)>,
    pub children: Vec<Element>,
    /// Character data directly inside this element, entity-decoded.
    pub text: String,
}

impl Element {
    pub fn new(name: &str) -> Self {
        Self { name: name.to_string(), ..Default::default() }
    }

    pub fn with_attr(mut self, key: &str, value: impl Into<String>) -> Self {
        self.attrs.push((key.to_string(), value.into()));
        self
    }

    pub fn with_child(mut self, child: Element) -> Self {
        self.children.push(child);
        self
    }

    pub fn attr(&self, key: &str) -> Option<&str> {
        self.attrs
            .iter()
            .find(|(k, _)| k == key)
            .map(|(_, v)| v.as_str())
    }

    /// Case-insensitive attribute lookup.
    ///
    /// The peer is not consistent about case — the handshake uses lowercase `key`/`version` while
    /// the licence request uses `UserId`/`RequestTicket` — so a caller that guesses wrong would
    /// silently read an empty value instead of failing.
    pub fn attr_ci(&self, key: &str) -> Option<&str> {
        self.attrs
            .iter()
            .find(|(k, _)| k.eq_ignore_ascii_case(key))
            .map(|(_, v)| v.as_str())
    }

    pub fn child(&self, name: &str) -> Option<&Element> {
        self.children.iter().find(|c| c.name == name)
    }

    pub fn to_xml(&self) -> String {
        let mut out = String::new();
        self.write_xml(&mut out);
        out
    }

    fn write_xml(&self, out: &mut String) {
        let _ = write!(out, "<{}", self.name);
        for (k, v) in &self.attrs {
            let _ = write!(out, " {}=\"{}\"", k, escape(v));
        }
        if self.children.is_empty() && self.text.is_empty() {
            out.push_str("/>");
            return;
        }
        out.push('>');
        if !self.text.is_empty() {
            out.push_str(&escape(&self.text));
        }
        for child in &self.children {
            child.write_xml(out);
        }
        let _ = write!(out, "</{}>", self.name);
    }
}

fn escape(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for c in text.chars() {
        match c {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            '\'' => out.push_str("&apos;"),
            _ => out.push(c),
        }
    }
    out
}

fn unescape(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut rest = text;
    while let Some(i) = rest.find('&') {
        out.push_str(&rest[..i]);
        rest = &rest[i..];
        let end = match rest.find(';') {
            Some(e) if e <= 10 => e,
            // A bare '&' is not an entity. Keep it rather than losing the tail of the string.
            _ => {
                out.push('&');
                rest = &rest[1..];
                continue;
            }
        };
        match &rest[..=end] {
            "&amp;" => out.push('&'),
            "&lt;" => out.push('<'),
            "&gt;" => out.push('>'),
            "&quot;" => out.push('"'),
            "&apos;" => out.push('\''),
            other => {
                if let Some(num) = other.strip_prefix("&#").and_then(|s| s.strip_suffix(';')) {
                    let cp = if let Some(hex) = num.strip_prefix('x').or_else(|| num.strip_prefix('X')) {
                        u32::from_str_radix(hex, 16).ok()
                    } else {
                        num.parse::<u32>().ok()
                    };
                    match cp.and_then(char::from_u32) {
                        Some(c) => out.push(c),
                        None => out.push_str(other),
                    }
                } else {
                    out.push_str(other);
                }
            }
        }
        rest = &rest[end + 1..];
    }
    out.push_str(rest);
    out
}

/// Parse the first complete `<LSX>...</LSX>` element in `text`.
///
/// Returns the element and the byte offset just past it, so a caller draining a buffer can keep the
/// remainder — the peer may pack several messages into one read.
pub fn parse_lsx(text: &str) -> Option<(Element, usize)> {
    let start = text.find("<LSX>")?;
    let (element, consumed) = parse_element(&text[start..])?;
    if element.name != "LSX" {
        return None;
    }
    Some((element, start + consumed))
}

fn parse_element(s: &str) -> Option<(Element, usize)> {
    let bytes = s.as_bytes();
    if bytes.first() != Some(&b'<') {
        return None;
    }
    let mut i = 1;
    // Reject anything that is not a plain element: comments, PIs, declarations, closing tags.
    if matches!(bytes.get(i), Some(b'!') | Some(b'?') | Some(b'/')) {
        return None;
    }
    let name_start = i;
    while i < bytes.len() && !matches!(bytes[i], b' ' | b'\t' | b'\r' | b'\n' | b'>' | b'/') {
        i += 1;
    }
    let name = s[name_start..i].to_string();
    if name.is_empty() {
        return None;
    }

    let mut element = Element::new(&name);

    loop {
        while i < bytes.len() && bytes[i].is_ascii_whitespace() {
            i += 1;
        }
        match bytes.get(i)? {
            b'/' => {
                // Self-closing: "/>"
                if bytes.get(i + 1) != Some(&b'>') {
                    return None;
                }
                return Some((element, i + 2));
            }
            b'>' => {
                i += 1;
                break;
            }
            _ => {
                let key_start = i;
                while i < bytes.len() && bytes[i] != b'=' && !bytes[i].is_ascii_whitespace() {
                    i += 1;
                }
                let key = s[key_start..i].to_string();
                while i < bytes.len() && bytes[i].is_ascii_whitespace() {
                    i += 1;
                }
                if bytes.get(i) != Some(&b'=') {
                    return None;
                }
                i += 1;
                while i < bytes.len() && bytes[i].is_ascii_whitespace() {
                    i += 1;
                }
                let quote = *bytes.get(i)?;
                if quote != b'"' && quote != b'\'' {
                    return None;
                }
                i += 1;
                let val_start = i;
                while i < bytes.len() && bytes[i] != quote {
                    i += 1;
                }
                if i >= bytes.len() {
                    return None;
                }
                element.attrs.push((key, unescape(&s[val_start..i])));
                i += 1;
            }
        }
    }

    // Content up to the matching close tag.
    let mut text_buf = String::new();
    loop {
        let rest = &s[i..];
        if rest.is_empty() {
            return None;
        }
        if let Some(after) = rest.strip_prefix("</") {
            let end = after.find('>')?;
            if after[..end].trim() != name {
                return None;
            }
            element.text = unescape(text_buf.trim());
            return Some((element, i + 2 + end + 1));
        }
        if rest.starts_with('<') {
            let (child, used) = parse_element(rest)?;
            element.children.push(child);
            i += used;
        } else {
            let next = rest.find('<')?;
            text_buf.push_str(&rest[..next]);
            i += next;
        }
    }
}

/// Split a NUL-terminated frame off the front of a buffer.
///
/// Returns the frame's bytes and how much of the buffer it used, or `None` while the frame is still
/// arriving. Trailing NULs are not part of the payload.
pub fn take_frame(buf: &[u8]) -> Option<(Vec<u8>, usize)> {
    let pos = buf.iter().position(|&b| b == 0)?;
    Some((buf[..pos].to_vec(), pos + 1))
}

/// Wrap a rendered message as a frame: NUL-terminated, encrypted-and-hexed when a key is active.
pub fn frame(xml: &str, key: Option<&[u8; 16]>) -> Vec<u8> {
    let mut out = match key {
        Some(k) => super::crypto::hex_encode(&super::crypto::encrypt(xml.as_bytes(), k)).into_bytes(),
        None => xml.as_bytes().to_vec(),
    };
    out.push(0);
    out
}

/// Recover the XML from a frame, decrypting when a key is active.
pub fn unframe(payload: &[u8], key: Option<&[u8; 16]>) -> Option<String> {
    match key {
        None => String::from_utf8(payload.to_vec()).ok(),
        Some(k) => {
            let raw = super::crypto::hex_decode(std::str::from_utf8(payload).ok()?)?;
            String::from_utf8(super::crypto::decrypt(&raw, k)?).ok()
        }
    }
}

// ── message construction ───────────────────────────────────────────────────────────────────────

pub const CORE_SENDER: &str = "EALS";

pub fn event(payload: Element) -> String {
    Element::new("LSX")
        .with_child(Element::new("Event").with_attr("sender", CORE_SENDER).with_child(payload))
        .to_xml()
}

pub fn response(id: &str, payload: Element) -> String {
    Element::new("LSX")
        .with_child(
            Element::new("Response")
                .with_attr("sender", CORE_SENDER)
                .with_attr("id", id)
                .with_child(payload),
        )
        .to_xml()
}

/// The generic "that worked" reply, used for every request whose answer the game does not act on.
pub fn error_success() -> Element {
    Element::new("ErrorSuccess")
        .with_attr("Code", "0")
        .with_attr("Description", "")
}

/// A parsed inbound message: the envelope kind, its id, and the payload element.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Incoming {
    pub envelope: String,
    pub id: String,
    pub payload: Element,
}

impl Incoming {
    pub fn parse(xml: &str) -> Option<Self> {
        let (lsx, _) = parse_lsx(xml)?;
        let envelope = lsx.children.into_iter().next()?;
        let id = envelope.attr_ci("id").unwrap_or_default().to_string();
        let payload = envelope.children.into_iter().next()?;
        Some(Self { envelope: envelope.name, id, payload })
    }

    pub fn is_request(&self) -> bool {
        self.envelope == "Request"
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_a_real_shaped_request() {
        let xml = r#"<LSX><Request recipient="Game" id="7"><RequestLicense UserId="1234" RequestTicket="abc" TicketEngine="denuvo" version="1"/></Request></LSX>"#;
        let msg = Incoming::parse(xml).unwrap();
        assert!(msg.is_request());
        assert_eq!(msg.id, "7");
        assert_eq!(msg.payload.name, "RequestLicense");
        assert_eq!(msg.payload.attr("RequestTicket"), Some("abc"));
        assert_eq!(msg.payload.attr_ci("userid"), Some("1234"));
    }

    #[test]
    fn parses_child_elements_and_text() {
        let xml = r#"<LSX><Request id="1"><ChallengeResponse response="ff" key="k" version="3"><ContentId>1035208</ContentId><Title>Payback</Title></ChallengeResponse></Request></LSX>"#;
        let msg = Incoming::parse(xml).unwrap();
        assert_eq!(msg.payload.attr("version"), Some("3"));
        assert_eq!(msg.payload.child("ContentId").unwrap().text, "1035208");
        assert_eq!(msg.payload.child("Title").unwrap().text, "Payback");
    }

    #[test]
    fn round_trips_through_xml() {
        let built = response("9", Element::new("RequestLicenseResponse").with_attr("License", ""));
        let msg = Incoming::parse(&built).unwrap();
        assert_eq!(msg.envelope, "Response");
        assert_eq!(msg.id, "9");
        assert_eq!(msg.payload.attr("License"), Some(""));
    }

    #[test]
    fn entities_survive_both_directions() {
        let tricky = r#"a&b<c>d"e'f"#;
        let built = response("1", Element::new("X").with_attr("V", tricky));
        assert!(built.contains("&amp;") && built.contains("&lt;"));
        let msg = Incoming::parse(&built).unwrap();
        assert_eq!(msg.payload.attr("V"), Some(tricky));
        assert_eq!(unescape("&#65;&#x42;"), "AB");
        // A bare ampersand must not swallow the rest of the value.
        assert_eq!(unescape("a & b"), "a & b");
    }

    #[test]
    fn refuses_grammar_it_does_not_actually_support() {
        assert!(Incoming::parse("<LSX><!-- hi --></LSX>").is_none());
        assert!(Incoming::parse("<LSX><Request id='1'><X unclosed=</Request></LSX>").is_none());
        assert!(Incoming::parse("not xml at all").is_none());
        // Mismatched close tag is a misread of the peer, not something to recover from.
        assert!(Incoming::parse("<LSX><Request id=\"1\"><X></Y></Request></LSX>").is_none());
    }

    #[test]
    fn frames_split_on_nul_and_leave_the_remainder() {
        let mut buf = frame("<a/>", None);
        buf.extend(frame("<b/>", None));
        let (first, used) = take_frame(&buf).unwrap();
        assert_eq!(first, b"<a/>");
        let (second, _) = take_frame(&buf[used..]).unwrap();
        assert_eq!(second, b"<b/>");
        // A frame still in flight yields nothing rather than a truncated message.
        assert!(take_frame(b"<partial").is_none());
    }

    #[test]
    fn encrypted_frames_round_trip() {
        let key = super::super::crypto::CRYPTO_KEY;
        let xml = response("3", error_success());
        let framed = frame(&xml, Some(&key));
        assert_eq!(*framed.last().unwrap(), 0);
        let (payload, _) = take_frame(&framed).unwrap();
        // Hex on the wire, not raw XML.
        assert!(!payload.starts_with(b"<"));
        assert_eq!(unframe(&payload, Some(&key)).unwrap(), xml);
    }

    #[test]
    fn several_messages_in_one_read_are_each_found() {
        let joined = format!("{}{}", event(Element::new("Challenge")), response("2", error_success()));
        let (first, used) = parse_lsx(&joined).unwrap();
        assert_eq!(first.children[0].name, "Event");
        let (second, _) = parse_lsx(&joined[used..]).unwrap();
        assert_eq!(second.children[0].name, "Response");
    }
}
