package com.winlator.star.core;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Idempotent runtime byte-patch for winebus.so ("TideGear #91 / preload-free winebus
 * duration patch").
 *
 * <p>SDL2's rumble APIs auto-expire the effect after the caller-supplied duration_ms.
 * winebus' {@code sdl_device_haptics_start} passes a real (short) duration when it drives
 * {@code SDL_JoystickRumble} / {@code SDL_JoystickRumbleTriggers} through the SDL2 pointers
 * it {@code dlsym}'d, so a held rumble dies after ~1-3s. This forces the 4th integer arg
 * (duration_ms: {@code w3} on aarch64 / {@code ecx} on x86_64 SysV) of those two indirect
 * calls to 0xffffffff (-1) so SDL2 never auto-stops it.
 *
 * <h3>aarch64-unix (arm64ec Proton)</h3>
 * <p><b>Stable shape.</b> At both rumble call sites the compiler sets the 4th arg with
 * {@code mov w3,wN} (ORR-shifted-reg, encoding {@code E3 03 Nx 2A} where {@code Nx} carries
 * the source register) immediately before the indirect {@code blr x8} ({@code 00 01 3F D6}).
 * We rewrite {@code mov w3,wN} -> {@code mov w3,#-1} ({@code movn w3,#0} = {@code 03 00 80
 * 12}), leaving {@code blr x8} intact. {@code mov w3,wzr} ({@code Nx = 0x1f}) is the
 * zero-duration / stop shape and is NEVER matched.
 * <pre>
 *   Proton 9.0 arm64ec (bundled, 220176 B): mov w3,w20  E3 03 14 2A 00 01 3F D6  x2
 *   Proton 10.0-x arm64ec (content pack):   mov w3,w19  E3 03 13 2A 00 01 3F D6  x2
 *   Proton 11.0-x arm64ec (content pack):   mov w3,w19  E3 03 13 2A 00 01 3F D6  x2
 *   patched (all):                          mov w3,#-1  03 00 80 12 00 01 3F D6
 * </pre>
 * Structural fallback (build-agnostic): masked {@code mov w3,w<0..30> ; blr x8}
 * (source reg wildcarded, wzr excluded). Yields exactly 2 sites on P9/P10/P11.
 *
 * <h3>x86_64-unix (Box64 / Wine x86_64)</h3>
 * <p>CODE-DERIVED against Wine 10.0 x86_64 winebus.so (78504 B, content pack
 * {@code Wine/10.0-X86_64-1}). System V ABI -> 4th int arg = {@code ecx}. At BOTH rumble
 * sites inside {@code sdl_device_haptics_start} (this is a {@code -O0} build):
 * <pre>
 *   8B 4D E4        mov   ecx, [rbp-0x1c]   ; duration_ms (4th arg)   <-- target (3 bytes)
 *   0F B7 F6        movzwl si, esi
 *   0F B7 D2        movzwl dx, edx
 *   FF D0           call  *rax              ; indirect SDL_JoystickRumble[Triggers]
 * </pre>
 * The 11-byte window matches EXACTLY the 2 rumble sites; the distinctive
 * {@code movzwl si;movzwl dx;call *rax} suffix ({@code 0F B7 F6 0F B7 D2 FF D0}) occurs
 * only there. We replace {@code mov ecx,[rbp-0x1c]} ({@code 8B 4D E4}) with
 * {@code or ecx,-1} ({@code 83 C9 FF}) so ecx becomes 0xffffffff; the suffix / call are
 * preserved. The zero-duration {@code sdl_device_haptics_stop} materializes ecx with
 * {@code xor ecx,ecx} ({@code 31 C9}) instead, so it is NOT matched and stays untouched.
 * <pre>
 *   Wine 10.0 x86_64: mov ecx,[rbp-0x1c]  8B 4D E4 0F B7 F6 0F B7 D2 FF D0  x2
 *   patched:          or  ecx,-1          83 C9 FF 0F B7 F6 0F B7 D2 FF D0
 * </pre>
 * Structural fallback: masked {@code mov ecx,[rbp+disp8]} ({@code 8B 4D ??}) followed by
 * that exact suffix (disp8 wildcarded, to survive a stack-slot shift in another build).
 * Matches exactly 2 sites in this binary; the suffix's specificity keeps it from
 * over-matching.
 *
 * <p><b>Residual risk (accepted).</b> The structural fallbacks could in principle
 * mis-identify a build containing exactly two unrelated sequences of the same shape. Exact
 * patterns are tried first (and short-circuit) to avoid this on known builds, and the
 * {@code == 2} guard is the mitigation on unknown ones. Everything is idempotent and
 * non-destructive: once patched the sites read the patched window and re-running is a
 * no-op; any ambiguous count logs and changes nothing (never a partial patch).
 */
public final class WinebusRumblePatcher {
    private static final String TAG = "Evshim";

    private static final int EXPECTED = 2;

    private WinebusRumblePatcher() {}

    /** An exact, build-specific fingerprint window and its patched replacement (equal length). */
    private static final class Sig {
        final String name;
        final byte[] original;
        final byte[] patched;
        Sig(String name, int[] original, int[] patched) {
            this.name = name;
            this.original = bytes(original);
            this.patched = bytes(patched);
            if (this.original.length != this.patched.length) {
                throw new IllegalArgumentException("sig length mismatch: " + name);
            }
        }
    }

    /** Locates the duration-load sites structurally (build-agnostic) for one arch. */
    private interface Structural {
        List<Integer> find(byte[] data);
    }

    /** Everything needed to patch one arch: exact patterns + the canonical patched window. */
    private static final class Plan {
        final List<Sig> exact = new ArrayList<>();
        final byte[] patchedWindow;   // canonical patched bytes (idempotency scan + structural write)
        final Structural structural;  // nullable
        Plan(byte[] patchedWindow, Structural structural) {
            this.patchedWindow = patchedWindow;
            this.structural = structural;
        }
    }

    // --- aarch64 constants ---------------------------------------------------------------
    private static final byte[] A64_BLR_X8 = bytes(new int[]{0x00, 0x01, 0x3f, 0xd6});
    private static final byte[] A64_PATCHED = concat(bytes(new int[]{0x03, 0x00, 0x80, 0x12}), A64_BLR_X8);

    // --- x86_64 constants ----------------------------------------------------------------
    // movzwl si,esi ; movzwl dx,edx ; call *rax   (the distinctive rumble-arg suffix)
    private static final byte[] X64_SUFFIX = bytes(new int[]{0x0f, 0xb7, 0xf6, 0x0f, 0xb7, 0xd2, 0xff, 0xd0});
    // or ecx,-1 ; <suffix>
    private static final byte[] X64_PATCHED = concat(bytes(new int[]{0x83, 0xc9, 0xff}), X64_SUFFIX);

    /** Build the per-arch patch plan, or {@code null} for an arch we have no verified pattern for. */
    private static Plan planFor(String archDir) {
        if ("aarch64-unix".equals(archDir)) {
            Plan p = new Plan(A64_PATCHED, WinebusRumblePatcher::findAarch64Sites);
            // Proton 10 / 11 arm64ec (content packs): mov w3,w19
            p.exact.add(new Sig("Proton 10/11 (mov w3,w19)",
                    new int[]{0xe3, 0x03, 0x13, 0x2a, 0x00, 0x01, 0x3f, 0xd6},
                    new int[]{0x03, 0x00, 0x80, 0x12, 0x00, 0x01, 0x3f, 0xd6}));
            // Proton 9.0 arm64ec (bundled): mov w3,w20
            p.exact.add(new Sig("Proton 9.0 (mov w3,w20)",
                    new int[]{0xe3, 0x03, 0x14, 0x2a, 0x00, 0x01, 0x3f, 0xd6},
                    new int[]{0x03, 0x00, 0x80, 0x12, 0x00, 0x01, 0x3f, 0xd6}));
            return p;
        }
        if ("x86_64-unix".equals(archDir)) {
            Plan p = new Plan(X64_PATCHED, WinebusRumblePatcher::findX86_64Sites);
            // Wine 10.0 x86_64 (content pack): mov ecx,[rbp-0x1c] ; movzwl si ; movzwl dx ; call *rax
            p.exact.add(new Sig("Wine 10.0 x86_64 (mov ecx,[rbp-0x1c])",
                    new int[]{0x8b, 0x4d, 0xe4, 0x0f, 0xb7, 0xf6, 0x0f, 0xb7, 0xd2, 0xff, 0xd0},
                    new int[]{0x83, 0xc9, 0xff, 0x0f, 0xb7, 0xf6, 0x0f, 0xb7, 0xd2, 0xff, 0xd0}));
            return p;
        }
        return null;
    }

    /**
     * Force the SDL rumble duration to never-expire in {@code winebus}. Tries the exact
     * per-build patterns first, then a build-agnostic structural fallback. Idempotent and
     * non-destructive; only ever patches when EXACTLY 2 sites are identified.
     */
    public static void patchDuration(File winebus, String archDir) {
        Plan plan = planFor(archDir);
        if (plan == null) {
            Log.i(TAG, "rumble: no verified duration pattern for " + archDir + ", skipping");
            return;
        }
        if (winebus == null || !winebus.exists() || winebus.length() <= 0) {
            Log.w(TAG, "rumble: winebus missing, skipping: " + winebus);
            return;
        }

        final byte[] data;
        try {
            data = readAll(winebus);
        } catch (IOException e) {
            Log.w(TAG, "rumble: read failed, skipping: " + e);
            return;
        }

        // Idempotency: our patch leaves exactly EXPECTED patched windows.
        int patchedCount = count(data, plan.patchedWindow);
        if (patchedCount == EXPECTED) {
            Log.i(TAG, "rumble: already patched (" + patchedCount + " sites), no-op");
            return;
        }

        // 1) Exact per-build patterns. Apply the FIRST that matches exactly EXPECTED sites.
        for (Sig sig : plan.exact) {
            if (count(data, sig.original) == EXPECTED) {
                replaceAll(data, sig.original, sig.patched);
                if (writeBack(winebus, data)) {
                    Log.i(TAG, "rumble: forced never-expire duration on " + EXPECTED
                            + " site(s) via exact pattern [" + sig.name + "] in " + archDir);
                }
                return;
            }
        }

        // 2) Build-agnostic structural fallback (only reached when no exact pattern hit EXPECTED).
        if (plan.structural == null) {
            Log.w(TAG, "rumble: no exact pattern matched and no structural matcher for " + archDir
                    + " (patched=" + patchedCount + "), SKIP");
            return;
        }
        List<Integer> sites = plan.structural.find(data);
        if (sites.size() != EXPECTED) {
            Log.w(TAG, "rumble: structural fallback found " + sites.size() + " site(s) (patched="
                    + patchedCount + ") in " + archDir + " winebus - ambiguous/unknown, SKIP");
            return;
        }
        for (int off : sites) System.arraycopy(plan.patchedWindow, 0, data, off, plan.patchedWindow.length);
        if (writeBack(winebus, data)) {
            Log.i(TAG, "rumble: forced never-expire duration on " + EXPECTED
                    + " site(s) via structural fallback in " + archDir + " winebus");
        }
    }

    /**
     * aarch64: positions of every "mov w3,w&lt;N&gt; ; blr x8" where the mov is ORR-shifted-reg
     * into w3 (Rd=3, Rn=wzr, LSL#0) with a REAL source register (N != 31/wzr, so the
     * zero-duration stop shape is excluded).
     */
    private static List<Integer> findAarch64Sites(byte[] d) {
        List<Integer> out = new ArrayList<>();
        final int last = d.length - 8;
        for (int i = 0; i <= last; i++) {
            // mov w3,wN  ==  E3 03 Nx 2A, with Nx = Rm in bits[20:16]; top 3 bits (23:21) = 0.
            if (d[i] != (byte) 0xe3 || d[i + 1] != 0x03 || d[i + 3] != 0x2a) continue;
            int rm = d[i + 2] & 0xff;
            if ((rm & 0xe0) != 0) continue;      // bits 23:21 must be 0 (plain LSL#0 ORR)
            if ((rm & 0x1f) == 0x1f) continue;   // exclude wzr (zero duration)
            if (matchAt(d, i + 4, A64_BLR_X8)) out.add(i);
        }
        return out;
    }

    /**
     * x86_64: positions of every "mov ecx,[rbp+disp8] ; movzwl si ; movzwl dx ; call *rax"
     * (disp8 wildcarded so a stack-slot shift in another build still matches; the distinctive
     * suffix keeps it specific). The zero-duration stop uses "xor ecx,ecx" and is not matched.
     */
    private static List<Integer> findX86_64Sites(byte[] d) {
        List<Integer> out = new ArrayList<>();
        final int last = d.length - (3 + X64_SUFFIX.length);
        for (int i = 0; i <= last; i++) {
            // mov ecx, [rbp+disp8]  ==  8B 4D disp8  (disp8 = d[i+2], wildcarded)
            if (d[i] != (byte) 0x8b || d[i + 1] != 0x4d) continue;
            if (matchAt(d, i + 3, X64_SUFFIX)) out.add(i);
        }
        return out;
    }

    private static boolean writeBack(File f, byte[] data) {
        try {
            writeAll(f, data);
            return true;
        } catch (IOException e) {
            Log.w(TAG, "rumble: write failed, winebus left unchanged: " + e);
            return false;
        }
    }

    private static byte[] bytes(int[] v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static boolean matchAt(byte[] hay, int pos, byte[] needle) {
        if (pos < 0 || pos + needle.length > hay.length) return false;
        for (int j = 0; j < needle.length; j++) {
            if (hay[pos + j] != needle[j]) return false;
        }
        return true;
    }

    private static int count(byte[] hay, byte[] needle) {
        int n = 0;
        for (int i = indexOf(hay, needle, 0); i >= 0; i = indexOf(hay, needle, i + 1)) n++;
        return n;
    }

    private static void replaceAll(byte[] hay, byte[] needle, byte[] repl) {
        for (int i = indexOf(hay, needle, 0); i >= 0; i = indexOf(hay, needle, i + needle.length)) {
            System.arraycopy(repl, 0, hay, i, repl.length);
        }
    }

    private static int indexOf(byte[] hay, byte[] needle, int from) {
        final int last = hay.length - needle.length;
        outer:
        for (int i = Math.max(0, from); i <= last; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static byte[] readAll(File f) throws IOException {
        long len = f.length();
        if (len <= 0 || len > Integer.MAX_VALUE) throw new IOException("bad size " + len);
        byte[] buf = new byte[(int) len];
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            raf.readFully(buf);
        }
        return buf;
    }

    private static void writeAll(File f, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
            out.flush();
            out.getFD().sync();
        }
    }
}
