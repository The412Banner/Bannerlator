package com.winlator.star.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.winlator.star.androidgames.AndroidGames
import com.winlator.star.container.Shortcut
import com.winlator.star.core.CopyGameToDriveC
import com.winlator.star.core.FileUtils
import com.winlator.star.core.ImageUtils
import java.io.File

/** A pixel rectangle inside an image. */
internal data class CoverCrop(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * A cover the user picked from their own files ("Add your own cover"), and the way back.
 *
 * The cover is written to both places a game's cover is read from, the same two writes Scrape cover
 * does: the full cover (`Shortcut.saveCustomCoverArt`, what the classic and Big Picture screens draw) and
 * the icon PNG under the container's 64 icon dir (what the XMB row draws). The shortcut is marked
 * `coverSource=user` so no Steam or online art replaces it behind the user's back.
 *
 * What the game showed before is kept so Remove can put it back: the original icon beside the new one
 * (`<name>.orig.png`), the original full cover the same way, and the original `Icon=` name and cover path
 * as extras. Blocking file work: call off the main thread.
 */
internal object CustomCover {
    private const val TAG = "CustomCover"

    private const val EXTRA_SOURCE = "coverSource"
    private const val SOURCE_USER = "user"
    /** The `Icon=` name the game had before its first custom cover ("" = none); absent when it was the game's own name. */
    private const val EXTRA_ICON_ORIG = "coverIconOrig"
    /**
     * The `customCoverArtPath` the game had before its first custom cover: "" for none, [COVER_OWN] when it
     * was the game's own cover file (the one the custom cover now takes over, kept as `.orig.png`), else
     * the path of a file that is not ours to touch. A path would go stale when the game is renamed or
     * copied to another container, which is why the common case is not stored as one.
     */
    private const val EXTRA_COVER_ORIG = "coverPathOrig"
    private const val COVER_OWN = "own"
    /** Where Shortcut.saveCustomCoverArt puts a cover, under the container root. */
    private const val COVER_DIR = "app_data/cover_arts"

    /** True while the shortcut shows a cover the user picked. */
    fun isCustom(shortcut: Shortcut): Boolean = shortcut.getExtra(EXTRA_SOURCE, "") == SOURCE_USER

    /**
     * The biggest centred portrait 2:3 rectangle of a [width] x [height] image, the shape every cover
     * tile is cut to. A wider picture loses its sides, a taller one its top and bottom.
     */
    fun cropFor(width: Int, height: Int): CoverCrop {
        if (width <= 0 || height <= 0) return CoverCrop(0, 0, 0, 0)
        return if (width.toLong() * 3 > height.toLong() * 2) {
            val w = (height * 2 / 3).coerceAtLeast(1)
            CoverCrop((width - w) / 2, 0, w, height)
        } else {
            val h = (width * 3 / 2).coerceAtLeast(1)
            CoverCrop(0, (height - h) / 2, width, h)
        }
    }

    /**
     * [lines] of a .desktop with its `Icon=` set to [icon]: replaced in place, or added under `Name=`
     * when there is none; dropped when [icon] is null. The Extra Data section is left alone.
     */
    fun withIconLine(lines: List<String>, icon: String?): List<String> {
        val out = ArrayList<String>(lines.size + 1)
        var inExtra = false
        var written = false
        var insertAt = -1
        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith("[")) inExtra = line == "[Extra Data]"
            if (!inExtra && line.startsWith("Icon=")) {
                if (icon != null && !written) {
                    out.add("Icon=$icon")
                    written = true
                }
                continue
            }
            out.add(raw)
            if (!inExtra && insertAt < 0 && line.startsWith("Name=")) insertAt = out.size
        }
        if (icon != null && !written) {
            val at = when {
                insertAt >= 0 -> insertAt
                out.firstOrNull()?.trim() == "[Desktop Entry]" -> 1
                else -> 0
            }
            out.add(at, "Icon=$icon")
        }
        return out
    }

    /** The picked image cut to the cover shape and within the size limit every cover keeps; null if it can't be read. */
    fun prepare(source: File): Bitmap? {
        val decoded = ImageUtils.decodeCoverArt(source.absolutePath) ?: return null
        val crop = cropFor(decoded.width, decoded.height)
        if (crop.width <= 0 || crop.height <= 0) return null
        val cut = if (crop.x == 0 && crop.y == 0 && crop.width == decoded.width && crop.height == decoded.height) {
            decoded
        } else {
            Bitmap.createBitmap(decoded, crop.x, crop.y, crop.width, crop.height)
        }
        return ImageUtils.fitCoverArt(cut)
    }

    /**
     * Makes [source] the game's cover everywhere. Returns the cover, or null when the image can't be
     * read or written (the game is then left as it was).
     */
    fun apply(shortcut: Shortcut, source: File): Bitmap? {
        val cover = prepare(source) ?: return null
        val name = shortcut.name
        val container = shortcut.container
        val iconsDir = container.getIconsDir(64) ?: return null
        if (!iconsDir.isDirectory && !iconsDir.mkdirs()) return null
        val iconFile = File(iconsDir, "$name.png")
        val coverFile = File(container.rootDir, "$COVER_DIR/$name.png")
        val first = !isCustom(shortcut)
        // Editing the file bumps its modified time, which the "Recently added" shelf sorts on.
        val modified = shortcut.file.lastModified()
        // Set once the originals are safe: only then is there something for remove() to put back.
        var undoable = false
        return try {
            if (first) {
                // An icon the game owns (Icon= is its own name) comes back with the file; only a
                // different name, or none, needs writing down.
                val origIcon = iconNameOf(shortcut)
                shortcut.putExtra(EXTRA_ICON_ORIG, if (origIcon == name) null else origIcon.orEmpty())
                val origCover = shortcut.customCoverArtPath.orEmpty()
                shortcut.putExtra(EXTRA_COVER_ORIG, if (origCover == coverFile.path) COVER_OWN else origCover)
                keepOriginal(iconFile)
                keepOriginal(coverFile)
                undoable = true
            }
            // The icon is read from <Icon=>.png. An entry whose Icon= names a file shared with other
            // games (the Linux entries) points at its own file now, so the shared one is never overwritten.
            setIconName(shortcut.file, name)
            shortcut.putExtra(EXTRA_SOURCE, SOURCE_USER)
            // Writes the cover file, sets customCoverArtPath and saves every extra above with it.
            shortcut.saveCustomCoverArt(cover)
            val iconSaved = FileUtils.saveBitmapToFile(cover, iconFile)
            if (!iconSaved || !coverFile.isFile) {
                Log.w(TAG, "could not write the cover files of $name")
                if (undoable) remove(null, shortcut)
                return null
            }
            shortcut.icon = cover
            cover
        } catch (e: Exception) {
            Log.w(TAG, "custom cover failed for $name", e)
            if (undoable) runCatching { remove(null, shortcut) }
            null
        } finally {
            if (modified > 0L) shortcut.file.setLastModified(modified)
        }
    }

    /**
     * Takes the custom cover away and brings back what the game showed before it. A game whose
     * cover was marked as the user's elsewhere (the classic editor) has no record of what came before:
     * with a [context] it gets the cover a new shortcut would, the app's tile or the .exe's own icon.
     * Returns false only when the shortcut can't be written.
     */
    fun remove(context: Context?, shortcut: Shortcut): Boolean {
        val name = shortcut.name
        val container = shortcut.container
        val iconFile = container.getIconsDir(64)?.let { File(it, "$name.png") }
        val coverFile = File(container.rootDir, "$COVER_DIR/$name.png")
        // Written by apply() on the first custom cover; a cover marked as the user's elsewhere has neither.
        val recorded = shortcut.hasExtra(EXTRA_COVER_ORIG)
        val origCover = shortcut.getExtra(EXTRA_COVER_ORIG, "")
        val modified = shortcut.file.lastModified()
        return try {
            if (iconFile != null) restoreOriginal(iconFile)
            val coverPath = when {
                // The old cover is a file of its own (shared, or kept elsewhere): ours just goes.
                origCover.isNotEmpty() && origCover != COVER_OWN && File(origCover).path != coverFile.path && File(origCover).isFile -> {
                    coverFile.delete()
                    Shortcut.originalOf(coverFile).delete()
                    origCover
                }
                Shortcut.originalOf(coverFile).isFile -> {
                    restoreOriginal(coverFile)
                    coverFile.path
                }
                else -> {
                    coverFile.delete()
                    null
                }
            }
            if (shortcut.hasExtra(EXTRA_ICON_ORIG)) {
                setIconName(shortcut.file, shortcut.getExtra(EXTRA_ICON_ORIG, "").ifEmpty { null })
            }
            shortcut.putExtra(EXTRA_SOURCE, null)
            shortcut.putExtra(EXTRA_ICON_ORIG, null)
            shortcut.putExtra(EXTRA_COVER_ORIG, null)
            shortcut.setCustomCoverArtPath(coverPath)
            if (!recorded && context != null) regenerate(context, shortcut)
            // The shortcut in hand shows what is on disk again.
            runCatching { Shortcut(container, shortcut.file) }.getOrNull()?.let { fresh ->
                shortcut.icon = fresh.icon
                shortcut.setCoverArt(fresh.coverArt)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "removing the custom cover failed for $name", e)
            false
        } finally {
            if (modified > 0L) shortcut.file.setLastModified(modified)
        }
    }

    /**
     * A new cover from somewhere else (Scrape cover, a Steam link) replaces the user's: the saved
     * originals no longer describe what Remove should bring back, so they go and the marker with them.
     * The caller saves the shortcut.
     */
    fun forget(shortcut: Shortcut) {
        shortcut.container.getIconsDir(64)?.let { Shortcut.originalOf(File(it, "${shortcut.name}.png")).delete() }
        Shortcut.originalOf(File(shortcut.container.rootDir, "$COVER_DIR/${shortcut.name}.png")).delete()
        shortcut.putExtra(EXTRA_SOURCE, null)
        shortcut.putExtra(EXTRA_ICON_ORIG, null)
        shortcut.putExtra(EXTRA_COVER_ORIG, null)
    }

    /** Copies [image] to its `.orig.png`; with no image to keep, a stale copy from an earlier custom cover goes. */
    private fun keepOriginal(image: File) {
        val original = Shortcut.originalOf(image)
        if (image.isFile) image.copyTo(original, overwrite = true) else original.delete()
    }

    /** Puts [image]'s `.orig.png` back in its place; with none saved the image is the custom one and goes. */
    private fun restoreOriginal(image: File) {
        val original = Shortcut.originalOf(image)
        if (original.isFile) {
            original.copyTo(image, overwrite = true)
            original.delete()
        } else {
            image.delete()
        }
    }

    private fun iconNameOf(shortcut: Shortcut): String? =
        FileUtils.readLines(shortcut.file)
            .map { it.trim() }
            .firstOrNull { it.startsWith("Icon=") }
            ?.substringAfter("Icon=")?.trim()?.takeIf { it.isNotEmpty() }

    private fun setIconName(file: File, icon: String?) {
        val lines = FileUtils.readLines(file)
        FileUtils.writeString(file, withIconLine(lines, icon).joinToString("\n") + "\n")
    }

    /** The cover a shortcut gets when it is added: an Android app's tile, else the icon inside its .exe. Best effort. */
    private fun regenerate(context: Context, shortcut: Shortcut) {
        val bmp = AndroidGames.packageOf(shortcut)?.let { AndroidGames.portraitTile(context, it) }
            ?: CopyGameToDriveC.parse(shortcut).exeAndroid?.let { ExeIconExtractor.extract(it) }
            ?: return
        val iconsDir = shortcut.container.getIconsDir(64) ?: return
        if (!iconsDir.isDirectory && !iconsDir.mkdirs()) return
        if (iconNameOf(shortcut) == null) setIconName(shortcut.file, shortcut.name)
        FileUtils.saveBitmapToFile(bmp, File(iconsDir, "${shortcut.name}.png"))
        shortcut.saveCustomCoverArt(bmp)
    }
}
