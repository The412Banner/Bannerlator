package com.winlator.star.linux;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * proot's fast path (tools/proot/fastpath, patches 0014/0015): path syscalls answered inside each
 * process instead of stopping it for proot. The guest library answers only when its tracer's
 * environment holds the key made here from the exact rootfs and binds proot was given.
 * (From Droid-Deck/DroidDeck ProotFastPath.kt.)
 */
public final class ProotFastPath {
    public static final String LIBRARY = "/usr/local/lib/libblfastpath.so";
    /** Present in Downloads: no preload, no key - the session exactly as before the fast path. */
    public static final String SWITCH = "Download/bannerlator-no-fastpath";

    private ProotFastPath() {}

    /**
     * Whether the proot about to run reads PROOT_FASTPATH (0014). Termux's shipped binary does not,
     * and a proot that traps the trampoline would translate an already translated path a second time.
     */
    public static boolean supportedBy(File proot) {
        byte[] want = "PROOT_FASTPATH".getBytes(StandardCharsets.US_ASCII);
        try {
            byte[] bin = Files.readAllBytes(proot.toPath());
            outer:
            for (int i = 0; i + want.length <= bin.length; i++) {
                for (int j = 0; j < want.length; j++) if (bin[i + j] != want[j]) continue outer;
                return true;
            }
        } catch (IOException e) {
            // Unreadable: treated as not supported.
        }
        return false;
    }

    /** The -b specs of a command LinuxRuntime.command built, in order; the guest command is skipped. */
    public static List<String> bindSpecs(List<String> command, int guestSize) {
        List<String> specs = new ArrayList<>();
        int end = command.size() - guestSize;
        for (int i = 0; i + 1 < end; i++) if ("-b".equals(command.get(i))) specs.add(command.get(++i));
        return specs;
    }

    /** The key for this rootfs and these binds, or null when the library cannot be told them. */
    public static String key(File root, List<String> binds) {
        if (root.getPath().contains("|")) return null;
        for (String b : binds) if (b.contains("|")) return null;
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest((root.getPath() + "\n" + String.join("\n", binds)).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 12; i++) hex.append(String.format("%02x", d[i]));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return null;
        }
    }

    public static List<String> guestEnv(File root, List<String> binds, String key) {
        return Arrays.asList("PROOT_FP_ROOT=" + root.getPath(),
                "PROOT_FP_BINDS=" + String.join("|", binds), "PROOT_FP_KEY=" + key);
    }
}
