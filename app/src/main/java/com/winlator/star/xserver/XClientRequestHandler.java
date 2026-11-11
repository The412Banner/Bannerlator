package com.winlator.star.xserver;

import android.util.Log;

import com.winlator.star.xconnector.Client;
import com.winlator.star.xconnector.RequestHandler;
import com.winlator.star.xconnector.XInputStream;
import com.winlator.star.xconnector.XOutputStream;
import com.winlator.star.xconnector.XStreamLock;
import com.winlator.star.xserver.errors.BadAlloc;
import com.winlator.star.xserver.errors.BadRequest;
import com.winlator.star.xserver.errors.XRequestError;
import com.winlator.star.xserver.extensions.Extension;
import com.winlator.star.xserver.requests.AtomRequests;
import com.winlator.star.xserver.requests.ColormapRequests;
import com.winlator.star.xserver.requests.CursorRequests;
import com.winlator.star.xserver.requests.DrawRequests;
import com.winlator.star.xserver.requests.ExtensionRequests;
import com.winlator.star.xserver.requests.FontRequests;
import com.winlator.star.xserver.requests.GrabRequests;
import com.winlator.star.xserver.requests.GraphicsContextRequests;
import com.winlator.star.xserver.requests.KeyboardRequests;
import com.winlator.star.xserver.requests.PixmapRequests;
import com.winlator.star.xserver.requests.SelectionRequests;
import com.winlator.star.xserver.requests.WindowRequests;

import java.io.IOException;
import java.nio.ByteOrder;

public class XClientRequestHandler implements RequestHandler {
    public static final byte RESPONSE_CODE_ERROR = 0;
    public static final byte RESPONSE_CODE_SUCCESS = 1;
    public static final int MAX_REQUEST_LENGTH = 65535;

    @Override
    public boolean handleRequest(Client client) throws IOException {
        XClient xClient = (XClient)client.getTag();
        XInputStream inputStream = client.getInputStream();
        XOutputStream outputStream = client.getOutputStream();

        if (xClient.isAuthenticated()) {
            return handleNormalRequest(xClient, inputStream, outputStream);
        }
        else return handleAuthRequest(xClient, inputStream, outputStream);
    }

    private void sendServerInformation(XClient client, XOutputStream outputStream) throws IOException {
        short vendorNameLength = (short)XServer.VENDOR_NAME.length();
        byte pixmapFormatCount = (byte)client.xServer.pixmapManager.supportedPixmapFormats.length;
        short additionalDataLength = (short)(8 + (2 * pixmapFormatCount) + ((vendorNameLength + 3) / 4) + ((40 + 8 * client.xServer.pixmapManager.supportedVisuals.length + 24) + 3) / 4);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(XServer.VERSION);
            outputStream.writeShort((short)0);
            outputStream.writeShort(additionalDataLength);
            outputStream.writeInt(1);
            outputStream.writeInt(client.resourceIDBase);
            outputStream.writeInt(client.xServer.resourceIDs.idMask);
            outputStream.writeInt(256);
            outputStream.writeShort(vendorNameLength);
            outputStream.writeShort((short)MAX_REQUEST_LENGTH);
            outputStream.writeByte((byte)1);
            outputStream.writeByte(pixmapFormatCount);
            outputStream.writeByte((byte)0);
            outputStream.writeByte((byte)0);
            outputStream.writeByte((byte)32);
            outputStream.writeByte((byte)32);
            outputStream.writeByte((byte)Keyboard.MIN_KEYCODE);
            outputStream.writeByte((byte)Keyboard.MAX_KEYCODE);
            outputStream.writeInt(0);
            outputStream.writeString8(XServer.VENDOR_NAME);

            for (PixmapFormat pixmapFormat : client.xServer.pixmapManager.supportedPixmapFormats) {
                outputStream.writeByte(pixmapFormat.depth);
                outputStream.writeByte(pixmapFormat.bitsPerPixel);
                outputStream.writeByte(pixmapFormat.scanlinePad);
                outputStream.writePad(5);
            }

            Visual rootVisual = client.xServer.windowManager.rootWindow.getContent().visual;

            outputStream.writeInt(client.xServer.windowManager.rootWindow.id);
            outputStream.writeInt(0);
            outputStream.writeInt(0xffffff);
            outputStream.writeInt(0x000000);
            outputStream.writeInt(client.xServer.windowManager.rootWindow.getAllEventMasks().getBits());
            outputStream.writeShort(client.xServer.screenInfo.width);
            outputStream.writeShort(client.xServer.screenInfo.height);
            outputStream.writeShort(client.xServer.screenInfo.getWidthInMillimeters());
            outputStream.writeShort(client.xServer.screenInfo.getHeightInMillimeters());
            outputStream.writeShort((short)1);
            outputStream.writeShort((short)1);
            outputStream.writeInt(rootVisual.id);
            outputStream.writeByte((byte)0);
            outputStream.writeByte((byte)0);
            outputStream.writeByte(rootVisual.depth);
            outputStream.writeByte((byte)client.xServer.pixmapManager.supportedVisuals.length);

            for (Visual visual : client.xServer.pixmapManager.supportedVisuals) {
                outputStream.writeByte(visual.depth);
                outputStream.writeByte((byte)0);
                outputStream.writeShort((short)(visual.displayable ? 1 : 0));
                outputStream.writeInt(0);

                if (visual.displayable) {
                    outputStream.writeInt(visual.id);
                    outputStream.writeByte(visual.visualClass);
                    outputStream.writeByte(visual.bitsPerRGBValue);
                    outputStream.writeShort(visual.colormapEntries);
                    outputStream.writeInt(visual.redMask);
                    outputStream.writeInt(visual.greenMask);
                    outputStream.writeInt(visual.blueMask);
                    outputStream.writeInt(0);
                }
            }
        }
    }

    private boolean handleAuthRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        if (inputStream.available() < 12) return false;

        byte byteOrder = inputStream.readByte();
        if (byteOrder == 66) {
            inputStream.setByteOrder(ByteOrder.BIG_ENDIAN);
            outputStream.setByteOrder(ByteOrder.BIG_ENDIAN);
        }
        else if (byteOrder == 108) {
            inputStream.setByteOrder(ByteOrder.LITTLE_ENDIAN);
            outputStream.setByteOrder(ByteOrder.LITTLE_ENDIAN);
        }

        inputStream.skip(1);

        short majorVersion = inputStream.readShort();
        inputStream.skip(2);
        int nameLength = inputStream.readUnsignedShort();
        int dataLength = inputStream.readUnsignedShort();
        inputStream.skip(2);
        // The whole setup request must be here before it is read (the caller rewinds on false).
        if (inputStream.available() < pad4(nameLength) + pad4(dataLength)) return false;

        if ((byteOrder != 66 && byteOrder != 108) || majorVersion != 11) {
            // Refuse the connection as X does (status Failed + reason); it used to throw on the X thread
            // and end the app. The IOException makes the connector close this client.
            refuseConnection(outputStream, "only X protocol version 11 is supported");
            throw new IOException("X client refused: byte order " + byteOrder + ", protocol version " + majorVersion);
        }

        if (nameLength > 0) inputStream.readString8(nameLength);
        if (dataLength > 0) inputStream.readString8(dataLength);

        try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
            sendServerInformation(client, outputStream);
        }

        client.setAuthenticated(true);
        return true;
    }

    private static int pad4(int length) {
        return (length + 3) & ~3;
    }

    private void refuseConnection(XOutputStream outputStream, String reason) throws IOException {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_ERROR);
            outputStream.writeByte((byte)reason.length());
            outputStream.writeShort(XServer.VERSION);
            outputStream.writeShort((short)0);
            outputStream.writeShort((short)(pad4(reason.length()) / 4));
            outputStream.writeString8(reason);
        }
    }

    /** Core requests (opcodes 1-119, 127) that have a reply. */
    private static final boolean[] CORE_HAS_REPLY = new boolean[128];
    static {
        for (int op : new int[]{3, 14, 15, 16, 17, 20, 21, 23, 26, 31, 38, 39, 40, 43, 44, 47, 48, 49, 50, 52, 73,
                83, 84, 85, 86, 87, 91, 92, 97, 98, 99, 101, 103, 106, 108, 110, 116, 117, 118, 119}) {
            CORE_HAS_REPLY[op] = true;
        }
    }

    /** One log line per opcode and kind of problem, not one per request. */
    private static final boolean[] loggedUnsupported = new boolean[256];
    private static final boolean[] loggedFailed = new boolean[256];
    private static final boolean[] loggedLength = new boolean[256];

    /**
     * A request this server doesn't handle: its body is skipped either way. A core request without a
     * reply is ignored (Wine's X error handler ends the program on an unexpected error, and real
     * servers implement them all). A core request with a reply gets BadAlloc, which Xlib's _XReply
     * turns into a failed call without calling the error handler (libX11 xcb_io.c), so the client
     * doesn't wait for a reply that never comes. Opcodes X doesn't define, or of an extension this
     * server doesn't have, get BadRequest.
     */
    private static void unsupportedRequest(XClient client, byte opcode) throws XRequestError {
        int op = opcode & 0xff;
        boolean defined = op >= 1 && (op <= 119 || op == 127);
        if (!loggedUnsupported[op]) {
            loggedUnsupported[op] = true;
            Log.w("XClientRequestHandler", "Unsupported " + (opcode < 0 ? "extension " : "") + "opcode " + op
                    + (defined ? (CORE_HAS_REPLY[op] ? " (has a reply: BadAlloc)" : " (ignored)") : " (BadRequest)"));
        }
        client.skipRequest();
        if (!defined) throw new BadRequest();
        if (CORE_HAS_REPLY[op]) throw new BadAlloc();
    }

    private boolean handleNormalRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        if (inputStream.available() < 4) return false;
        byte opcode = inputStream.readByte();
        byte requestData = inputStream.readByte();

        int requestLength = inputStream.readUnsignedShort();
        if (requestLength != 0) {
            requestLength = requestLength * 4 - 4;
        }
        else if (inputStream.available() < 4) {
            return false;
        }
        else {
            long words = Integer.toUnsignedLong(inputStream.readInt());
            requestLength = words >= 2 && words <= Integer.MAX_VALUE / 4 ? (int)(words * 4 - 8) : -1;
        }
        // A length that can't be right (big-request length under 2 words): the stream can't be followed
        // any further, so the connector closes this client.
        if (requestLength < 0) throw new IOException("X client sent an invalid request length (opcode " + (opcode & 0xff) + ")");
        if (inputStream.available() < requestLength) return false;

        client.generateSequenceNumber();
        client.setRequestData(requestData);
        client.setRequestLength(requestLength);
        // Where the next request starts. Every exit below continues from here, so a request this server
        // reads too little or too much of can't shift the rest of the stream.
        final int requestEnd = inputStream.getActivePosition() + requestLength;

        try {
            switch (opcode) {
                case ClientOpcodes.CREATE_WINDOW:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.INPUT_DEVICE, XServer.Lockable.CURSOR_MANAGER)) {
                        WindowRequests.createWindow(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.CHANGE_WINDOW_ATTRIBUTES:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.CURSOR_MANAGER)) {
                        WindowRequests.changeWindowAttributes(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.GET_WINDOW_ATTRIBUTES:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                        WindowRequests.getWindowAttributes(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.DESTROY_WINDOW:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                        WindowRequests.destroyWindow(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.DESTROY_SUB_WINDOWS:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                        WindowRequests.destroySubWindows(client, inputStream, outputStream);
                    }
                case ClientOpcodes.REPARENT_WINDOW:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                        WindowRequests.reparentWindow(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.MAP_WINDOW:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                        WindowRequests.mapWindow(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.MAP_SUB_WINDOWS:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                        WindowRequests.mapSubWindows(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.UNMAP_WINDOW:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                        WindowRequests.unmapWindow(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.CONFIGURE_WINDOW:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                        WindowRequests.configureWindow(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.GET_GEOMETRY:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
                        WindowRequests.getGeometry(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.QUERY_TREE:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                        WindowRequests.queryTree(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.INTERN_ATOM:
                    AtomRequests.internAtom(client, inputStream, outputStream);
                    break;
                /* This seems to also link to UnmapWindow */
                case ClientOpcodes.GET_ATOM_NAME:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                        AtomRequests.getAtomName(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.CHANGE_PROPERTY:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                        WindowRequests.changeProperty(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.DELETE_PROPERTY:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                        WindowRequests.deleteProperty(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.GET_PROPERTY:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                        WindowRequests.getProperty(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.SET_SELECTION_OWNER:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                        SelectionRequests.setSelectionOwner(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.GET_SELECTION_OWNER:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                        SelectionRequests.getSelectionOwner(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.SEND_EVENT:
                    try (XLock lock = client.xServer.lockAll()) {
                        WindowRequests.sendEvent(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.GRAB_POINTER:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE, XServer.Lockable.CURSOR_MANAGER)) {
                        GrabRequests.grabPointer(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.UNGRAB_POINTER:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                        GrabRequests.ungrabPointer(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.QUERY_POINTER:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                        WindowRequests.queryPointer(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.TRANSLATE_COORDINATES:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                        WindowRequests.translateCoordinates(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.WARP_POINTER:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                        WindowRequests.warpPointer(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.SET_INPUT_FOCUS:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                        WindowRequests.setInputFocus(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.GET_INPUT_FOCUS:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                        WindowRequests.getInputFocus(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.QUERY_KEYMAP:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                        outputStream.writeByte(RESPONSE_CODE_SUCCESS);
                        outputStream.writeByte((byte) 0);
                        outputStream.writeShort(client.getSequenceNumber());
                        outputStream.writeInt(2);
                        outputStream.writePad(32);
                    }
                    break;
                case ClientOpcodes.OPEN_FONT:
                    FontRequests.openFont(client, inputStream, outputStream);
                    break;
                case ClientOpcodes.LIST_FONTS:
                    FontRequests.listFonts(client, inputStream, outputStream);
                    break;
                case ClientOpcodes.CREATE_PIXMAP:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
                        PixmapRequests.createPixmap(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.FREE_PIXMAP:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
                        PixmapRequests.freePixmap(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.CREATE_GC:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                        GraphicsContextRequests.createGC(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.CHANGE_GC:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                        GraphicsContextRequests.changeGC(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.COPY_GC:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                        GraphicsContextRequests.copyGC(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.SET_CLIP_RECTANGLES:
                    client.skipRequest();
                    break;
                case ClientOpcodes.FREE_GC:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                        GraphicsContextRequests.freeGC(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.COPY_AREA:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                        DrawRequests.copyArea(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.POLY_LINE:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                        DrawRequests.polyLine(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.POLY_SEGMENT:
                    client.skipRequest();
                    break;
                case ClientOpcodes.POLY_RECTANGLE:
                    client.skipRequest();
                    break;
                case ClientOpcodes.POLY_FILL_RECTANGLE:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                        DrawRequests.polyFillRectangle(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.PUT_IMAGE:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                        DrawRequests.putImage(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.GET_IMAGE:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
                        DrawRequests.getImage(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.CREATE_COLORMAP:
                    ColormapRequests.createColormap(client, inputStream, outputStream);
                    break;
                case ClientOpcodes.FREE_COLORMAP:
                    ColormapRequests.freeColormap(client, inputStream, outputStream);
                    break;
                case ClientOpcodes.CREATE_CURSOR:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.CURSOR_MANAGER)) {
                        CursorRequests.createCursor(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.CREATE_GLYPH_CURSOR:
                    client.skipRequest();
                    break;
                case ClientOpcodes.FREE_CURSOR:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.CURSOR_MANAGER)) {
                        CursorRequests.freeCursor(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.QUERY_EXTENSION:
                    ExtensionRequests.queryExtension(client, inputStream, outputStream);
                    break;
                case ClientOpcodes.GET_KEYBOARD_MAPPING:
                    try (XLock lock = client.xServer.lock(XServer.Lockable.INPUT_DEVICE)) {
                        KeyboardRequests.getKeyboardMapping(client, inputStream, outputStream);
                    }
                    break;
                case ClientOpcodes.BELL:
                    client.skipRequest();
                    break;
                case ClientOpcodes.SET_SCREEN_SAVER:
                    client.skipRequest();
                    break;
                case ClientOpcodes.GET_SCREEN_SAVER:
                    WindowRequests.getScreenSaver(client, inputStream, outputStream);
                    break;
                case ClientOpcodes.FORCE_SCREEN_SAVER:
                    client.skipRequest();
                    break;
                case ClientOpcodes.GET_MODIFIER_MAPPING:
                    KeyboardRequests.getModifierMapping(client, inputStream, outputStream);
                    break;
                case ClientOpcodes.NO_OPERATION:
                    client.skipRequest();
                    break;
                case ClientOpcodes.GET_POINTER_MAPPING:
                    CursorRequests.getPointerMaping(client, inputStream, outputStream);
                    break;
                // GrabServer and UngrabServer have no reply in X (Xlib sends them without waiting).
                case 36: // X_GrabServer
                    try (XLock lock = client.xServer.lockAll()) {
                        client.xServer.setGrabbed(true, client);
                    }
                    break;

                case 37: // X_UngrabServer
                    try (XLock lock = client.xServer.lockAll()) {
                        if (client.xServer.isGrabbedBy(client)) {
                            client.xServer.setGrabbed(false, null);
                        }
                    }
                    break;
                default:
                    Extension extension = opcode < 0 ? client.xServer.extensions.get(opcode) : null;
                    if (extension != null) extension.handleRequest(client, inputStream, outputStream);
                    else unsupportedRequest(client, opcode);
                    break;
            }
        }
        catch (XRequestError e) {
            inputStream.setActivePosition(requestEnd);
            e.sendError(client, opcode);
        }
        catch (RuntimeException e) {
            // A request that broke its handler (bad values, a missing case): drop it and go on with the
            // next one. It used to escape the X thread and end the app.
            int op = opcode & 0xff;
            if (!loggedFailed[op]) {
                loggedFailed[op] = true;
                Log.e("XClientRequestHandler", "Request with opcode " + op + " failed; skipped", e);
            }
            inputStream.setActivePosition(requestEnd);
            if (op < 128 && CORE_HAS_REPLY[op]) new BadAlloc().sendError(client, opcode);
        }

        if (inputStream.getActivePosition() != requestEnd) {
            int op = opcode & 0xff;
            if (!loggedLength[op]) {
                loggedLength[op] = true;
                Log.w("XClientRequestHandler", "Request with opcode " + op + " read "
                        + (inputStream.getActivePosition() - (requestEnd - requestLength)) + " of " + requestLength + " bytes");
            }
            inputStream.setActivePosition(requestEnd);
        }
        return true;
    }
}