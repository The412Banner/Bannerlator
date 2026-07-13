package com.winlator.star.net;

/**
 * JNI facade for the lannet overlay tunnel (liblannet.so). The native side runs
 * the pump loop on its own worker thread; Java owns lifecycle via the returned
 * handle. See LanOverlayVpnService for the tun + lifecycle.
 */
public final class LanOverlay {
    static { System.loadLibrary("lannet"); }

    public static final int ROLE_HOST = 1;
    public static final int ROLE_CLIENT = 2;

    private LanOverlay() {}

    /** Returns a native handle (0 == failure). tunFd is the VpnService tun fd. */
    public static native long nativeStart(int tunFd, String relayIp, int relayPort,
                                          String room, int role);

    public static native void nativeStop(long handle);
}
