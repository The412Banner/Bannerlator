package com.winlator.star.cast;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal live HLS segmenter: turns the H.264 elementary stream from MediaCodec into MPEG-TS (.ts)
 * segments + a rolling live .m3u8 playlist, held in memory for {@link HttpFileServer}-style serving.
 *
 * Android's MediaMuxer can't emit MPEG-TS, so this hand-rolls just enough of it for a Chromecast to
 * play: PAT + PMT (H.264 = stream type 0x1B) at the top of every segment, PES-wrapped access units
 * with 90 kHz PTS, PCR in the video PID's adaptation field, and 188-byte TS packet alignment. Segments
 * start on IDR keyframes (encoder set to ~2 s GOP), and SPS/PPS is prepended to each keyframe so every
 * segment is independently decodable. Only the last few segments are kept (live window).
 */
public class TsSegmenter {
    private static final int PID_PAT = 0x0000, PID_PMT = 0x1000, PID_VIDEO = 0x0100, PID_AUDIO = 0x0101;
    private static final int TARGET_MS = 1500;      // close a segment on the first keyframe past this
    private static final int WINDOW = 5;            // segments kept in the live window

    private byte[] codecConfig;                     // SPS+PPS in Annex-B
    private ByteArrayOutputStream seg;              // current segment being built
    private long segStartPtsUs = -1;
    private long firstPtsUs = -1;                   // normalize all timestamps to start at 0
    private int ccPat = 0, ccPmt = 0, ccVideo = 0, ccAudio = 0;
    private int mediaSeq = 0;                        // EXT-X-MEDIA-SEQUENCE of the oldest segment
    private int segIndex = 0;                        // monotonically increasing segment id

    // Silent AAC audio track (Chromecast stalls on video-only). audioPtsUs walks the timeline.
    private byte[] silentAac;
    private long audioFrameDurUs;
    private long audioPtsUs = 0;

    // name -> bytes, insertion-ordered so the oldest is first.
    private final LinkedHashMap<String, byte[]> segments = new LinkedHashMap<>();
    private final ArrayDeque<String> order = new ArrayDeque<>();
    private final ArrayDeque<Double> durations = new ArrayDeque<>();

    /** Codec config (BUFFER_FLAG_CODEC_CONFIG output) — SPS/PPS in Annex-B. */
    public synchronized void setCodecConfig(byte[] cfg) { codecConfig = cfg; }

    /** Provide the silent AAC frame (ADTS) + its duration to add an audio track. */
    public synchronized void setSilentAac(byte[] frame, long frameDurUs) {
        this.silentAac = frame; this.audioFrameDurUs = frameDurUs;
    }

    /** Feed one access unit (Annex-B, from MediaCodec) with its PTS (µs) and whether it's a keyframe. */
    public synchronized void feed(byte[] au, long ptsUs, boolean keyframe) {
        if (firstPtsUs < 0) firstPtsUs = ptsUs;
        long pts = ptsUs - firstPtsUs;              // normalize to start at 0

        if (keyframe && seg != null && (pts - segStartPtsUs) >= TARGET_MS * 1000L) closeSegment(pts);
        if (seg == null) { if (!keyframe) return; openSegment(pts); }

        // Interleave silent audio up to this video PTS so the audio timeline keeps pace.
        if (silentAac != null && audioFrameDurUs > 0) {
            while (audioPtsUs <= pts) {
                writePesTo(PID_AUDIO, 0xC0, silentAac, audioPtsUs, false, true);
                audioPtsUs += audioFrameDurUs;
            }
        }

        // Prepend an Access Unit Delimiter (NAL type 9) — MPEG-TS/H.264 wants one per access unit and
        // strict HW decoders (Chromecast) can refuse to start without it. Keyframe order: AUD, SPS/PPS, IDR.
        byte[] aud = new byte[]{0, 0, 0, 1, 0x09, (byte) 0xF0};
        byte[] payload = (keyframe && codecConfig != null)
                ? concat(aud, concat(codecConfig, au))
                : concat(aud, au);
        // PCR on EVERY video frame (~33 ms) — the receiver needs a frequent clock or it never starts.
        writePesTo(PID_VIDEO, 0xE0, payload, pts, true, false);
    }

    private void openSegment(long ptsUs) {
        seg = new ByteArrayOutputStream();
        segStartPtsUs = ptsUs;
        // Do NOT reset continuity counters per segment — players read segments back-to-back and a CC
        // reset at each boundary reads as a corrupt/lost packet (which is why the Chromecast bailed).
        writeTs(PID_PAT, true, buildPat(), -1);
        writeTs(PID_PMT, true, buildPmt(), -1);
    }

    private void closeSegment(long endPtsUs) {
        if (seg == null) return;
        double dur = Math.max(0.1, (endPtsUs - segStartPtsUs) / 1_000_000.0);
        String name = "seg" + segIndex + ".ts";
        segments.put(name, seg.toByteArray());
        order.addLast(name);
        durations.addLast(dur);
        segIndex++;
        while (order.size() > WINDOW) {
            String old = order.pollFirst();
            segments.remove(old);
            durations.pollFirst();
            mediaSeq++;
        }
        seg = null;
    }

    public synchronized byte[] getSegment(String name) { return segments.get(name); }

    /** The live playlist. Empty until the first segment closes. */
    public synchronized String playlist() {
        StringBuilder sb = new StringBuilder();
        sb.append("#EXTM3U\n#EXT-X-VERSION:3\n");
        // TARGETDURATION must be >= the longest segment or the receiver rejects the playlist.
        double maxDur = 3;
        for (Double d : durations) maxDur = Math.max(maxDur, d);
        sb.append("#EXT-X-TARGETDURATION:").append((int) Math.ceil(maxDur)).append('\n');
        sb.append("#EXT-X-MEDIA-SEQUENCE:").append(mediaSeq).append('\n');
        java.util.Iterator<String> it = order.iterator();
        java.util.Iterator<Double> dit = durations.iterator();
        while (it.hasNext()) {
            sb.append(String.format(Locale.US, "#EXTINF:%.3f,\n", dit.next()));
            sb.append(it.next()).append('\n');
        }
        return sb.toString();
    }

    public synchronized boolean hasSegments() { return !order.isEmpty(); }

    // ---- MPEG-TS building -----------------------------------------------------------------------

    // PES-wrap an access unit and split it across 188-byte TS packets on a PID. Each packet is a fixed
    // 188-byte array with an EXACTLY-sized adaptation field, so payload always advances (no runaway loop).
    private void writePesTo(int pid, int streamId, byte[] au, long ptsUs, boolean withPcr, boolean audio) {
        long pts = ptsUs * 9 / 100;                 // µs -> 90 kHz
        byte[] data = buildPes(streamId, au, pts, audio);

        int offset = 0; boolean first = true;
        while (offset < data.length) {
            int remaining = data.length - offset;
            boolean pcr = first && withPcr;
            int payloadLen, adaptLen;
            if (remaining >= 184 && !pcr) {
                payloadLen = 184; adaptLen = 0;     // full payload, no adaptation field
            } else {
                int minAdapt = pcr ? 8 : 1;         // len+flags+6(PCR) ; or just the length byte for stuffing
                payloadLen = Math.min(remaining, 184 - minAdapt);
                adaptLen = 184 - payloadLen;         // adaptation field fills the rest of the 184
            }

            byte[] pkt = new byte[188];
            pkt[0] = 0x47;
            int afc = (adaptLen > 0) ? 0b11 : 0b01;
            pkt[1] = (byte) ((first ? 0x40 : 0x00) | ((pid >> 8) & 0x1F));
            pkt[2] = (byte) (pid & 0xFF);
            int cc = audio ? ccAudio : ccVideo;
            pkt[3] = (byte) ((afc << 4) | (cc & 0x0F));
            if (audio) ccAudio = (ccAudio + 1) & 0x0F; else ccVideo = (ccVideo + 1) & 0x0F;

            int pos = 4;
            if (adaptLen > 0) {
                pkt[pos++] = (byte) (adaptLen - 1); // adaptation_field_length
                if (adaptLen >= 2) {
                    pkt[pos++] = (byte) (pcr ? 0x10 : 0x00);   // flags
                    if (pcr) {
                        long b = pts;               // reuse PTS as PCR base (90 kHz)
                        pkt[pos++] = (byte) ((b >> 25) & 0xFF);
                        pkt[pos++] = (byte) ((b >> 17) & 0xFF);
                        pkt[pos++] = (byte) ((b >> 9) & 0xFF);
                        pkt[pos++] = (byte) ((b >> 1) & 0xFF);
                        pkt[pos++] = (byte) (((b & 0x1) << 7) | 0x7E);
                        pkt[pos++] = 0x00;
                    }
                    while (pos < 4 + adaptLen) pkt[pos++] = (byte) 0xFF;  // stuffing
                }
            }
            System.arraycopy(data, offset, pkt, pos, payloadLen);   // pos == 4 + adaptLen
            offset += payloadLen;
            seg.write(pkt, 0, 188);
            first = false;
        }
    }

    private byte[] buildPes(int streamId, byte[] au, long pts, boolean audio) {
        ByteArrayOutputStream pes = new ByteArrayOutputStream();
        pes.write(0x00); pes.write(0x00); pes.write(0x01); pes.write(streamId);
        if (audio) {
            int pesLen = 3 + 5 + au.length;         // opt-hdr(3) + PTS(5) + payload
            pes.write((pesLen >> 8) & 0xFF); pes.write(pesLen & 0xFF);
            pes.write(0x80);                        // marker
            pes.write(0x80);                        // PTS only
            pes.write(0x05);                        // PES header data length
            writePts(pes, 0x02, pts);
        } else {
            // Video: write BOTH PTS and DTS (equal — no B-frames). Strict HW decoders need DTS to set up
            // their decode pipeline; PTS-only stalls them. length 0 = unbounded (allowed for video).
            pes.write(0x00); pes.write(0x00);
            pes.write(0x80);                        // marker
            pes.write(0xC0);                        // PTS + DTS present
            pes.write(0x0A);                        // PES header data length (5 PTS + 5 DTS)
            writePts(pes, 0x03, pts);               // PTS (prefix 0011)
            writePts(pes, 0x01, pts);               // DTS (prefix 0001) = PTS
        }
        pes.write(au, 0, au.length);
        return pes.toByteArray();
    }

    private void writeTs(int pid, boolean pusi, byte[] payload, long pcr) {
        // section-style payload (PAT/PMT): pointer_field 0 then the table.
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        p.write(0x00);                              // pointer_field
        p.write(payload, 0, payload.length);
        byte[] data = p.toByteArray();
        int cc = (pid == PID_PAT) ? (ccPat = (ccPat + 1) & 0x0F) : (ccPmt = (ccPmt + 1) & 0x0F);
        byte[] pkt = new byte[188];
        java.util.Arrays.fill(pkt, (byte) 0xFF);
        pkt[0] = 0x47;
        pkt[1] = (byte) ((pusi ? 0x40 : 0x00) | ((pid >> 8) & 0x1F));
        pkt[2] = (byte) (pid & 0xFF);
        pkt[3] = (byte) ((0b01 << 4) | (cc & 0x0F)); // payload only
        System.arraycopy(data, 0, pkt, 4, Math.min(data.length, 184));
        seg.write(pkt, 0, 188);
    }

    private byte[] buildPat() {
        // PAT: one program (1) -> PMT PID.
        byte[] section = new byte[]{
                0x00,                               // table_id PAT
                (byte) 0xB0, 0x0D,                  // section_syntax=1, length=13
                0x00, 0x01,                         // transport_stream_id
                (byte) 0xC1,                        // version/current_next
                0x00, 0x00,                         // section/last section
                0x00, 0x01,                         // program_number 1
                (byte) (0xE0 | ((PID_PMT >> 8) & 0x1F)), (byte) (PID_PMT & 0xFF),
                0, 0, 0, 0                           // CRC placeholder
        };
        return withCrc(section, section.length - 4);
    }

    private byte[] buildPmt() {
        byte[] section = new byte[]{
                0x02,                               // table_id PMT
                (byte) 0xB0, 0x17,                  // section_length = 23 (with the audio ES entry)
                0x00, 0x01,                         // program_number
                (byte) 0xC1, 0x00, 0x00,
                (byte) (0xE0 | ((PID_VIDEO >> 8) & 0x1F)), (byte) (PID_VIDEO & 0xFF), // PCR PID = video
                (byte) 0xF0, 0x00,                  // program_info_length 0
                0x1B,                               // stream_type H.264
                (byte) (0xE0 | ((PID_VIDEO >> 8) & 0x1F)), (byte) (PID_VIDEO & 0xFF),
                (byte) 0xF0, 0x00,                  // ES_info_length 0
                0x0F,                               // stream_type AAC (ADTS)
                (byte) (0xE0 | ((PID_AUDIO >> 8) & 0x1F)), (byte) (PID_AUDIO & 0xFF),
                (byte) 0xF0, 0x00,                  // ES_info_length 0
                0, 0, 0, 0                           // CRC placeholder
        };
        return withCrc(section, section.length - 4);
    }

    private static void writePts(ByteArrayOutputStream o, int flag, long pts) {
        o.write((flag << 4) | (int) (((pts >> 30) & 0x07) << 1) | 0x01);
        o.write((int) ((pts >> 22) & 0xFF));
        o.write((int) (((pts >> 15) & 0x7F) << 1) | 0x01);
        o.write((int) ((pts >> 7) & 0xFF));
        o.write((int) (((pts & 0x7F) << 1) | 0x01));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    // MPEG-2 systems CRC32 over the section (excluding the 4 CRC bytes), written big-endian.
    private static byte[] withCrc(byte[] section, int len) {
        int crc = 0xFFFFFFFF;
        for (int i = 0; i < len; i++) {
            crc ^= (section[i] & 0xFF) << 24;
            for (int j = 0; j < 8; j++)
                crc = ((crc & 0x80000000) != 0) ? (crc << 1) ^ 0x04C11DB7 : (crc << 1);
        }
        section[len]     = (byte) ((crc >> 24) & 0xFF);
        section[len + 1] = (byte) ((crc >> 16) & 0xFF);
        section[len + 2] = (byte) ((crc >> 8) & 0xFF);
        section[len + 3] = (byte) (crc & 0xFF);
        return section;
    }
}
