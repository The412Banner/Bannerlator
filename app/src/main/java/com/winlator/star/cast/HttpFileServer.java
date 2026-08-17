package com.winlator.star.cast;

import android.util.Log;

import java.io.File;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Enumeration;

/**
 * Dead-simple single-file HTTP/1.1 server for casting: a Chromecast fetches the media over the LAN, so
 * we host one file on a background thread with basic HTTP Range support (the receiver seeks). Serves
 * only the one file it was started with; not a general server.
 */
public class HttpFileServer {
    private static final String TAG = "HttpFileServer";
    private File file;
    private String contentType;
    private TsSegmenter hls;                 // set for live HLS mode (serves .m3u8 + .ts from memory)
    private ServerSocket server;
    private volatile boolean running = false;
    private int port = -1;

    public HttpFileServer(File file, String contentType) { this.file = file; this.contentType = contentType; }

    /** Live HLS mode: serve the segmenter's rolling playlist + in-memory .ts segments. */
    public HttpFileServer(TsSegmenter segmenter) { this.hls = segmenter; }

    public int start() throws Exception {
        server = new ServerSocket(0);           // any free port
        port = server.getLocalPort();
        running = true;
        new Thread(this::acceptLoop, "cast-http").start();
        return port;
    }

    public int getPort() { return port; }

    /** The phone's Wi-Fi IPv4 address, for building the URL the TV fetches from. */
    public static String localIpv4() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && a instanceof java.net.Inet4Address
                            && a.getHostAddress() != null && a.getHostAddress().startsWith("192.168")) {
                        return a.getHostAddress();
                    }
                }
            }
            // fall back to any non-loopback IPv4
            ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                Enumeration<InetAddress> addrs = ifs.nextElement().getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && a instanceof java.net.Inet4Address) return a.getHostAddress();
                }
            }
        } catch (Exception e) { Log.w(TAG, "localIpv4 failed", e); }
        return null;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = server.accept();
                new Thread(() -> serve(client), "cast-http-conn").start();
            } catch (Exception e) {
                if (running) Log.w(TAG, "accept failed", e);
                break;
            }
        }
    }

    private void serve(Socket client) {
        try (Socket c = client) {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(c.getInputStream()));
            String reqLine = r.readLine();          // e.g. "GET /live.m3u8 HTTP/1.1"
            long rStart = 0, rEnd = -1; boolean partial = false;
            String h;
            while ((h = r.readLine()) != null && !h.isEmpty()) {
                if (h.toLowerCase().startsWith("range:")) {
                    partial = true;
                    String rng = h.substring(h.indexOf('=') + 1).trim();
                    String[] parts = rng.split("-");
                    try {
                        rStart = parts[0].isEmpty() ? 0 : Long.parseLong(parts[0]);
                        if (parts.length > 1 && !parts[1].isEmpty()) rEnd = Long.parseLong(parts[1]);
                    } catch (NumberFormatException ignored) {}
                }
            }
            String path = "/";
            if (reqLine != null) { String[] t = reqLine.split(" "); if (t.length >= 2) path = t[1]; }
            OutputStream os = c.getOutputStream();
            if (hls != null) serveHls(os, path);
            else serveFile(os, partial, rStart, rEnd);
        } catch (Exception e) {
            // client hang-ups are normal; keep quiet
        }
    }

    private void serveHls(OutputStream os, String path) throws Exception {
        if (path.endsWith("master.m3u8")) {
            // Multivariant playlist declaring the codecs up front so the receiver preps its decoders
            // (avc1.42E01F = H.264 Baseline L3.1, mp4a.40.2 = AAC-LC).
            String m = "#EXTM3U\n#EXT-X-VERSION:3\n"
                    + "#EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1280x720,CODECS=\"avc1.42E01F,mp4a.40.2\"\n"
                    + "live.m3u8\n";
            byte[] body = m.getBytes("UTF-8");
            Log.i(TAG, "GET " + path + " -> 200 master(" + body.length + "B)");
            writeHead(os, "200 OK", "application/vnd.apple.mpegurl", body.length, false, 0, 0, 0);
            os.write(body); os.flush();
            return;
        }
        if (path.endsWith(".m3u8")) {
            byte[] body = hls.playlist().getBytes("UTF-8");
            Log.i(TAG, "GET " + path + " -> 200 playlist(" + body.length + "B)");
            writeHead(os, "200 OK", "application/vnd.apple.mpegurl", body.length, false, 0, 0, 0);
            os.write(body);
        } else if (path.endsWith(".ts")) {
            String name = path.substring(path.lastIndexOf('/') + 1);
            byte[] body = hls.getSegment(name);
            Log.i(TAG, "GET " + path + " -> " + (body == null ? "404" : "200 seg(" + body.length + "B)"));
            if (body == null) { writeHead(os, "404 Not Found", "text/plain", 0, false, 0, 0, 0); os.flush(); return; }
            writeHead(os, "200 OK", "video/mp2t", body.length, false, 0, 0, 0);
            os.write(body);
        } else {
            Log.i(TAG, "GET " + path + " -> 404");
            writeHead(os, "404 Not Found", "text/plain", 0, false, 0, 0, 0);
        }
        os.flush();
    }

    private void serveFile(OutputStream os, boolean partial, long start, long endReq) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long total = file.length();
            long end = endReq < 0 ? total - 1 : endReq;
            long length = end - start + 1;
            writeHead(os, partial ? "206 Partial Content" : "200 OK", contentType, length, partial, start, end, total);
            raf.seek(start);
            byte[] buf = new byte[64 * 1024];
            long remaining = length;
            while (remaining > 0) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) break;
                os.write(buf, 0, n);
                remaining -= n;
            }
            os.flush();
        }
    }

    private void writeHead(OutputStream os, String status, String ctype, long len,
                           boolean partial, long s, long e, long total) throws Exception {
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(status).append("\r\n");
        h.append("Content-Type: ").append(ctype).append("\r\n");
        h.append("Access-Control-Allow-Origin: *\r\n");
        h.append("Accept-Ranges: bytes\r\n");
        h.append("Content-Length: ").append(len).append("\r\n");
        if (partial) h.append("Content-Range: bytes ").append(s).append('-').append(e).append('/').append(total).append("\r\n");
        h.append("Connection: close\r\n\r\n");
        os.write(h.toString().getBytes("UTF-8"));
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
    }
}
