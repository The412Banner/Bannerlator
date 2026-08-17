package com.winlator.star.contents

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Step 5 of the Wrapper Version Manager (issue #132) — downloads a catalog wrapper archive and hands
 * it to the SAME import pipeline as a hand-picked file. A near-clone of
 * {@link com.winlator.star.reshade.ReshadeDownloader}, except the final step feeds the temp .tzst to
 * {@link WrapperManager#importWrapper} (File -> file:// Uri) instead of extracting it directly — so a
 * downloaded wrapper gets capability detection + env-scan + smart settings + the collision/label
 * handling for free, and lands exactly like a user import (with the catalog entry's name).
 *
 * Reuses Downloader.downloadFile (HTTP + progress); verifies the catalog's UPPERCASE MD5 before
 * trusting the archive (blank checksum = skip). Runs entirely off the main thread. Never crashes.
 */
object WrapperCatalogDownloader {
    private const val TAG = "WrapperCatalogDownloader"

    /**
     * Download + verify [entry], then import it via [WrapperManager.importWrapper].
     * [onProgress] reports 0..100 (download fraction; jumps to 100 for the short import step). Returns
     * the stored wrapper identifier on success, or null on any failure (download / checksum / invalid
     * archive / import). Off-main (IO).
     */
    suspend fun install(
        context: Context,
        entry: WrapperCatalogEntry,
        onProgress: (Int) -> Unit,
    ): String? = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, "wrapper_dl").apply { mkdirs() }
        val archive = File(cacheDir, "${safe(entry.id)}.tzst")
        if (archive.exists()) archive.delete()
        try {
            onProgress(0)
            if (!Downloader.downloadFile(entry.url, archive) { fraction ->
                    // fraction may be -1 (unknown size) — clamp to a sane 0..100 for the UI.
                    onProgress((fraction.coerceIn(0f, 1f) * 100f).toInt())
                }) {
                Log.w(TAG, "download failed: ${entry.url}")
                return@withContext null
            }
            // Verify the UPPERCASE MD5 from the catalog before trusting the archive (blank = skip).
            if (entry.checksum.isNotBlank()) {
                val actual = md5Upper(archive)
                if (!actual.equals(entry.checksum, ignoreCase = true)) {
                    Log.w(TAG, "checksum mismatch for ${entry.id}: expected ${entry.checksum} got $actual")
                    archive.delete()
                    return@withContext null
                }
            }
            onProgress(100)
            // Route the download through the SAME import pipeline. importWrapper reads the source via
            // ContentResolver.openInputStream, which accepts a file:// Uri (in-process, no
            // FileUriExposedException), and returns the stored identifier (or null if the archive
            // isn't a valid wrapper). This is where capability detection + env-scan + smart settings run.
            // Pass the catalog id + version so the import records its provenance (#132): the manager then
            // shows an "update available" badge / offers an in-place update when the catalog ships a newer
            // version, and a re-download of the same entry updates the wrapper in place.
            val identifier = WrapperManager(context)
                .importWrapper(Uri.fromFile(archive), entry.name, entry.id, entry.version)
            if (identifier == null) Log.w(TAG, "import failed for ${entry.id}")
            identifier
        } catch (t: Throwable) {
            Log.w(TAG, "install failed for ${entry.id}", t)
            null
        } finally {
            archive.delete()
        }
    }

    /**
     * Download + verify [entry] exactly like [install], but instead of a free-form import it installs
     * the archive as the OVERRIDE for the bundled slot [slotFileName] via
     * [WrapperManager.installOverride] — so the slot's marker validation applies (a wrapper archive
     * that doesn't match the slot type is rejected there, surfacing the usual invalid-wrapper toast).
     * [onProgress] reports 0..100 (download fraction; jumps to 100 for the short install step). Returns
     * true on success, false on any failure (download / checksum / invalid archive / mismatched slot).
     * Off-main (IO); the temp archive is always deleted; never crashes.
     */
    suspend fun installToSlot(
        context: Context,
        entry: WrapperCatalogEntry,
        slotFileName: String,
        onProgress: (Int) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, "wrapper_dl").apply { mkdirs() }
        val archive = File(cacheDir, "${safe(entry.id)}.tzst")
        if (archive.exists()) archive.delete()
        try {
            onProgress(0)
            if (!Downloader.downloadFile(entry.url, archive) { fraction ->
                    onProgress((fraction.coerceIn(0f, 1f) * 100f).toInt())
                }) {
                Log.w(TAG, "download failed: ${entry.url}")
                return@withContext false
            }
            // Verify the UPPERCASE MD5 from the catalog before trusting the archive (blank = skip).
            if (entry.checksum.isNotBlank()) {
                val actual = md5Upper(archive)
                if (!actual.equals(entry.checksum, ignoreCase = true)) {
                    Log.w(TAG, "checksum mismatch for ${entry.id}: expected ${entry.checksum} got $actual")
                    return@withContext false
                }
            }
            onProgress(100)
            // Install as THIS slot's override. installOverride reads the source via
            // ContentResolver.openInputStream (accepts a file:// Uri in-process) and applies the slot's
            // marker validation, returning false for a mismatched/invalid archive.
            val wm = WrapperManager(context)
            val ok = wm.installOverride(slotFileName, Uri.fromFile(archive))
            if (ok) {
                // Record catalog provenance for the slot override (#132). Slot overrides have no .meta,
                // so this durable mapping is what lets the picker/manager show a persistent "Installed"
                // state + detect a newer catalog version for a slot-installed wrapper.
                wm.recordSlotCatalog(slotFileName, entry.id, entry.version, entry.name)
            } else {
                Log.w(TAG, "installOverride failed for ${entry.id} -> $slotFileName")
            }
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "installToSlot failed for ${entry.id}", t)
            false
        } finally {
            archive.delete()
        }
    }

    private fun md5Upper(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02X".format(it) }
    }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
