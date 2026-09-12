package com.winlator.star.cast;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-app discovery of Google Cast devices (Chromecast / Google TV) via mDNS ({@code _googlecast._tcp})
 * using Android's native {@link NsdManager} — no third-party dependency. Feeds the in-app Cast dialog
 * so the user picks a screen from OUR list instead of the system cast screen.
 *
 * Only Google Cast devices are listed: those are the ones the no-receiver-app media-cast path can reach.
 * Roku / Miracast-only TVs are intentionally excluded (they need the system Miracast picker).
 */
public class CastDiscovery {
    private static final String TAG = "CastDiscovery";
    private static final String SERVICE_TYPE = "_googlecast._tcp.";

    /** A discovered Cast device. type is a friendly model hint ("Google TV", "Chromecast", …). */
    public static class Device {
        public final String id, name, type, host;
        public final int port;
        public Device(String id, String name, String type, String host, int port) {
            this.id = id; this.name = name; this.type = type; this.host = host; this.port = port;
        }
    }

    public interface Listener { void onDevices(List<Device> devices); }

    private final NsdManager nsd;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final Map<String, Device> found = new LinkedHashMap<>();
    private NsdManager.DiscoveryListener discoveryListener;
    private boolean discovering = false;

    public CastDiscovery(Context ctx, Listener listener) {
        this.nsd = (NsdManager) ctx.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        this.listener = listener;
    }

    /** (Re)start discovery — clears the current list and scans again (the dialog's Refresh button). */
    public void refresh() {
        stop();
        found.clear();
        emit();
        start();
    }

    public void start() {
        if (nsd == null || discovering) return;
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String t) {}
            @Override public void onDiscoveryStopped(String t) {}
            @Override public void onStartDiscoveryFailed(String t, int e) { discovering = false; }
            @Override public void onStopDiscoveryFailed(String t, int e) {}
            @Override public void onServiceLost(NsdServiceInfo s) {
                if (found.remove(s.getServiceName()) != null) emit();
            }
            @Override public void onServiceFound(NsdServiceInfo s) {
                if (!s.getServiceType().contains("googlecast")) return;
                resolve(s);
            }
        };
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
            discovering = true;
        } catch (Exception e) {
            Log.w(TAG, "discoverServices failed", e);
        }
    }

    private void resolve(NsdServiceInfo s) {
        try {
            nsd.resolveService(s, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo si, int errorCode) {}
                @Override public void onServiceResolved(NsdServiceInfo si) {
                    String fn = attr(si, "fn");                 // friendly name
                    String md = attr(si, "md");                 // model, e.g. "Chromecast", "Google TV"
                    String name = fn != null ? fn : si.getServiceName();
                    String host = si.getHost() != null ? si.getHost().getHostAddress() : "";
                    Device d = new Device(si.getServiceName(), name,
                            md != null ? md : "Cast device", host, si.getPort());
                    found.put(si.getServiceName(), d);
                    emit();
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "resolveService failed", e);
        }
    }

    private static String attr(NsdServiceInfo s, String key) {
        try {
            Map<String, byte[]> a = s.getAttributes();
            byte[] v = a != null ? a.get(key) : null;
            return v != null ? new String(v) : null;
        } catch (Exception e) { return null; }
    }

    private void emit() {
        final List<Device> list = new ArrayList<>(found.values());
        main.post(() -> { if (listener != null) listener.onDevices(list); });
    }

    public void stop() {
        if (nsd != null && discoveryListener != null && discovering) {
            try { nsd.stopServiceDiscovery(discoveryListener); } catch (Exception ignored) {}
        }
        discovering = false;
        discoveryListener = null;
    }
}
