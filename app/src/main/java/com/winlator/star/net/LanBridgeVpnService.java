package com.winlator.star.net;

import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * SPIKE / DEBUG ONLY — not shipped, not wired into any user-facing UI.
 *
 * Purpose: prove the load-bearing assumption for the "LAN-over-internet" feature —
 * that traffic originating under this app's UID (which is where Wine/box64 winsock
 * traffic lands, since Winlator does NOT isolate the network namespace) is captured
 * by a per-app VpnService tun. If a broadcast fired here shows up on the tun, then
 * a game's LAN-discovery broadcast inside the container will too.
 *
 * It routes ONLY broadcast/multicast (+ common private LAN ranges) to the tun, so the
 * container's normal internet is untouched and Steam/games still launch during the test.
 *
 * Trigger (device, via root bridge):
 *   cmd appops set com.winlator.banner ACTIVATE_VPN allow      # grant VPN consent
 *   am broadcast -a com.winlator.banner.LAN_SPIKE_START -p com.winlator.banner
 * Observe:
 *   logcat -s LANSPIKE
 */
public class LanBridgeVpnService extends VpnService {
    private static final String TAG = "LANSPIKE";
    private static final int PROBE_PORT = 54545;

    private ParcelFileDescriptor tun;
    private Thread reader;
    private Thread prober;
    private volatile boolean running;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running) return START_STICKY;
        try {
            // Must register as the active VPN before establish(). With appop
            // ACTIVATE_VPN=allow (set via root for the spike) this returns null and
            // consents silently; a non-null Intent would need an Activity to show the dialog.
            Intent prep = VpnService.prepare(this);
            if (prep != null) {
                Log.e(TAG, "prepare() wants UI consent — appop ACTIVATE_VPN not honored; "
                        + "run: cmd appops set com.winlator.banner ACTIVATE_VPN allow");
                stopSelf();
                return START_NOT_STICKY;
            }
            Log.i(TAG, "prepare() ok (app registered as active VPN)");
            Builder b = new Builder()
                    .setSession("LanBridgeSpike")
                    .setMtu(1400)
                    .addAddress("10.99.0.2", 24)
                    // limited broadcast + subnet-directed broadcast catch-alls
                    .addRoute("255.255.255.255", 32)
                    .addRoute("224.0.0.0", 4)          // multicast
                    .addRoute("192.168.0.0", 16)       // typical LAN + its .255 broadcast
                    .addRoute("10.0.0.0", 8);
            b.setBlocking(true);
            tun = b.establish();
            if (tun == null) {
                Log.e(TAG, "establish() returned null — VPN consent NOT granted "
                        + "(run: cmd appops set com.winlator.banner ACTIVATE_VPN allow)");
                stopSelf();
                return START_NOT_STICKY;
            }
            running = true;
            Log.i(TAG, "tun established fd=" + tun.getFd()
                    + " — routing broadcast/multicast/private ranges to tun");
            startReader();
            startProber();
        } catch (Exception e) {
            Log.e(TAG, "establish failed", e);
            stopSelf();
        }
        return START_STICKY;
    }

    private void startReader() {
        reader = new Thread(() -> {
            byte[] pkt = new byte[32767];
            try (FileInputStream in = new FileInputStream(tun.getFileDescriptor())) {
                while (running) {
                    int n = in.read(pkt);
                    if (n <= 0) continue;
                    logPacket(pkt, n);
                }
            } catch (Exception e) {
                if (running) Log.e(TAG, "reader loop died", e);
            }
        }, "lanspike-reader");
        reader.start();
    }

    /** Parse just enough IPv4 to prove capture + classify the destination. */
    private void logPacket(byte[] p, int n) {
        if (n < 20) return;
        int version = (p[0] & 0xF0) >> 4;
        if (version != 4) return;
        int ihl = (p[0] & 0x0F) * 4;
        int proto = p[9] & 0xFF;
        String src = ip(p, 12);
        String dst = ip(p, 16);
        int dstLast = p[19] & 0xFF;
        int dstFirst = p[16] & 0xFF;
        boolean bcast = "255.255.255.255".equals(dst) || dstLast == 255;
        boolean mcast = dstFirst >= 224 && dstFirst <= 239;
        String kind = bcast ? "BROADCAST" : mcast ? "MULTICAST" : "unicast";
        String protoName = proto == 17 ? "UDP" : proto == 6 ? "TCP" : ("proto" + proto);
        String ports = "";
        if (proto == 17 && n >= ihl + 4) {
            int sp = ((p[ihl] & 0xFF) << 8) | (p[ihl + 1] & 0xFF);
            int dp = ((p[ihl + 2] & 0xFF) << 8) | (p[ihl + 3] & 0xFF);
            ports = " sport=" + sp + " dport=" + dp;
        }
        Log.i(TAG, "CAPTURED " + protoName + " " + src + " -> " + dst
                + ports + " len=" + n + " [" + kind + "]");
    }

    private static String ip(byte[] p, int off) {
        return (p[off] & 0xFF) + "." + (p[off + 1] & 0xFF) + "."
                + (p[off + 2] & 0xFF) + "." + (p[off + 3] & 0xFF);
    }

    /** Fire an app-UID broadcast every 2s — the exact path Wine's winsock takes. */
    private void startProber() {
        prober = new Thread(() -> {
            for (int i = 0; i < 5 && running; i++) {
                try (DatagramSocket s = new DatagramSocket()) {
                    s.setBroadcast(true);
                    byte[] msg = ("LANSPIKE-PROBE-" + i).getBytes();
                    s.send(new DatagramPacket(msg, msg.length,
                            InetAddress.getByName("255.255.255.255"), PROBE_PORT));
                    Log.i(TAG, "probe " + i + " sent to 255.255.255.255:" + PROBE_PORT
                            + " (expect a CAPTURED line if tun sees app-UID traffic)");
                } catch (Exception e) {
                    Log.e(TAG, "probe " + i + " failed", e);
                }
                try { Thread.sleep(2000); } catch (InterruptedException ie) { break; }
            }
        }, "lanspike-prober");
        prober.start();
    }

    @Override
    public void onDestroy() {
        running = false;
        try { if (tun != null) tun.close(); } catch (Exception ignored) {}
        Log.i(TAG, "stopped");
        super.onDestroy();
    }
}
