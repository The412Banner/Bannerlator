package com.winlator.star.androidgames

/**
 * The on-disk shape of an Android game in the Games list, kept free of Android classes so it can be
 * unit-tested on the JVM. An Android game is an ordinary `.desktop` shortcut — that is what the
 * Games tab, the XMB view and Big Picture all list — tagged `storeSource=android` with the package
 * to start. It lives in the app's own `files/android-games/` home, never in a container. Nothing about it involves Wine: the launch paths check [isAndroid] first and hand the
 * package to Android instead of opening a session.
 */
object AndroidGameEntry {
    const val STORE_SOURCE = "android"
    const val EXTRA_PACKAGE = "androidPackage"
    /** The launcher activity seen when the game was added; only a fallback for the launch. */
    const val EXTRA_ACTIVITY = "androidActivity"

    /**
     * Exec is never run. It keeps the "wine " prefix every shortcut has because Shortcut cuts its
     * `path` at that prefix, and an entry without it gets a mangled path (same as the Linux entries).
     */
    private const val EXEC_PREFIX = "wine android:"

    /** True for the extras an Android game carries: the tag AND a package to start. */
    fun isAndroid(storeSource: String?, pkg: String?): Boolean =
        storeSource == STORE_SOURCE && !pkg.isNullOrBlank()

    /** A file name the desktop dir accepts, from the app's label. */
    fun safeName(label: String, pkg: String): String =
        label.replace(Regex("""[\\/:*?"<>|\r\n]"""), "_").trim().ifEmpty { pkg }

    /**
     * The whole `.desktop` text for one Android game. [iconName] names the PNG under the
     * container's icon dir (the Games card reads `Icon=`, not the cover art). `eos=0` is written up
     * front so the EOS badge's background folder walk never runs on a path that isn't a folder.
     */
    fun desktopEntry(name: String, pkg: String, activity: String?, iconName: String?): String =
        buildString {
            append("[Desktop Entry]\n")
            append("Name=").append(name).append('\n')
            if (!iconName.isNullOrEmpty()) append("Icon=").append(iconName).append('\n')
            append("Exec=").append(EXEC_PREFIX).append(pkg).append('\n')
            append("Type=Application\n")
            append("StartupWMClass=android\n")
            append('\n')
            append("[Extra Data]\n")
            append("storeSource=").append(STORE_SOURCE).append('\n')
            append(EXTRA_PACKAGE).append('=').append(pkg).append('\n')
            if (!activity.isNullOrEmpty()) append(EXTRA_ACTIVITY).append('=').append(activity).append('\n')
            append("eos=0\n")
        }

    /** The `Icon=` name of an entry's [Desktop Entry] section, or null when it has none. */
    fun iconOf(desktopText: String): String? = valueOf(desktopText, "Desktop Entry", "Icon")

    /** One `[Extra Data]` value of an entry, or null when it is absent or empty. */
    fun extraOf(desktopText: String, key: String): String? = valueOf(desktopText, "Extra Data", key)

    private fun valueOf(desktopText: String, wantSection: String, wantKey: String): String? {
        var section = ""
        for (raw in desktopText.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("[")) { section = line.substringAfter('[').substringBefore(']'); continue }
            if (section != wantSection) continue
            val eq = line.indexOf('=')
            if (eq > 0 && line.substring(0, eq) == wantKey) return line.substring(eq + 1).takeIf { it.isNotEmpty() }
        }
        return null
    }

    /**
     * The same entry pointed at a moved icon and cover: `Icon=` becomes [iconName] and
     * `customCoverArtPath` becomes [coverPath]; a null leaves that line as it was. Everything else -
     * the package and the uuid a home-screen pin was made with - is kept verbatim.
     */
    fun retarget(desktopText: String, iconName: String?, coverPath: String?): String {
        var section = ""
        val out = StringBuilder()
        for (raw in desktopText.lines()) {
            val line = raw.trim()
            if (line.startsWith("[")) section = line.substringAfter('[').substringBefore(']')
            val replaced = when {
                iconName != null && section == "Desktop Entry" && line.startsWith("Icon=") -> "Icon=$iconName"
                coverPath != null && section == "Extra Data" && line.startsWith("customCoverArtPath=") ->
                    "customCoverArtPath=$coverPath"
                else -> raw
            }
            out.append(replaced).append('\n')
        }
        return out.toString().trimEnd('\n') + "\n"
    }

    /**
     * The package an entry's text names, or null when it is not an Android game. Reads the same
     * `[Extra Data]` keys Shortcut does, for callers that only hold the file (the add dialog's
     * "Already added" check runs over files, not loaded shortcuts).
     */
    fun packageOf(desktopText: String): String? {
        var section = ""
        var source: String? = null
        var pkg: String? = null
        for (raw in desktopText.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("[")) { section = line.substringAfter('[').substringBefore(']'); continue }
            if (section != "Extra Data") continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            when (line.substring(0, eq)) {
                "storeSource" -> source = line.substring(eq + 1)
                EXTRA_PACKAGE -> pkg = line.substring(eq + 1)
            }
        }
        return if (isAndroid(source, pkg)) pkg else null
    }
}
