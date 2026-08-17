package com.winlator.star.xserver.extensions;

import static com.winlator.star.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.star.xconnector.XInputStream;
import com.winlator.star.xconnector.XOutputStream;
import com.winlator.star.xconnector.XStreamLock;
import com.winlator.star.xserver.ScreenInfo;
import com.winlator.star.xserver.XClient;
import com.winlator.star.xserver.errors.XRequestError;

import java.io.IOException;

/**
 * Minimal RandR 1.1 server, enough for Wine to enumerate refresh rates.
 *
 * <p>Without this extension {@code winex11.drv} finds no RandR, falls back to its "NoRes" settings
 * handler (priority 1) and reports exactly one display mode with {@code dmDisplayFrequency}
 * hardcoded to 60 — which is why every in-game refresh dropdown was locked at 60 Hz regardless of
 * the panel. Registering any RandR version lets Wine install its {@code xrandr10} handler at
 * priority 200 instead, and that handler builds its mode list from {@code XRRSizes()} and
 * {@code XRRRates()} — both of which are client-side parses of a single {@code RRGetScreenInfo}
 * reply, so this one reply is the whole mechanism.
 *
 * <p><b>We deliberately advertise 1.1, not something higher.</b> 1.1 is the version that added
 * refresh rates to {@code RRGetScreenInfo}, so it is the minimum that can carry them. Reporting
 * &gt;= 1.4 would make Wine additionally install its 1.4 display handler, which issues
 * {@code RRGetScreenResources} / {@code RRGetOutputInfo} / {@code RRGetCrtcInfo} — none of which
 * exist here. Advertising 1.1 keeps Wine on the path we actually implement.
 *
 * <p>Only ONE screen size is offered (the container's current resolution), matching what the NoRes
 * fallback already did — this changes the refresh list, not resolution handling. Mode <i>changes</i>
 * are accepted and acknowledged but not acted upon: the compositor's output rate is driven by the
 * host surface, not by the guest's choice. Accepting them matters because a game that gets
 * {@code DISP_CHANGE_FAILED} may refuse to apply its own setting at all.
 */
public class RandrExtension implements Extension {
    public static final byte MAJOR_OPCODE = -105;

    private static final short RR_ROTATE_0 = 1;

    /** Rotations we claim to support — unrotated only. */
    private static final byte SET_OF_ROTATIONS = (byte)RR_ROTATE_0;

    private static abstract class ClientOpcodes {
        private static final byte QUERY_VERSION = 0;
        private static final byte SET_SCREEN_CONFIG = 2;
        private static final byte SELECT_INPUT = 4;
        private static final byte GET_SCREEN_INFO = 5;
    }

    private final ScreenInfo screenInfo;

    /**
     * Advertised refresh rates in Hz, highest first. Wine turns each into a distinct DEVMODE, so
     * this list is literally what the game's dropdown will show. Defaults to 60 only; the activity
     * replaces it with the panel's real supported rates once it knows them.
     */
    private volatile short[] refreshRates = {60};

    /** Rate reported as current. Kept in step with whatever the client last asked for. */
    private volatile short currentRate = 60;

    public RandrExtension(ScreenInfo screenInfo) {
        this.screenInfo = screenInfo;
    }

    /**
     * @param rates supported refresh rates in Hz. Order is preserved; duplicates and non-positive
     *              values are the caller's problem. Ignored if empty, so a device that reports no
     *              modes keeps the 60 Hz default rather than advertising an empty list (which Wine
     *              would read as "no rates" and collapse back to a single rate-less mode).
     */
    public void setRefreshRates(short[] rates) {
        if (rates == null || rates.length == 0) return;
        refreshRates = rates;
        currentRate = rates[0];
    }

    @Override
    public String getName() {
        return "RANDR";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public byte getFirstErrorId() {
        return Byte.MIN_VALUE;
    }

    @Override
    public byte getFirstEventId() {
        return 64;
    }

    private static void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        inputStream.skip(8); // client major + minor version
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(1); // major
            outputStream.writeInt(1); // minor
            outputStream.writePad(16);
        }
    }

    private void getScreenInfo(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        int window = inputStream.readInt();

        final short[] rates = refreshRates;
        final short nSizes = 1;
        // The rate block is, per size, a count followed by that many rates.
        final short nRateEnts = (short)(1 + rates.length);

        // Reply body: one 8-byte SCREENSIZE plus the CARD16 rate block, padded to 4 bytes.
        final int bodyBytes = nSizes * 8 + nRateEnts * 2;
        final int padBytes = (4 - (bodyBytes % 4)) % 4;

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte(SET_OF_ROTATIONS);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt((bodyBytes + padBytes) / 4);
            outputStream.writeInt(window);   // root
            outputStream.writeInt(0);        // timestamp
            outputStream.writeInt(0);        // config timestamp
            outputStream.writeShort(nSizes);
            outputStream.writeShort((short)0);       // current size id
            outputStream.writeShort(RR_ROTATE_0);    // current rotation
            outputStream.writeShort(currentRate);
            outputStream.writeShort(nRateEnts);
            outputStream.writeShort((short)0);       // pad

            // SCREENSIZE
            outputStream.writeShort(screenInfo.width);
            outputStream.writeShort(screenInfo.height);
            outputStream.writeShort(screenInfo.getWidthInMillimeters());
            outputStream.writeShort(screenInfo.getHeightInMillimeters());

            // REFRESH block for that one size
            outputStream.writeShort((short)rates.length);
            for (short rate : rates) outputStream.writeShort(rate);

            if (padBytes > 0) outputStream.writePad(padBytes);
        }
    }

    private void setScreenConfig(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        int window = inputStream.readInt();
        inputStream.skip(8);             // timestamp + config timestamp
        inputStream.readShort();         // size id — only one size exists, so nothing to select
        inputStream.readShort();         // rotation
        short rate = inputStream.readShort();
        inputStream.skip(2);             // pad

        // Remember the requested rate so a subsequent GetScreenInfo reports back what was asked
        // for. Nothing downstream changes: the host surface owns the real present rate.
        if (rate > 0) currentRate = rate;

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0); // status: RRSetConfigSuccess
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(0);        // new timestamp
            outputStream.writeInt(0);        // new config timestamp
            outputStream.writeInt(window);   // root
            outputStream.writeShort((short)0); // subpixel order: unknown
            outputStream.writeShort((short)0); // pad
            outputStream.writePad(8);
        }
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        switch (opcode) {
            case ClientOpcodes.QUERY_VERSION:
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.GET_SCREEN_INFO:
                getScreenInfo(client, inputStream, outputStream);
                break;
            case ClientOpcodes.SET_SCREEN_CONFIG:
                setScreenConfig(client, inputStream, outputStream);
                break;
            case ClientOpcodes.SELECT_INPUT:
                // We never send RRScreenChangeNotify, so registering interest is a no-op. Still has
                // to be consumed rather than left in the stream, and it carries no reply.
                inputStream.skip(8);
                break;
            default:
                break;
        }
    }
}
