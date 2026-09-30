package com.winlator.star.androidgames

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import com.winlator.star.container.Container
import com.winlator.star.container.ContainerManager
import com.winlator.star.container.Shortcut
import com.winlator.star.core.FileUtils
import org.json.JSONObject
import java.io.File

/**
 * Android games in the Games list ("+" → Add Android game). An entry is a plain `.desktop` shortcut
 * (see [AndroidGameEntry]). Tapping one starts the app the normal Android way, as its own task —
 * nothing runs inside Bannerlator.
 *
 * The entries live in their own home, `files/android-games/`, not in any Wine container: they never
 * use one, and a container's delete must not take them along. The home is a [Container] envelope
 * with a reserved id and nothing but a desktop and icon dir beneath it — the same trick as the Linux
 * runtime's settings (LinuxSettings) — so [Shortcut], the cover art, rename and remove all work on
 * it unchanged. [ContainerManager.getContainerById] hands it out for [CONTAINER_ID]; it is never in
 * [ContainerManager.getContainers], so no container screen offers to edit, back up or delete it.
 */
object AndroidGames {
    private const val TAG = "AndroidGames"

    /** Never a real container's id (those count up from 1); the Linux settings are -7. */
    const val CONTAINER_ID = -8
    const val DIR = "android-games"
    /** What the "Container" sort and the properties sheet call the home. */
    const val NAME = "Android"
    /** Written once every entry found in a Wine container has been moved to the home. */
    private const val MIGRATION_MARKER = ".migrated-from-containers"

    @JvmStatic
    fun isHome(id: Int): Boolean = id == CONTAINER_ID

    @JvmStatic
    fun isHome(container: Container?): Boolean = container != null && container.id == CONTAINER_ID

    /**
     * The home envelope. No config file is written — there is nothing to configure — so it is built
     * from the app's defaults each time; [ContainerManager] keeps one per instance.
     */
    @JvmStatic
    fun homeContainer(context: Context, manager: ContainerManager): Container {
        val root = File(context.filesDir, DIR)
        root.mkdirs()
        val container = Container(CONTAINER_ID, manager)
        container.rootDir = root
        try {
            val data = JSONObject()
            Container.checkObsoleteOrMissingProperties(data)
            data.put("name", NAME)
            container.loadData(data)
        } catch (e: Exception) {
            Log.w(TAG, "could not load defaults into the Android games home", e)
        }
        container.name = NAME
        return container
    }

    @Volatile private var migrationChecked = false
    private val migrationLock = Any()

    /**
     * Moves Android entries written by the first build of this feature — which put them in whichever
     * container the "+" flow had picked — into the home, with their icons and covers. Runs from the
     * first [ContainerManager.loadShortcuts] of the process, before anything is listed, so no screen
     * ever shows an entry in its old place. Idempotent: a marker file ends it for good once a pass
     * moves everything; a pass with a failure leaves no marker and the next app start tries again.
     */
    @JvmStatic
    fun migrateOnce(home: Container, containers: List<Container>) {
        if (migrationChecked) return
        // Other threads' loads wait here until the pass is done, so none of them lists an entry
        // halfway through its move.
        synchronized(migrationLock) {
            if (migrationChecked) return
            try {
                val marker = File(home.rootDir, MIGRATION_MARKER)
                if (marker.isFile) return
                var moved = 0
                var failed = 0
                var scanned = 0
                for (c in containers) {
                    if (isHome(c)) continue
                    val files = c.desktopDir.listFiles { f -> f.isFile && f.name.endsWith(".desktop") } ?: continue
                    scanned++
                    for (f in files) {
                        val text = runCatching { FileUtils.readString(f) }.getOrNull() ?: continue
                        if (AndroidGameEntry.packageOf(text) == null) continue
                        if (moveEntry(c, home, f, text)) moved++ else failed++
                    }
                }
                // No marker from a pass that saw no container at all (the list can be empty before
                // setup finishes): there would be nothing it had actually checked.
                if (failed == 0 && scanned > 0) FileUtils.writeString(marker, "moved=$moved\n")
                if (moved > 0 || failed > 0) {
                    Log.i(TAG, "migration: moved $moved Android entr${if (moved == 1) "y" else "ies"} out of containers, $failed failed")
                }
            } finally {
                migrationChecked = true
            }
        }
    }

    private fun copy(src: File, dst: File): Boolean = try {
        dst.parentFile?.mkdirs()
        src.copyTo(dst, overwrite = true)
        true
    } catch (e: Exception) {
        Log.w(TAG, "could not copy $src to $dst", e)
        false
    }

    /**
     * One entry from [from]'s desktop dir into [home]'s, under a free name (a clash gets " (2)",
     * " (3)"…). The icon PNGs and the cover follow and the entry is repointed at them; the originals
     * are deleted only after the new entry is written.
     */
    private fun moveEntry(from: Container, home: Container, file: File, text: String): Boolean {
        val destDir = home.desktopDir
        if (!destDir.isDirectory && !destDir.mkdirs()) return false
        val oldBase = file.name.removeSuffix(".desktop")
        var base = oldBase
        var n = 2
        while (File(destDir, "$base.desktop").exists()) base = "$oldBase ($n)".also { n++ }

        val movedIcons = ArrayList<File>()
        var newIcon: String? = null
        AndroidGameEntry.iconOf(text)?.let { icon ->
            for (size in intArrayOf(64, 48, 32, 16)) {
                val src = File(from.getIconsDir(size), "$icon.png")
                if (src.isFile && copy(src, File(home.getIconsDir(size), "$base.png"))) {
                    movedIcons += src
                    newIcon = base
                }
            }
        }
        var oldCover: File? = null
        var newCover: String? = null
        AndroidGameEntry.extraOf(text, "customCoverArtPath")?.let { path ->
            val src = File(path)
            val dst = File(home.rootDir, "app_data/cover_arts/$base.png")
            if (src.isFile && copy(src, dst)) {
                oldCover = src
                newCover = dst.path
            }
        }
        val target = File(destDir, "$base.desktop")
        if (!FileUtils.writeString(target, AndroidGameEntry.retarget(text, newIcon, newCover))) {
            Log.w(TAG, "could not write $target")
            return false
        }
        file.delete()
        movedIcons.forEach { it.delete() }
        // Only a cover that lived in the source container is ours to delete.
        oldCover?.takeIf { it.path.startsWith(from.rootDir.path) }?.delete()
        Log.i(TAG, "moved '${file.name}' from container ${from.id} to the Android games home as '$base'")
        return true
    }

    /** Portrait 2:3 tile the Games card crops to, the app icon centred on it. */
    private const val TILE_W = 360
    private const val TILE_H = 540
    private const val TILE_ICON = 216
    private const val TILE_BG = 0xFF1B1B1B.toInt()

    data class InstalledApp(
        val packageName: String,
        val label: String,
        /** Launcher activity class, kept only as a fallback for the launch. */
        val activity: String,
        val isGame: Boolean,
    )

    enum class LaunchResult { STARTED, NOT_INSTALLED, FAILED }

    fun packageOf(shortcut: Shortcut?): String? {
        if (shortcut == null) return null
        val pkg = shortcut.getExtra(AndroidGameEntry.EXTRA_PACKAGE)
        return pkg.takeIf { AndroidGameEntry.isAndroid(shortcut.getExtra("storeSource"), it) }
    }

    fun isAndroidEntry(shortcut: Shortcut?): Boolean = packageOf(shortcut) != null

    /** Packages already in the Games list, for the picker's "Already added" rows. */
    fun addedPackages(shortcuts: List<Shortcut>): Set<String> = shortcuts.mapNotNullTo(HashSet()) { packageOf(it) }

    /**
     * Every app with a launcher entry (phone launcher, or TV launcher for apps that only have that),
     * minus Bannerlator itself, sorted by label. Blocking — PackageManager calls; run off main.
     * No QUERY_ALL_PACKAGES: the manifest's <queries> MAIN/LAUNCHER intent is what makes these
     * visible on API 30+ targets (the current targetSdk, 28, sees them anyway).
     */
    fun listInstalledApps(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val self = context.packageName
        val found = LinkedHashMap<String, InstalledApp>()
        for (category in arrayOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            val infos = runCatching { pm.queryIntentActivities(query, 0) }.getOrDefault(emptyList())
            for (ri in infos) {
                val ai = ri.activityInfo ?: continue
                val pkg = ai.packageName ?: continue
                if (pkg == self || pkg in found) continue
                val label = runCatching { ri.loadLabel(pm)?.toString() }.getOrNull()?.trim().orEmpty()
                found[pkg] = InstalledApp(pkg, label.ifEmpty { pkg }, ai.name, isGame(ai.applicationInfo))
            }
        }
        return found.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    /** What Android itself marks as a game: the Play category, or the older isGame manifest flag. */
    @Suppress("DEPRECATION")
    fun isGame(app: ApplicationInfo?): Boolean {
        if (app == null) return false
        return app.category == ApplicationInfo.CATEGORY_GAME || (app.flags and ApplicationInfo.FLAG_IS_GAME) != 0
    }

    // Icons for the picker rows; a few dozen at 96px is well under this.
    private val iconCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** The app's icon as a [sizePx] square bitmap, cached. Blocking; null when the app is gone. */
    fun iconBitmap(context: Context, pkg: String, sizePx: Int): Bitmap? {
        val key = "$pkg@$sizePx"
        iconCache.get(key)?.let { return it }
        val drawable = try {
            context.packageManager.getApplicationIcon(pkg)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        val bmp = runCatching { drawable.toBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
        iconCache.put(key, bmp)
        return bmp
    }

    private fun portraitTile(context: Context, pkg: String): Bitmap? {
        val icon = iconBitmap(context, pkg, TILE_ICON) ?: return null
        val tile = Bitmap.createBitmap(TILE_W, TILE_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(tile)
        canvas.drawColor(TILE_BG)
        canvas.drawBitmap(icon, ((TILE_W - TILE_ICON) / 2).toFloat(), ((TILE_H - TILE_ICON) / 2).toFloat(), null)
        return tile
    }

    /**
     * Writes the Games-list entry for [app] into [container]'s desktop dir — the home, see
     * [homeContainer] — with the app's icon as the card art (Icon= PNG) and the cover. A name
     * already taken gets " (Android)" so an existing entry is never overwritten. Blocking.
     * Returns the new `.desktop`, or null if it could not be written.
     */
    fun addToShortcuts(context: Context, container: Container, app: InstalledApp): File? {
        val desktopDir = container.desktopDir
        if (!desktopDir.isDirectory && !desktopDir.mkdirs()) {
            Log.w(TAG, "cannot create $desktopDir")
            return null
        }
        val wanted = AndroidGameEntry.safeName(app.label, app.packageName)
        var base = wanted
        if (File(desktopDir, "$base.desktop").exists()) base = "$wanted (Android)"
        var n = 2
        while (File(desktopDir, "$base.desktop").exists()) base = "$wanted (Android $n)".also { n++ }

        val tile = portraitTile(context, app.packageName)
        var iconName: String? = null
        if (tile != null) {
            val iconsDir = container.getIconsDir(64)
            if (iconsDir != null && (iconsDir.isDirectory || iconsDir.mkdirs()) &&
                FileUtils.saveBitmapToFile(tile, File(iconsDir, "$base.png"))
            ) iconName = base
        }
        val file = File(desktopDir, "$base.desktop")
        val text = AndroidGameEntry.desktopEntry(app.label, app.packageName, app.activity, iconName)
        if (!FileUtils.writeString(file, text)) {
            Log.w(TAG, "could not write $file")
            return null
        }
        if (tile != null) {
            try { Shortcut(container, file).saveCustomCoverArt(tile) } catch (e: Exception) { Log.w(TAG, "cover for $base", e) }
        }
        Log.i(TAG, "added ${app.packageName} as '$base' in container ${container.id}")
        return file
    }

    /**
     * Drops a removed entry's tile and cover from the home. Nothing else uses them, and a re-add of
     * the same app under the same name writes fresh ones. Only files inside the home are touched.
     */
    fun deleteArt(shortcut: Shortcut) {
        val home = shortcut.container ?: return
        if (!isHome(home)) return
        val root = home.rootDir?.path ?: return
        shortcut.iconFile?.takeIf { it.isFile && it.path.startsWith(root) }?.delete()
        shortcut.customCoverArtPath?.takeIf { it.startsWith(root) }?.let { File(it).delete() }
    }

    fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * The intent that opens the game as Android would from its own launcher icon: the package's
     * launch intent, the TV one for TV-only apps, else the activity recorded when it was added.
     * Null when the app is gone or has no way in.
     */
    fun launchIntent(context: Context, shortcut: Shortcut): Intent? {
        val pkg = packageOf(shortcut) ?: return null
        if (!isInstalled(context, pkg)) return null
        val pm = context.packageManager
        return pm.getLaunchIntentForPackage(pkg)
            ?: pm.getLeanbackLaunchIntentForPackage(pkg)
            ?: shortcut.getExtra(AndroidGameEntry.EXTRA_ACTIVITY).takeIf { it.isNotEmpty() }?.let {
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(pkg, it)
            }
    }

    /** Starts the game as its own task. Bannerlator stays where it is, in the background. */
    fun launch(context: Context, shortcut: Shortcut): LaunchResult {
        val pkg = packageOf(shortcut) ?: return LaunchResult.FAILED
        if (!isInstalled(context, pkg)) return LaunchResult.NOT_INSTALLED
        val intent = launchIntent(context, shortcut) ?: return LaunchResult.FAILED
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            LaunchResult.STARTED
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no activity for $pkg", e)
            LaunchResult.FAILED
        } catch (e: SecurityException) {
            Log.w(TAG, "not allowed to start $pkg", e)
            LaunchResult.FAILED
        }
    }

    /** The one-line message for a launch that did not start, or null when it did. */
    fun failureMessage(shortcut: Shortcut, result: LaunchResult): String? = when (result) {
        LaunchResult.STARTED -> null
        LaunchResult.NOT_INSTALLED -> "${shortcut.name} isn't installed anymore"
        LaunchResult.FAILED -> "Couldn't open ${shortcut.name}"
    }
}
