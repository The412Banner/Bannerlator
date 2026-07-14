package com.winlator.star.net;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;

/**
 * P1 LAN-over-internet overlay. A per-app VpnService whose tun is SCOPED to
 * Bannerlator only (addAllowedApplication) so we tunnel just the emulator's
 * traffic — the spike proved an unscoped VPN blackholes the whole device.
 *
 * Virtual subnet 10.99.0.0/24: host = .1, client = .2. Only the virtual subnet
 * + broadcast + multicast are routed to the tun (NOT public / real-LAN ranges,
 * which is the mistake that ate DNS in the spike). The tun fd is handed to
 * liblannet.so, which classifies each packet and pumps it to/from the relay.
 *
 * Consent: for the P1 device bring-up, VPN consent is granted out-of-band
 * (root appops ACTIVATE_VPN). A prepare()-launching Activity replaces that for
 * the shipped UX.
 *
 * Trigger (device):
 *   am startservice -n com.winlator.banner/com.winlator.star.net.LanOverlayVpnService \
 *     -a START --es relay 127.0.0.1 --ei port 48800 --es room GAME01 --ei role 1
 */
public class LanOverlayVpnService extends VpnService {
    private static final String TAG = "lannet";
    public static final String EXTRA_RELAY = "relay";
    public static final String EXTRA_PORT  = "port";
    public static final String EXTRA_ROOM  = "room";
    public static final String EXTRA_ROLE  = "role";
    public static final String SELF_PKG    = "com.winlator.banner";

    private ParcelFileDescriptor tun;
    private long handle;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if ("STOP".equals(intent != null ? intent.getAction() : null)) {
            teardown();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (handle != 0) return START_STICKY;   // already running

        String relay = intent != null ? intent.getStringExtra(EXTRA_RELAY) : null;
        int port     = intent != null ? intent.getIntExtra(EXTRA_PORT, 48800) : 48800;
        String room  = intent != null ? intent.getStringExtra(EXTRA_ROOM) : null;
        int role     = intent != null ? intent.getIntExtra(EXTRA_ROLE, LanOverlay.ROLE_HOST) : LanOverlay.ROLE_HOST;
        if (relay == null || room == null) {
            Log.e(TAG, "missing relay/room extras");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (VpnService.prepare(this) != null) {
            Log.e(TAG, "VPN not consented — run: cmd appops set " + SELF_PKG + " ACTIVATE_VPN allow");
            stopSelf();
            return START_NOT_STICKY;
        }

        String selfIp = (role == LanOverlay.ROLE_HOST) ? "10.99.0.1" : "10.99.0.2";
        // The underlying Wi-Fi's directed broadcast (e.g. 192.168.1.255). Many LAN
        // games (FlatOut 2, etc.) discover peers by broadcasting to THIS instead of
        // 255.255.255.255, so we must route it into the tun and tell native to treat
        // it as a broadcast. Computed BEFORE establish() while the real net is active.
        int localBcastInt = 0;
        String localBcastStr = null;
        try {
            InetAddress db = computeUnderlyingDirectedBroadcast();
            if (db != null) {
                localBcastStr = db.getHostAddress();
                byte[] o = db.getAddress();
                localBcastInt = ((o[0] & 0xFF) << 24) | ((o[1] & 0xFF) << 16)
                              | ((o[2] & 0xFF) << 8) | (o[3] & 0xFF);
            }
        } catch (Exception e) {
            Log.w(TAG, "could not resolve underlying directed broadcast", e);
        }

        try {
            Builder b = new Builder()
                    .setSession("Bannerlator LAN")
                    .setMtu(1380)
                    .addAddress(selfIp, 24)
                    .addRoute("10.99.0.0", 24)          // peer unicast + subnet broadcast
                    .addRoute("255.255.255.255", 32)     // limited broadcast
                    .addRoute("224.0.0.0", 4);           // multicast
            if (localBcastStr != null) {
                b.addRoute(localBcastStr, 32);           // real-net directed broadcast (e.g. 192.168.1.255)
                Log.i(TAG, "routing underlying directed broadcast " + localBcastStr + " into tun");
            }
            b.addAllowedApplication(SELF_PKG);           // SCOPE: only the emulator
            b.setBlocking(true);
            tun = b.establish();
            if (tun == null) { Log.e(TAG, "establish() null"); stopSelf(); return START_NOT_STICKY; }

            handle = LanOverlay.nativeStart(tun.getFd(), relay, port, room, role, localBcastInt);
            if (handle == 0) { Log.e(TAG, "nativeStart failed"); teardown(); stopSelf(); return START_NOT_STICKY; }
            Log.i(TAG, "overlay up: " + selfIp + " role=" + role + " relay=" + relay + ":" + port + " room=" + room);
            // Tell the shared session state the tunnel is up so all 3 UI surfaces flip to connected.
            LanSessionState.onOverlayUp(room, role);
        } catch (Exception e) {
            Log.e(TAG, "start failed", e);
            teardown();
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    /**
     * Find the underlying real network's IPv4 subnet and return its directed
     * broadcast address (e.g. 192.168.1.0/24 -> 192.168.1.255). Prefers the
     * active network; falls back to scanning all networks. Skips loopback and
     * our own 10.99.0.0/24 overlay. Returns null if none found.
     */
    private InetAddress computeUnderlyingDirectedBroadcast() throws Exception {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;

        Network[] candidates;
        Network active = cm.getActiveNetwork();
        if (active != null) {
            candidates = new Network[]{active};
        } else {
            candidates = cm.getAllNetworks();
        }
        for (Network net : candidates) {
            LinkProperties lp = cm.getLinkProperties(net);
            if (lp == null) continue;
            for (LinkAddress la : lp.getLinkAddresses()) {
                InetAddress addr = la.getAddress();
                if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) continue;
                byte[] o = addr.getAddress();
                int ip = ((o[0] & 0xFF) << 24) | ((o[1] & 0xFF) << 16)
                       | ((o[2] & 0xFF) << 8) | (o[3] & 0xFF);
                if ((ip & 0xFFFFFF00) == 0x0A630000) continue;   // skip our own 10.99.0.x overlay
                int prefix = la.getPrefixLength();
                if (prefix <= 0 || prefix >= 31) continue;       // need real host bits
                int hostMask = (int) (0xFFFFFFFFL >>> prefix);
                int directed = (ip & ~hostMask) | hostMask;      // network | all-ones host part
                byte[] out = new byte[]{
                        (byte) (directed >>> 24), (byte) (directed >>> 16),
                        (byte) (directed >>> 8),  (byte) directed};
                return InetAddress.getByAddress(out);
            }
        }
        return null;
    }

    private void teardown() {
        if (handle != 0) { LanOverlay.nativeStop(handle); handle = 0; }
        if (tun != null) { try { tun.close(); } catch (Exception ignored) {} tun = null; }
    }

    @Override
    public void onDestroy() {
        teardown();
        // Overlay stopped (user Stop, or a failed start) — return every UI surface to Idle.
        LanSessionState.onOverlayDown();
        Log.i(TAG, "overlay stopped");
        super.onDestroy();
    }
}
