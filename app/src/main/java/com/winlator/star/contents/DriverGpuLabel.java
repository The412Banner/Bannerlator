package com.winlator.star.contents;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which Adreno GPUs a downloadable driver is for, read from its name (the driver feeds carry only a name and
 * a URL). Null when the name does not say; nothing is guessed. Pure Java, unit-tested.
 *
 * <ul>
 * <li>Banners-Turnip release names, per the variant table in its README: no variant = Adreno 6xx/7xx, A8xx,
 * 710-720-Test, 8G2-OneUI, Banners-AIO.</li>
 * <li>Otherwise a GPU model written as its own word, a + three digits starting 6/7/8 (a735, A830, A840P), or a
 * family: a6xx / a7xx / a8xx, Turnip's "gen8" (= Adreno 8xx), "8 Elite".</li>
 * </ul>
 * "adreno744", "adreno-676" and bare numbers are NOT read: in the Kimchi and StevenMXZ feeds those are
 * Qualcomm driver versions, not GPU models.
 */
public final class DriverGpuLabel {
    private DriverGpuLabel() {}

    private static final Pattern BANNERS_TURNIP =
            Pattern.compile("(?i)^turnip-v\\d+(?:\\.\\d+)*-\\d{8}(?:-r\\d+)?(-.+)?$");
    private static final Pattern BUILD_SUFFIX = Pattern.compile("(?i)(?:-linux|-wayland)?(?:\\.zip)?$");
    private static final Pattern MODEL = Pattern.compile("(?i)(?<![a-z0-9])a([678]\\d\\d)p?(?![a-z0-9])");
    private static final Pattern FAMILY = Pattern.compile("(?i)(?<![a-z0-9])a([678])xx");
    private static final Pattern GEN8 = Pattern.compile("(?i)(?<![a-z0-9])gen_?8(?![0-9])");
    private static final Pattern ELITE = Pattern.compile("(?i)(?<![a-z])8 ?elite");

    /** The label for a driver called {@code name} (display name or file name), or null. */
    public static String of(String name) {
        if (name == null) return null;
        String n = name.trim();
        if (n.isEmpty()) return null;
        Matcher bt = BANNERS_TURNIP.matcher(BUILD_SUFFIX.matcher(n).replaceFirst(""));
        if (bt.matches()) {
            String variant = bt.group(1) == null ? "" : bt.group(1).toLowerCase(Locale.ROOT);
            switch (variant) {
                case "": return "Adreno 6xx/7xx";
                case "-a8xx": return "Adreno 8xx";
                case "-710-720-test": return "Adreno 710/720/722";
                case "-8g2-oneui": return "Adreno 740 One UI";
                case "-banners-aio": return "All Adreno";
                default: break;
            }
        }
        Matcher m = MODEL.matcher(n);
        if (m.find()) return "Adreno " + m.group(1);
        Matcher f = FAMILY.matcher(n);
        if (f.find()) return "Adreno " + f.group(1) + "xx";
        if (GEN8.matcher(n).find() || ELITE.matcher(n).find()) return "Adreno 8xx";
        return null;
    }

    /** {@link #of} of the display name, else of the file name. */
    public static String of(String displayName, String fileName) {
        String label = of(displayName);
        return label != null ? label : of(fileName);
    }
}
