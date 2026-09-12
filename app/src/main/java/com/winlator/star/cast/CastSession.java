package com.winlator.star.cast;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Minimal Google Cast v2 protocol client — enough to make a Chromecast / Google TV play a media URL,
 * with NO Cast SDK dependency (so it works directly with the IPs our own mDNS discovery finds).
 *
 * Cast v2 = length-prefixed protobuf {@code CastMessage} frames over TLS on port 8009, each carrying a
 * JSON payload on a namespace. Flow: CONNECT → LAUNCH the Default Media Receiver (appId CC1AD845) →
 * from RECEIVER_STATUS grab the launched app's transportId → CONNECT to it → LOAD the media URL. A
 * heartbeat PING keeps the socket alive. The device's cert is self-signed, so we trust-all (LAN only).
 */
public class CastSession {
    private static final String TAG = "CastSession";
    private static final int PORT = 8009;
    private static final String NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection";
    private static final String NS_HEARTBEAT  = "urn:x-cast:com.google.cast.tp.heartbeat";
    private static final String NS_RECEIVER   = "urn:x-cast:com.google.cast.receiver";
    private static final String NS_MEDIA      = "urn:x-cast:com.google.cast.media";
    private static final String DEFAULT_MEDIA_RECEIVER = "CC1AD845";
    private static final String SRC = "sender-0";

    public interface Callback {
        void onConnected();                 // media receiver launched, ready to LOAD
        void onLoaded();                    // media accepted
        void onError(String message);
    }

    private final String host;
    private final Callback cb;
    private SSLSocket socket;
    private DataOutputStream out;
    private volatile boolean running = false;
    private String mediaTransportId;        // destination for CONNECT + media messages
    private int requestId = 1;
    // Media to load once the receiver app is up.
    private String pendingUrl, pendingContentType, pendingStreamType;
    private boolean loadSent = false;

    public CastSession(String host, Callback cb) { this.host = host; this.cb = cb; }

    /** Connect + launch the media receiver, then LOAD this media once it's ready. Off the main thread. */
    public void connectAndLoad(String url, String contentType, String streamType) {
        this.pendingUrl = url; this.pendingContentType = contentType; this.pendingStreamType = streamType;
        new Thread(this::run, "cast-session").start();
    }

    private void run() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{ TRUST_ALL }, new java.security.SecureRandom());
            SSLSocketFactory sf = ctx.getSocketFactory();
            socket = (SSLSocket) sf.createSocket();
            socket.connect(new InetSocketAddress(host, PORT), 5000);
            socket.startHandshake();
            out = new DataOutputStream(socket.getOutputStream());
            running = true;

            send(NS_CONNECTION, "receiver-0", "{\"type\":\"CONNECT\"}");
            send(NS_RECEIVER, "receiver-0", "{\"type\":\"LAUNCH\",\"appId\":\"" + DEFAULT_MEDIA_RECEIVER
                    + "\",\"requestId\":" + (requestId++) + "}");
            startHeartbeat();

            DataInputStream in = new DataInputStream(socket.getInputStream());
            while (running) {
                int len = in.readInt();                 // 4-byte big-endian frame length
                if (len <= 0 || len > 1_000_000) break;
                byte[] frame = new byte[len];
                in.readFully(frame);
                handle(parsePayload(frame));
            }
        } catch (Exception e) {
            Log.w(TAG, "session ended: " + e.getMessage());
            if (cb != null && !loadSent) cb.onError("Couldn't reach the TV's cast receiver.");
        } finally {
            closeQuietly();
        }
    }

    private void handle(String json) {
        if (json == null) return;
        // The receiver pings us; answer or it drops the connection.
        if (json.contains("\"PING\"")) {
            try { send(NS_HEARTBEAT, "receiver-0", "{\"type\":\"PONG\"}"); } catch (Exception ignored) {}
            return;
        }
        // Launched-app transportId appears in RECEIVER_STATUS. Grab it, connect to it, then LOAD.
        if (mediaTransportId == null && json.contains("\"transportId\"")) {
            String tid = extract(json, "transportId");
            if (tid != null) {
                mediaTransportId = tid;
                try {
                    send(NS_CONNECTION, tid, "{\"type\":\"CONNECT\"}");
                    if (cb != null) cb.onConnected();
                    sendLoad();
                } catch (Exception e) { Log.w(TAG, "connect-to-app failed", e); }
            }
        }
        if (json.contains("MEDIA_STATUS")) {
            Log.i(TAG, "MEDIA_STATUS: " + json.substring(0, Math.min(json.length(), 500)));
            if (loadSent && cb != null) cb.onLoaded();
        }
        if (json.contains("LOAD_FAILED") || json.contains("LOAD_CANCELLED") || json.contains("INVALID_REQUEST")) {
            Log.w(TAG, "load problem: " + json.substring(0, Math.min(json.length(), 500)));
        }
    }

    private void sendLoad() throws Exception {
        if (loadSent || mediaTransportId == null || pendingUrl == null) return;
        String payload = "{\"type\":\"LOAD\",\"requestId\":" + (requestId++)
                + ",\"autoplay\":true,\"media\":{\"contentId\":\"" + pendingUrl
                + "\",\"streamType\":\"" + pendingStreamType
                + "\",\"contentType\":\"" + pendingContentType + "\"}}";
        send(NS_MEDIA, mediaTransportId, payload);
        loadSent = true;
    }

    private void startHeartbeat() {
        new Thread(() -> {
            try {
                while (running) {
                    Thread.sleep(4500);
                    send(NS_HEARTBEAT, "receiver-0", "{\"type\":\"PING\"}");
                }
            } catch (Exception ignored) {}
        }, "cast-heartbeat").start();
    }

    public void close() {
        try { if (out != null && mediaTransportId != null) send(NS_CONNECTION, mediaTransportId, "{\"type\":\"CLOSE\"}"); } catch (Exception ignored) {}
        running = false;
        closeQuietly();
    }

    private void closeQuietly() {
        running = false;
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
    }

    // ---- Cast protobuf CastMessage framing (hand-serialized) ------------------------------------

    private synchronized void send(String namespace, String dest, String payloadJson) throws Exception {
        byte[] msg = buildCastMessage(namespace, dest, payloadJson);
        out.writeInt(msg.length);   // 4-byte big-endian length prefix
        out.write(msg);
        out.flush();
    }

    private static byte[] buildCastMessage(String namespace, String dest, String payload) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeVarintField(b, 1, 0);                // protocol_version = CASTV2_1_0 (0)
        writeStringField(b, 2, SRC);              // source_id
        writeStringField(b, 3, dest);             // destination_id
        writeStringField(b, 4, namespace);        // namespace
        writeVarintField(b, 5, 0);                // payload_type = STRING (0)
        writeStringField(b, 6, payload);          // payload_utf8
        return b.toByteArray();
    }

    private static void writeVarintField(ByteArrayOutputStream b, int field, long value) {
        writeTag(b, field, 0);
        writeVarint(b, value);
    }

    private static void writeStringField(ByteArrayOutputStream b, int field, String s) throws Exception {
        byte[] data = s.getBytes("UTF-8");
        writeTag(b, field, 2);
        writeVarint(b, data.length);
        b.write(data, 0, data.length);
    }

    private static void writeTag(ByteArrayOutputStream b, int field, int wireType) {
        writeVarint(b, ((long) field << 3) | wireType);
    }

    private static void writeVarint(ByteArrayOutputStream b, long value) {
        while (true) {
            if ((value & ~0x7FL) == 0) { b.write((int) value); return; }
            b.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
    }

    // Pull the payload_utf8 (field 6) JSON string out of a CastMessage frame without a full protobuf lib.
    private static String parsePayload(byte[] frame) {
        try {
            int i = 0;
            String payload = null;
            while (i < frame.length) {
                long tag = frame[i] & 0xFF; int field = (int) (tag >> 3); int wt = (int) (tag & 7); i++;
                if (wt == 0) {                     // varint
                    while (i < frame.length && (frame[i] & 0x80) != 0) i++;
                    i++;
                } else if (wt == 2) {              // length-delimited
                    int len = 0, shift = 0;
                    while (i < frame.length && (frame[i] & 0x80) != 0) { len |= (frame[i] & 0x7F) << shift; shift += 7; i++; }
                    len |= (frame[i] & 0x7F) << shift; i++;
                    if (field == 6) payload = new String(frame, i, len, "UTF-8");
                    i += len;
                } else break;
            }
            return payload;
        } catch (Exception e) { return null; }
    }

    private static String extract(String json, String key) {
        String needle = "\"" + key + "\"";
        int k = json.indexOf(needle);
        if (k < 0) return null;
        int c = json.indexOf(':', k + needle.length());
        if (c < 0) return null;
        int q1 = json.indexOf('"', c);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }

    private static final X509TrustManager TRUST_ALL = new X509TrustManager() {
        public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
        public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
        public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
    };
}
