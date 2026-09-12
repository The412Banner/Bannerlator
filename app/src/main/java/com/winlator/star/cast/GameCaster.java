package com.winlator.star.cast;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.nio.ByteBuffer;

/**
 * Version B — Part 2, Step 1: capture the game and hardware-encode it.
 *
 * We render the game onto a private {@link VirtualDisplay} whose {@link Surface} is a MediaCodec H.264
 * encoder's input (so the GPU feeds the encoder directly — no readback), the same reparent-to-a-
 * Presentation trick Version A uses for a real TV. This needs NO permissions (a VIRTUAL_DISPLAY_FLAG_
 * OWN_CONTENT_ONLY display renders only the Presentation we put on it), and no MediaProjection / media-
 * projection foreground service.
 *
 * Step 1 muxes the encoded stream to an .mp4 FILE so we can verify capture+encode works under the
 * emulator before adding the network + Cast half (Step 2 swaps the muxer for an HTTP/HLS server that a
 * Chromecast plays). The caller must disable the wired-display controller while this runs so the two
 * don't fight over the game view's parent.
 */
public class GameCaster {
    private static final String TAG = "GameCaster";
    private static final String MIME = "video/avc";

    public interface Listener { void onState(String state, String detail); }

    private final Activity activity;
    private final View gameView;          // XServerView
    private final ViewGroup internalHost; // on-phone host to return the game to
    private final Listener listener;
    private final DisplayManager displayManager;

    private MediaCodec encoder;
    private Surface inputSurface;
    private VirtualDisplay virtualDisplay;
    private CastPresentation presentation;
    private MediaMuxer muxer;
    private TsSegmenter streamSink;       // set for live-stream (HLS) mode instead of the file muxer
    private Thread drainThread;
    private volatile boolean running = false;
    private int trackIndex = -1;
    private String outputPath;

    public GameCaster(Activity activity, View gameView, ViewGroup internalHost, Listener listener) {
        this.activity = activity;
        this.gameView = gameView;
        this.internalHost = internalHost;
        this.listener = listener;
        this.displayManager = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
    }

    public boolean isRunning() { return running; }

    /** Start capturing at w×h and muxing to outPath (.mp4). Runs on the main thread; the encoder drains
     *  on its own thread. Returns false on setup failure (nothing is left running). */
    public boolean start(int w, int h, int bitrate, String outPath) {
        if (running) return true;
        // Encoders want even dimensions.
        w &= ~1; h &= ~1;
        this.outputPath = outPath;
        try {
            MediaFormat fmt = MediaFormat.createVideoFormat(MIME, w, h);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            encoder = MediaCodec.createEncoderByType(MIME);
            encoder.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Log.i(TAG, "step: encoder configured " + w + "x" + h);
            inputSurface = encoder.createInputSurface();
            encoder.start();
            Log.i(TAG, "step: encoder started + input surface");

            muxer = new MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            int dpi = activity.getResources().getDisplayMetrics().densityDpi;
            virtualDisplay = displayManager.createVirtualDisplay(
                    "BannerlatorCast", w, h, dpi, inputSurface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                            | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);
            if (virtualDisplay == null) {
                Log.e(TAG, "createVirtualDisplay returned null");
                cleanup();
                notifyState("FAILED", "Couldn't create the capture display.");
                return false;
            }
            Log.i(TAG, "step: virtual display created");

            presentation = new CastPresentation(activity, virtualDisplay.getDisplay());
            presentation.show();
            Log.i(TAG, "step: presentation shown");
            moveGameTo(presentation.getRoot());
            Log.i(TAG, "step: game reparented to cast display");

            running = true;
            drainThread = new Thread(this::drainLoop, "cast-encoder-drain");
            drainThread.start();
            notifyState("STREAMING", "Capturing the game.");
            Log.i(TAG, "step: STREAMING notified");
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "start failed", e);
            cleanup();
            notifyState("FAILED", "Couldn't start capture: " + e.getMessage());
            return false;
        }
    }

    /** Start a LIVE capture (Step 2b): feed the encoder's H.264 straight into the HLS segmenter instead
     *  of a file muxer. Same private-VirtualDisplay + reparent path as {@link #start}. */
    public boolean startStream(int w, int h, int bitrate, TsSegmenter sink) {
        if (running) return true;
        w &= ~1; h &= ~1;
        this.streamSink = sink;
        // Give the stream a silent AAC track — Chromecast stalls on video-only HLS.
        try {
            SilentAac aac = new SilentAac();
            if (aac.adtsFrame != null) sink.setSilentAac(aac.adtsFrame, SilentAac.FRAME_DUR_US);
        } catch (Throwable ignored) {}
        try {
            MediaFormat fmt = MediaFormat.createVideoFormat(MIME, w, h);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10);  // GOP fallback; forced sync frames drive segments
            // Baseline profile = most universally HW-decodable (Chromecast) + no B-frames.
            fmt.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            fmt.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31);
            if (android.os.Build.VERSION.SDK_INT >= 30) fmt.setInteger(MediaFormat.KEY_LATENCY, 1);
            encoder = MediaCodec.createEncoderByType(MIME);
            encoder.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = encoder.createInputSurface();
            encoder.start();
            int dpi = activity.getResources().getDisplayMetrics().densityDpi;
            virtualDisplay = displayManager.createVirtualDisplay("BannerlatorCast", w, h, dpi, inputSurface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);
            if (virtualDisplay == null) { cleanup(); notifyState("FAILED", "Couldn't create the capture display."); return false; }
            presentation = new CastPresentation(activity, virtualDisplay.getDisplay());
            presentation.show();
            moveGameTo(presentation.getRoot());
            running = true;
            drainThread = new Thread(this::drainStreamLoop, "cast-stream-drain");
            drainThread.start();
            notifyState("STREAMING", "Streaming the game.");
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "startStream failed", e);
            cleanup();
            notifyState("FAILED", "Couldn't start streaming: " + e.getMessage());
            return false;
        }
    }

    private void drainStreamLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long lastKeyMs = System.currentTimeMillis();
        try {
            while (running) {
                // Force a keyframe every ~2s so HLS segments stay ~2s (don't rely on the encoder GOP).
                long now = System.currentTimeMillis();
                if (now - lastKeyMs >= 2000) {
                    try {
                        android.os.Bundle b = new android.os.Bundle();
                        b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                        encoder.setParameters(b);
                    } catch (Exception ignored) {}
                    lastKeyMs = now;
                }
                int idx = encoder.dequeueOutputBuffer(info, 10000);
                if (idx >= 0) {
                    ByteBuffer buf = encoder.getOutputBuffer(idx);
                    if (buf != null && info.size > 0) {
                        byte[] data = new byte[info.size];
                        buf.position(info.offset);
                        buf.get(data, 0, info.size);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            streamSink.setCodecConfig(data);
                        } else {
                            boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                            streamSink.feed(data, info.presentationTimeUs, key);
                        }
                    }
                    encoder.releaseOutputBuffer(idx, false);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "stream drain ended", e);
        }
    }

    /** Stop capture, finalize the file, and return the game to the phone. */
    public void stop() {
        if (!running && encoder == null) return;
        running = false;
        try { if (drainThread != null) drainThread.join(1500); } catch (InterruptedException ignored) {}
        drainThread = null;
        moveGameTo(internalHost);
        cleanup();
        notifyState("IDLE", outputPath != null ? "Saved: " + outputPath : "");
    }

    private void drainLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean muxerStarted = false;
        try {
            while (running) {
                int idx = encoder.dequeueOutputBuffer(info, 10000);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    trackIndex = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                } else if (idx >= 0) {
                    ByteBuffer buf = encoder.getOutputBuffer(idx);
                    if (buf != null && muxerStarted && info.size > 0
                            && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        muxer.writeSampleData(trackIndex, buf, info);
                    }
                    encoder.releaseOutputBuffer(idx, false);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "drain loop ended", e);
        }
    }

    private void cleanup() {
        try { if (encoder != null) { encoder.stop(); encoder.release(); } } catch (Exception ignored) {}
        try { if (muxer != null) { muxer.stop(); muxer.release(); } } catch (Exception ignored) {}
        try { if (presentation != null) presentation.dismiss(); } catch (Exception ignored) {}
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) {}
        try { if (inputSurface != null) inputSurface.release(); } catch (Exception ignored) {}
        encoder = null; muxer = null; presentation = null; virtualDisplay = null; inputSurface = null;
        trackIndex = -1;
    }

    private void moveGameTo(ViewGroup target) {
        activity.runOnUiThread(() -> {
            ViewGroup parent = (ViewGroup) gameView.getParent();
            if (parent == target) return;
            if (parent != null) parent.removeView(gameView);
            gameView.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            target.addView(gameView, 0);
        });
    }

    private void notifyState(String state, String detail) {
        if (listener != null) activity.runOnUiThread(() -> listener.onState(state, detail));
    }

    /** Full-screen black Presentation on the cast VirtualDisplay we reparent the game into. */
    private static class CastPresentation extends Presentation {
        private FrameLayout root;
        CastPresentation(Context ctx, Display display) { super(ctx, display); }
        FrameLayout getRoot() { return root; }
        @Override protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            root = new FrameLayout(getContext());
            root.setBackgroundColor(0xFF000000);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
            setContentView(root);
        }
    }
}
