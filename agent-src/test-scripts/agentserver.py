#!/usr/bin/env python3
# Minimal stand-in for GameHub's Android-side SteamAgentServer.
# The in-Wine SteamAgent.exe connects OUT to 127.0.0.1:<STEAMAGENT_PORT> and
# streams newline-delimited JSON status/event messages. This just accepts,
# logs everything it receives, and holds the connection open so the agent
# doesn't see a dropped socket. We'll add real responses once we see the traffic.
import socket, sys, time, threading

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 47800
LOG = "/sdcard/Download/steamlite/agentserver.log"

def log(m):
    line = f"{time.strftime('%H:%M:%S')} {m}"
    try:
        with open(LOG, "a") as f:
            f.write(line + "\n")
    except Exception:
        pass
    print(line, flush=True)

def handle(conn, addr):
    log(f"[server] CONNECT from {addr}")
    conn.settimeout(120)
    try:
        buf = b""
        while True:
            data = conn.recv(8192)
            if not data:
                log("[server] peer closed connection")
                break
            buf += data
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                log("[recv] " + line.decode("utf-8", "replace").strip())
            if buf:
                log("[recv-partial] " + buf.decode("utf-8", "replace").strip())
    except socket.timeout:
        log("[server] recv timeout")
    except Exception as e:
        log(f"[server] recv error: {e}")
    finally:
        try:
            conn.close()
        except Exception:
            pass
        log("[server] connection handler done")

def main():
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("0.0.0.0", PORT))
    s.listen(8)
    log(f"[server] listening on 0.0.0.0:{PORT}")
    while True:
        try:
            conn, addr = s.accept()
            threading.Thread(target=handle, args=(conn, addr), daemon=True).start()
        except Exception as e:
            log(f"[server] accept error: {e}")
            time.sleep(0.5)

if __name__ == "__main__":
    main()
