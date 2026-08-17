package com.winlator.star.cast;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;

import java.nio.ByteBuffer;

/**
 * Produces one silent AAC-LC frame (ADTS-wrapped) so the cast TS has an audio track. Many HLS receivers —
 * notably the Chromecast Default Media Receiver — **stall forever on video-only streams**, buffering
 * segments but never starting playback. Muxing a silent AAC track alongside the video fixes that.
 *
 * 44100 Hz mono; one AAC frame = 1024 samples (~23.22 ms). Generated once via MediaCodec at start and
 * repeated (with advancing timestamps) by {@link TsSegmenter}.
 */
public class SilentAac {
    public static final int SAMPLE_RATE = 44100;
    public static final int CHANNELS = 1;
    public static final long FRAME_DUR_US = 1024L * 1_000_000L / SAMPLE_RATE; // ~23219 µs

    /** ADTS header + AAC payload for one silent frame; null if generation failed. */
    public final byte[] adtsFrame;

    public SilentAac() {
        byte[] f = null;
        try { f = generate(); } catch (Throwable ignored) {}
        adtsFrame = f;
    }

    private static byte[] generate() throws Exception {
        MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS);
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 64000);
        MediaCodec enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        enc.start();
        byte[] out = null;
        try {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int inIdx = enc.dequeueInputBuffer(200000);
            if (inIdx >= 0) {
                ByteBuffer in = enc.getInputBuffer(inIdx);
                in.clear();
                in.put(new byte[1024 * 2 * CHANNELS]);   // 1024 samples of 16-bit silence
                enc.queueInputBuffer(inIdx, 0, 1024 * 2 * CHANNELS, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
            long deadline = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < deadline) {
                int idx = enc.dequeueOutputBuffer(info, 50000);
                if (idx >= 0) {
                    boolean cfg = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (!cfg && info.size > 0) {
                        ByteBuffer b = enc.getOutputBuffer(idx);
                        byte[] aac = new byte[info.size];
                        b.position(info.offset); b.get(aac, 0, info.size);
                        out = withAdts(aac);
                        enc.releaseOutputBuffer(idx, false);
                        break;
                    }
                    enc.releaseOutputBuffer(idx, false);
                }
            }
        } finally {
            try { enc.stop(); } catch (Exception ignored) {}
            enc.release();
        }
        return out;
    }

    // Prefix a 7-byte ADTS header (AAC-LC, 44100 Hz, mono) so the TS carries stream_type 0x0F (ADTS AAC).
    private static byte[] withAdts(byte[] aac) {
        int len = aac.length + 7;
        int freqIdx = 4;            // 44100 Hz
        int chanCfg = CHANNELS;     // 1
        byte[] p = new byte[len];
        p[0] = (byte) 0xFF;
        p[1] = (byte) 0xF1;                                     // MPEG-4, layer 0, no CRC
        p[2] = (byte) ((1 << 6) | (freqIdx << 2) | ((chanCfg >> 2) & 1)); // profile AAC-LC(1) | freq | chan hi
        p[3] = (byte) (((chanCfg & 3) << 6) | ((len >> 11) & 3));
        p[4] = (byte) ((len >> 3) & 0xFF);
        p[5] = (byte) (((len & 7) << 5) | 0x1F);
        p[6] = (byte) 0xFC;
        System.arraycopy(aac, 0, p, 7, aac.length);
        return p;
    }
}
