package com.winlator.star.core;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * App side of the container's Android requests (Winlator protocol, loopback port 20000): Wine's
 * winebrowser sends OPEN_URL when WINE_OPEN_WITH_ANDROID_BROWSER=1 ("Open links in the Android
 * browser"), the clipboard requests come with WINE_FROM/TO_ANDROID_CLIPBOARD. Each message is
 * int request code, then the request's own fields (big-endian ints, bytes), one per connection.
 *
 * Bound to 127.0.0.1 only: the container reaches it over loopback, nothing on the network can.
 * Only http, https and mailto links are opened.
 */
public class WineRequestHandler {
    private static final String TAG = "WineRequestHandler";
    private static final int PORT = 20000;
    private static final int MAX_MESSAGE = 64 * 1024;

    abstract static class RequestCodes {
        static final int OPEN_URL = 1;
        static final int GET_WINE_CLIPBOARD = 2;
        static final int SET_WINE_CLIPBAORD = 3;
    }

    private final Context context;
    private volatile ServerSocket serverSocket;
    private volatile boolean running;

    public WineRequestHandler(Context context) {
        this.context = context.getApplicationContext();
    }

    public void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(this::acceptLoop, "wine-request-handler");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        ServerSocket s = serverSocket;
        serverSocket = null;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    private void acceptLoop() {
        try (ServerSocket server = new ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))) {
            serverSocket = server;
            Log.i(TAG, "listening on 127.0.0.1:" + PORT);
            while (running) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    handleRequest(in, out, in.readInt());
                    out.flush();
                } catch (IOException | RuntimeException e) {
                    if (running) Log.w(TAG, "request failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            if (running) Log.w(TAG, "cannot listen on 127.0.0.1:" + PORT + ": " + e.getMessage());
        } finally {
            running = false;
            serverSocket = null;
        }
    }

    public void handleRequest(DataInputStream inputStream, DataOutputStream outputStream, int requestCode) throws IOException {
        switch (requestCode) {
            case RequestCodes.OPEN_URL:
                openURL(inputStream);
                break;
            case RequestCodes.GET_WINE_CLIPBOARD:
                getWineClipboard(inputStream);
                break;
            case RequestCodes.SET_WINE_CLIPBAORD:
                setWineClipboard(outputStream);
                break;
            default:
                Log.w(TAG, "unknown request code " + requestCode);
        }
    }

    private static byte[] readSized(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > MAX_MESSAGE) throw new IOException("bad size " + size);
        byte[] data = new byte[size];
        in.readFully(data);
        return data;
    }

    private void openURL(DataInputStream inputStream) throws IOException {
        String url = new String(readSized(inputStream), StandardCharsets.UTF_8).trim();
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") && !scheme.equals("mailto")) {
            Log.w(TAG, "OPEN_URL refused: scheme \"" + scheme + "\"");
            return;
        }
        // Scheme and host only: links can carry sign-in tokens.
        Log.i(TAG, "OPEN_URL " + scheme + "://" + (uri.getHost() == null ? "" : uri.getHost()));
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "no app opens " + scheme + " links");
        }
    }

    private void getWineClipboard(DataInputStream inputStream) throws IOException {
        int format = inputStream.readInt();
        byte[] data = readSized(inputStream);
        if (format == 13) {
            String text = new String(data, StandardCharsets.UTF_16LE).replace("\0", "");
            ClipboardManager clpm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            clpm.setPrimaryClip(ClipData.newPlainText("", text));
        }
        Log.d(TAG, "GET_WINE_CLIPBOARD format " + format + " size " + data.length);
    }

    private void setWineClipboard(DataOutputStream outputStream) throws IOException {
        int format = 13;
        ClipboardManager clipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clipData = clipboardManager.getPrimaryClip();
        CharSequence text = clipData != null && clipData.getItemCount() > 0 ? clipData.getItemAt(0).getText() : null;
        String clipText = (text != null ? text.toString() : "") + "\0";
        byte[] dataByte = clipText.getBytes(StandardCharsets.UTF_16LE);
        Log.d(TAG, "SET_WINE_CLIPBOARD size " + dataByte.length);
        outputStream.writeInt(format);
        outputStream.writeInt(dataByte.length);
        outputStream.write(dataByte);
    }
}
