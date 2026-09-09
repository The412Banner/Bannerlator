//! LSX listener — the localhost socket the game's embedded Origin SDK talks to.
//!
//! A game published by EA does not ask EA's servers whether it may run. It asks *its launcher*,
//! over a plain TCP socket on loopback whose port arrives in the `EALsxPort` environment variable.
//! On device we have already watched our own titles do exactly that: `Origin::SDK::Lsx` connects,
//! announces `OriginSDK Version 10.6.1.1`, sends `RequestLicense`, and waits for a
//! `RequestLicenseResponse`. EA Desktop is merely the process that currently answers.
//!
//! That the guest can reach an app-side listener at all is not a new trick: [`crate::wine_bridge`]
//! already binds `127.0.0.1` and the in-container Steam PE dials out to it. This is the same move
//! on a port we hand the game ourselves.
//!
//! # Two modes, and why capture exists
//!
//! [`Mode::Capture`] is a record-and-forward relay: we bind, the game connects to us, and we open a
//! second connection to the real EA Desktop and pump bytes both ways, copying everything to a
//! transcript. The game cannot tell the difference, EA Desktop cannot tell the difference, and we
//! learn the wire format *as our own titles speak it* rather than as some other implementation
//! documents it. It answers the question the whole feature rests on: when a licence check fails, is
//! the failure even in this conversation?
//!
//! [`Mode::Serve`] is the destination: answer `RequestLicense` from a licence we hold, and EA
//! Desktop never starts. Serve is deliberately NOT implemented from a guess at the format — it is
//! written against a transcript captured from a real launch.
//!
//! Capture mode is protocol-agnostic on purpose. It parses nothing, so it cannot be wrong about a
//! format nobody here has verified yet; it moves bytes and writes down what it saw.

use super::serve::{LicenceSource, LicenceStrategy, Session, Step};
use std::io::{Read, Write};
use std::net::{Shutdown, TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::Duration;

/// Which side of the conversation a transcript chunk came from.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Direction {
    /// Game → launcher. The requests we will eventually have to answer ourselves.
    FromGame,
    /// Launcher → game. In capture mode, EA Desktop's real answers.
    ToGame,
    /// Not traffic — a lifecycle note (a connection arriving, a session ending).
    ///
    /// Worth its own variant because the single most valuable thing a transcript can say is
    /// "the game connected" or, far more usefully when a launch misbehaves, that it never did.
    Note,
}

impl Direction {
    pub fn as_str(self) -> &'static str {
        match self {
            Direction::FromGame => "game->lsx",
            Direction::ToGame => "lsx->game",
            Direction::Note => "note",
        }
    }
}

/// Observer for captured bytes: `(connection id, direction, bytes)`.
type Transcript = Arc<dyn Fn(u64, Direction, Vec<u8>) + Send + Sync + 'static>;

/// Builds a fresh licence source per connection.
///
/// A factory rather than a shared object because each game connection gets its own [`Session`], and
/// because it keeps [`LsxConfig`] a plain value that can stay `Clone`/`Debug`/`PartialEq`.
type LicenceFactory = Arc<dyn Fn() -> Box<dyn LicenceSource> + Send + Sync + 'static>;

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum Mode {
    /// Relay to the real EA Desktop on `upstream_port`, copying both directions to the transcript.
    Capture { upstream_port: u16 },
    /// Answer the game ourselves — no EA Desktop in the launch at all.
    Serve { strategy: LicenceStrategy },
}

#[derive(Clone, Debug)]
pub struct LsxConfig {
    pub bind_host: String,
    /// Port to bind. `0` asks the OS for a free one — the normal case, because the port only has to
    /// agree with the `EALsxPort` we hand the game, and a fixed port would collide with EA Desktop.
    pub port: u16,
    pub mode: Mode,
    /// Cap on a single transcript chunk, so a large licence blob cannot balloon the log.
    pub chunk_limit: usize,
}

impl Default for LsxConfig {
    fn default() -> Self {
        Self {
            bind_host: "127.0.0.1".to_string(),
            port: 0,
            mode: Mode::Capture {
                upstream_port: 0,
            },
            chunk_limit: 64 * 1024,
        }
    }
}

#[derive(Default)]
pub struct LsxServer {
    running: Arc<AtomicBool>,
    port: Arc<Mutex<u16>>,
    last_error: Arc<Mutex<String>>,
    transcript: Arc<Mutex<Option<Transcript>>>,
    licences: Arc<Mutex<Option<LicenceFactory>>>,
    conn_seq: Arc<Mutex<u64>>,
    thread: Option<JoinHandle<()>>,
}

impl LsxServer {
    /// Bind and start accepting. Returns false and sets [`Self::last_error`] on failure; the caller
    /// must then leave `EALsxPort` unset so the game falls back to real EA Desktop.
    pub fn start(&mut self, config: LsxConfig) -> bool {
        if self.running.load(Ordering::Relaxed) {
            return true;
        }
        self.stop();

        let listener = match TcpListener::bind((config.bind_host.as_str(), config.port)) {
            Ok(listener) => listener,
            Err(err) => {
                self.set_error(format!("bind({}:{}): {err}", config.bind_host, config.port));
                return false;
            }
        };
        let bound = match listener.local_addr() {
            Ok(addr) => addr.port(),
            Err(err) => {
                self.set_error(format!("local_addr: {err}"));
                return false;
            }
        };
        *self.port.lock().expect("lsx poisoned") = bound;

        self.running.store(true, Ordering::Relaxed);
        self.thread = Some(spawn_accept_loop(
            listener,
            config,
            Arc::clone(&self.running),
            Arc::clone(&self.transcript),
            Arc::clone(&self.licences),
            Arc::clone(&self.conn_seq),
            Arc::clone(&self.last_error),
        ));
        true
    }

    pub fn stop(&mut self) {
        self.running.store(false, Ordering::Relaxed);
        if let Some(handle) = self.thread.take() {
            let _ = handle.join();
        }
        *self.port.lock().expect("lsx poisoned") = 0;
    }

    pub fn running(&self) -> bool {
        self.running.load(Ordering::Relaxed)
    }

    /// The bound port, or 0 when stopped. This is the value that goes into `EALsxPort`.
    pub fn port(&self) -> u16 {
        *self.port.lock().expect("lsx poisoned")
    }

    /// How many guest connections have been accepted.
    ///
    /// Zero after a launch is the loudest diagnosis available: the game never spoke to us, so
    /// nothing about our licence answer is implicated and the fault is upstream — most likely that
    /// the game was handed a different port than the one we published.
    pub fn connections(&self) -> u64 {
        *self.conn_seq.lock().expect("lsx poisoned")
    }

    pub fn last_error(&self) -> String {
        self.last_error.lock().expect("lsx poisoned").clone()
    }

    pub fn set_transcript<F>(&self, observer: F)
    where
        F: Fn(u64, Direction, Vec<u8>) + Send + Sync + 'static,
    {
        *self.transcript.lock().expect("lsx poisoned") = Some(Arc::new(observer));
    }

    /// Supply held licences. Without this, serve mode answers every title with an empty licence —
    /// which is the cheap path, and correct for any title that does not need a Denuvo token.
    pub fn set_licences<F>(&self, factory: F)
    where
        F: Fn() -> Box<dyn LicenceSource> + Send + Sync + 'static,
    {
        *self.licences.lock().expect("lsx poisoned") = Some(Arc::new(factory));
    }

    fn set_error(&self, error: String) {
        *self.last_error.lock().expect("lsx poisoned") = error;
    }
}

impl Drop for LsxServer {
    fn drop(&mut self) {
        self.stop();
    }
}

fn spawn_accept_loop(
    listener: TcpListener,
    config: LsxConfig,
    running: Arc<AtomicBool>,
    transcript: Arc<Mutex<Option<Transcript>>>,
    licences: Arc<Mutex<Option<LicenceFactory>>>,
    conn_seq: Arc<Mutex<u64>>,
    last_error: Arc<Mutex<String>>,
) -> JoinHandle<()> {
    thread::spawn(move || {
        let _ = listener.set_nonblocking(true);
        let mut conns: Vec<JoinHandle<()>> = Vec::new();
        while running.load(Ordering::Relaxed) {
            match listener.accept() {
                Ok((stream, _)) => {
                    let id = {
                        let mut seq = conn_seq.lock().expect("lsx poisoned");
                        *seq += 1;
                        *seq
                    };
                    let cb = transcript.lock().expect("lsx poisoned").clone();
                    if let Some(cb) = cb {
                        cb(id, Direction::Note, b"connection accepted from the guest".to_vec());
                    }
                    conns.push(spawn_connection(
                        stream,
                        id,
                        config.clone(),
                        Arc::clone(&running),
                        Arc::clone(&transcript),
                        Arc::clone(&licences),
                        Arc::clone(&last_error),
                    ));
                }
                Err(err) if err.kind() == std::io::ErrorKind::WouldBlock => {
                    thread::sleep(Duration::from_millis(20));
                }
                Err(_) => break,
            }
        }
        for handle in conns {
            let _ = handle.join();
        }
    })
}

fn spawn_connection(
    game: TcpStream,
    id: u64,
    config: LsxConfig,
    running: Arc<AtomicBool>,
    transcript: Arc<Mutex<Option<Transcript>>>,
    licences: Arc<Mutex<Option<LicenceFactory>>>,
    last_error: Arc<Mutex<String>>,
) -> JoinHandle<()> {
    thread::spawn(move || match config.mode {
        Mode::Capture { upstream_port } => {
            let upstream = TcpStream::connect((config.bind_host.as_str(), upstream_port));
            match upstream {
                Ok(upstream) => relay(game, upstream, id, config.chunk_limit, running, transcript),
                Err(err) => {
                    // Upstream refused: EA Desktop is not listening where we were told. Record it and
                    // drop the game's connection rather than hanging it — the launch then fails the
                    // way it would have without us in the path, instead of stalling forever.
                    *last_error.lock().expect("lsx poisoned") =
                        format!("upstream connect({upstream_port}): {err}");
                    let _ = game.shutdown(Shutdown::Both);
                }
            }
        }
        Mode::Serve { strategy } => {
            let source = match licences.lock().expect("lsx poisoned").clone() {
                Some(factory) => factory(),
                None => Box::new(super::serve::NoTokens) as Box<dyn LicenceSource>,
            };
            serve(
                game,
                Session::new(strategy, source),
                id,
                config.chunk_limit,
                running,
                transcript,
                last_error,
            );
        }
    })
}

/// Drive a [`Session`] over one game connection.
///
/// Unlike the capture relay this is half-duplex by nature: the SDK asks, we answer. The greeting
/// goes out first, unprompted, because the game waits to be challenged before it says anything.
///
/// Every exit path closes the socket. A launch that is waiting on a launcher which has silently
/// stopped answering is the one failure this feature must never cause — better a clean refusal the
/// caller can fall back from.
fn serve(
    mut game: TcpStream,
    mut session: Session,
    id: u64,
    chunk_limit: usize,
    running: Arc<AtomicBool>,
    transcript: Arc<Mutex<Option<Transcript>>>,
    last_error: Arc<Mutex<String>>,
) {
    let note = |dir: Direction, bytes: &[u8]| {
        let cb = transcript.lock().expect("lsx poisoned").clone();
        if let Some(cb) = cb {
            cb(id, dir, bytes[..bytes.len().min(chunk_limit)].to_vec());
        }
    };

    let greeting = session.greeting();
    if game.write_all(&greeting).is_err() {
        return;
    }
    let _ = game.flush();
    note(Direction::ToGame, &greeting);

    let _ = game.set_read_timeout(Some(Duration::from_millis(250)));
    let mut pending: Vec<u8> = Vec::new();
    let mut buf = vec![0u8; 16 * 1024];

    while running.load(Ordering::Relaxed) {
        match game.read(&mut buf) {
            Ok(0) => break,
            Ok(n) => pending.extend_from_slice(&buf[..n]),
            Err(err) => match err.kind() {
                std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut => continue,
                _ => break,
            },
        }

        // One read can carry several messages, or half of one. Drain whole frames only.
        while let Some((payload, used)) = super::proto::take_frame(&pending) {
            pending.drain(..used);
            note(Direction::FromGame, &payload);
            match session.handle_frame(&payload) {
                Step::Send(bytes) => {
                    note(Direction::ToGame, &bytes);
                    if game.write_all(&bytes).is_err() {
                        let _ = game.shutdown(Shutdown::Both);
                        return;
                    }
                    let _ = game.flush();
                }
                Step::Nothing => {}
                Step::Close(why) => {
                    *last_error.lock().expect("lsx poisoned") = why;
                    let _ = game.shutdown(Shutdown::Both);
                    return;
                }
            }
        }
    }

    // Record how far the conversation got: "never handshook" and "handshook then went quiet" are
    // very different diagnoses when a launch fails.
    let summary = format!(
        "session ended: handshaked={} title={:?} contentId={:?}\n{}",
        session.handshaked(),
        session.title(),
        session.content_id(),
        session.log().join("\n")
    );
    *last_error.lock().expect("lsx poisoned") = summary;
    let _ = game.shutdown(Shutdown::Both);
}

/// Full-duplex byte pump between the game and real EA Desktop, copying both directions out.
///
/// One thread per direction: a licence exchange is request/response, but the SDK keeps the socket
/// open and both sides may speak unprompted, so a half-duplex loop would deadlock the launch.
fn relay(
    game: TcpStream,
    upstream: TcpStream,
    id: u64,
    chunk_limit: usize,
    running: Arc<AtomicBool>,
    transcript: Arc<Mutex<Option<Transcript>>>,
) {
    let (game_rx, game_tx) = match (game.try_clone(), game) {
        (Ok(a), b) => (a, b),
        _ => return,
    };
    let (up_rx, up_tx) = match (upstream.try_clone(), upstream) {
        (Ok(a), b) => (a, b),
        _ => return,
    };

    let to_upstream = pump(
        game_rx,
        up_tx,
        id,
        Direction::FromGame,
        chunk_limit,
        Arc::clone(&running),
        Arc::clone(&transcript),
    );
    let to_game = pump(
        up_rx,
        game_tx,
        id,
        Direction::ToGame,
        chunk_limit,
        running,
        transcript,
    );

    let _ = to_upstream.join();
    let _ = to_game.join();
}

fn pump(
    mut from: TcpStream,
    mut to: TcpStream,
    id: u64,
    dir: Direction,
    chunk_limit: usize,
    running: Arc<AtomicBool>,
    transcript: Arc<Mutex<Option<Transcript>>>,
) -> JoinHandle<()> {
    thread::spawn(move || {
        // A read timeout keeps a quiet socket from pinning the thread past stop(); the launch can
        // sit idle between the SDK's handshake and its licence request.
        let _ = from.set_read_timeout(Some(Duration::from_millis(250)));
        let mut buf = vec![0u8; 16 * 1024];
        while running.load(Ordering::Relaxed) {
            match from.read(&mut buf) {
                Ok(0) => break,
                Ok(n) => {
                    if to.write_all(&buf[..n]).is_err() {
                        break;
                    }
                    let _ = to.flush();
                    let cb = transcript.lock().expect("lsx poisoned").clone();
                    if let Some(cb) = cb {
                        let take = n.min(chunk_limit);
                        cb(id, dir, buf[..take].to_vec());
                    }
                }
                Err(err) => match err.kind() {
                    std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut => continue,
                    _ => break,
                },
            }
        }
        let _ = to.shutdown(Shutdown::Both);
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::mpsc;

    fn free_port() -> u16 {
        TcpListener::bind(("127.0.0.1", 0))
            .unwrap()
            .local_addr()
            .unwrap()
            .port()
    }

    #[test]
    fn ephemeral_port_is_reported_for_ealsxport() {
        let mut server = LsxServer::default();
        assert!(server.start(LsxConfig {
            mode: Mode::Capture { upstream_port: free_port() },
            ..Default::default()
        }));
        assert_ne!(server.port(), 0, "port must be knowable to set EALsxPort");
        server.stop();
        assert_eq!(server.port(), 0);
    }

    #[test]
    fn capture_relays_both_directions_and_transcribes() {
        // Stand in for EA Desktop: echo back a fixed response.
        let upstream = TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let upstream_port = upstream.local_addr().unwrap().port();
        thread::spawn(move || {
            let (mut s, _) = upstream.accept().unwrap();
            let mut buf = [0u8; 64];
            let n = s.read(&mut buf).unwrap();
            assert_eq!(&buf[..n], b"<RequestLicense/>");
            s.write_all(b"<RequestLicenseResponse/>").unwrap();
            s.flush().unwrap();
        });

        let mut server = LsxServer::default();
        let (tx, rx) = mpsc::channel();
        // Lifecycle notes (the accept announcement) are not traffic — drop them here so this test
        // asserts on the bytes that actually crossed the wire.
        server.set_transcript(move |id, dir, bytes| {
            if dir != Direction::Note {
                tx.send((id, dir, bytes)).unwrap();
            }
        });
        assert!(server.start(LsxConfig {
            mode: Mode::Capture { upstream_port },
            ..Default::default()
        }));

        let mut game = TcpStream::connect(("127.0.0.1", server.port())).unwrap();
        game.write_all(b"<RequestLicense/>").unwrap();
        game.flush().unwrap();

        // Collect both, then match by direction rather than by arrival order. The two directions
        // are pumped by separate threads, and each forwards its bytes BEFORE recording them — so
        // the upstream reply can reach the transcript ahead of the request that caused it. That
        // ordering is genuinely not guaranteed, and a test that assumed it was is the reason this
        // one failed in CI while the relay itself was behaving correctly.
        let mut seen: Vec<(Direction, Vec<u8>)> = Vec::new();
        for _ in 0..2 {
            let (_, dir, bytes) = rx.recv_timeout(Duration::from_secs(3)).unwrap();
            seen.push((dir, bytes));
        }
        let from_game = seen.iter().find(|(d, _)| *d == Direction::FromGame);
        let to_game = seen.iter().find(|(d, _)| *d == Direction::ToGame);
        assert_eq!(
            from_game.map(|(_, b)| b.as_slice()),
            Some(&b"<RequestLicense/>"[..]),
            "transcript: {seen:?}"
        );
        assert_eq!(
            to_game.map(|(_, b)| b.as_slice()),
            Some(&b"<RequestLicenseResponse/>"[..]),
            "transcript: {seen:?}"
        );

        // And the game really received it, not just the transcript.
        let mut back = [0u8; 64];
        let n = game.read(&mut back).unwrap();
        assert_eq!(&back[..n], b"<RequestLicenseResponse/>");

        server.stop();
    }

    #[test]
    fn connections_counts_arrivals_and_starts_at_zero() {
        // Zero is the diagnosis this counter exists to deliver: it is how "the game never spoke to
        // us" gets told apart from "we answered it badly".
        let mut server = LsxServer::default();
        assert!(server.start(LsxConfig {
            mode: Mode::Serve { strategy: LicenceStrategy::Auto },
            ..Default::default()
        }));
        assert_eq!(server.connections(), 0);
        let _game = TcpStream::connect(("127.0.0.1", server.port())).unwrap();
        let deadline = std::time::Instant::now() + Duration::from_secs(3);
        while server.connections() == 0 && std::time::Instant::now() < deadline {
            thread::sleep(Duration::from_millis(20));
        }
        assert_eq!(server.connections(), 1);
        server.stop();
    }

    #[test]
    fn refused_upstream_drops_the_game_rather_than_hanging_it() {
        let dead = free_port();
        let mut server = LsxServer::default();
        assert!(server.start(LsxConfig {
            mode: Mode::Capture { upstream_port: dead },
            ..Default::default()
        }));
        let mut game = TcpStream::connect(("127.0.0.1", server.port())).unwrap();
        let mut buf = [0u8; 8];
        // Closed, not stalled: a hung launch is the failure mode we must never introduce.
        assert_eq!(game.read(&mut buf).unwrap(), 0);
        assert!(server.last_error().contains("upstream connect"));
        server.stop();
    }

    #[test]
    fn serve_mode_greets_then_answers_a_licence_request() {
        use super::super::crypto;
        use super::super::proto::{self, Incoming};

        let mut server = LsxServer::default();
        assert!(server.start(LsxConfig {
            mode: Mode::Serve { strategy: LicenceStrategy::Auto },
            ..Default::default()
        }));

        let mut game = TcpStream::connect(("127.0.0.1", server.port())).unwrap();
        game.set_read_timeout(Some(Duration::from_secs(3))).unwrap();

        // The launcher speaks first, unprompted.
        let mut buf = [0u8; 4096];
        let n = game.read(&mut buf).unwrap();
        let (payload, _) = proto::take_frame(&buf[..n]).unwrap();
        let greeting = String::from_utf8(payload).unwrap();
        assert!(greeting.contains("<Challenge"), "got {greeting}");
        let nonce = Incoming::parse(&greeting)
            .unwrap()
            .payload
            .attr("key")
            .unwrap()
            .to_string();

        // Answer the challenge the way the SDK would.
        let cr = format!(
            "<LSX><Request recipient=\"Game\" id=\"1\"><ChallengeResponse response=\"{}\" key=\"k\" version=\"2\"><ContentId>1035208</ContentId><Title>Payback</Title></ChallengeResponse></Request></LSX>",
            crypto::make_challenge_response(&nonce)
        );
        game.write_all(&proto::frame(&cr, None)).unwrap();
        let n = game.read(&mut buf).unwrap();
        let (payload, _) = proto::take_frame(&buf[..n]).unwrap();
        let accepted = String::from_utf8(payload).unwrap();
        assert!(accepted.contains("ChallengeAccepted"), "got {accepted}");

        // From here the conversation is encrypted under the fixed key (version 2 -> seed 0).
        let key = crypto::CRYPTO_KEY;
        let req = "<LSX><Request id=\"2\"><RequestLicense UserId=\"1\" RequestTicket=\"t\"/></Request></LSX>";
        game.write_all(&proto::frame(req, Some(&key))).unwrap();
        let n = game.read(&mut buf).unwrap();
        let (payload, _) = proto::take_frame(&buf[..n]).unwrap();
        let xml = proto::unframe(&payload, Some(&key)).unwrap();
        let reply = Incoming::parse(&xml).unwrap().payload;
        assert_eq!(reply.name, "RequestLicenseResponse");
        assert_eq!(reply.attr("License"), Some(""), "cheap path: empty licence");

        server.stop();
    }
}
