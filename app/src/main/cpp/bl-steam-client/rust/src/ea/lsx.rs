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
}

impl Direction {
    pub fn as_str(self) -> &'static str {
        match self {
            Direction::FromGame => "game->lsx",
            Direction::ToGame => "lsx->game",
        }
    }
}

/// Observer for captured bytes: `(connection id, direction, bytes)`.
type Transcript = Arc<dyn Fn(u64, Direction, Vec<u8>) + Send + Sync + 'static>;

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum Mode {
    /// Relay to the real EA Desktop on `upstream_port`, copying both directions to the transcript.
    Capture { upstream_port: u16 },
    /// Answer the game ourselves. Not reachable until a captured transcript defines the format.
    Serve,
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

    pub fn last_error(&self) -> String {
        self.last_error.lock().expect("lsx poisoned").clone()
    }

    pub fn set_transcript<F>(&self, observer: F)
    where
        F: Fn(u64, Direction, Vec<u8>) + Send + Sync + 'static,
    {
        *self.transcript.lock().expect("lsx poisoned") = Some(Arc::new(observer));
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
                    conns.push(spawn_connection(
                        stream,
                        id,
                        config.clone(),
                        Arc::clone(&running),
                        Arc::clone(&transcript),
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
        Mode::Serve => {
            // Deliberately unimplemented. Serving a licence means answering RequestLicense in a
            // format we have not yet observed; guessing it would produce a launch failure that
            // looks like a licence refusal and would send us chasing the wrong bug. Until a capture
            // defines the format, close cleanly so the caller's fallback puts EA Desktop back in
            // the path.
            *last_error.lock().expect("lsx poisoned") =
                "serve mode not implemented — capture a transcript first".to_string();
            let _ = game.shutdown(Shutdown::Both);
        }
    })
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
        server.set_transcript(move |id, dir, bytes| {
            tx.send((id, dir, bytes)).unwrap();
        });
        assert!(server.start(LsxConfig {
            mode: Mode::Capture { upstream_port },
            ..Default::default()
        }));

        let mut game = TcpStream::connect(("127.0.0.1", server.port())).unwrap();
        game.write_all(b"<RequestLicense/>").unwrap();
        game.flush().unwrap();

        let (_, d1, b1) = rx.recv_timeout(Duration::from_secs(3)).unwrap();
        assert_eq!(d1, Direction::FromGame);
        assert_eq!(b1, b"<RequestLicense/>");

        let (_, d2, b2) = rx.recv_timeout(Duration::from_secs(3)).unwrap();
        assert_eq!(d2, Direction::ToGame);
        assert_eq!(b2, b"<RequestLicenseResponse/>");

        // And the game really received it, not just the transcript.
        let mut back = [0u8; 64];
        let n = game.read(&mut back).unwrap();
        assert_eq!(&back[..n], b"<RequestLicenseResponse/>");

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
    fn serve_mode_refuses_until_a_transcript_defines_the_format() {
        let mut server = LsxServer::default();
        assert!(server.start(LsxConfig { mode: Mode::Serve, ..Default::default() }));
        let mut game = TcpStream::connect(("127.0.0.1", server.port())).unwrap();
        let mut buf = [0u8; 8];
        assert_eq!(game.read(&mut buf).unwrap(), 0);
        assert!(server.last_error().contains("not implemented"));
        server.stop();
    }
}
