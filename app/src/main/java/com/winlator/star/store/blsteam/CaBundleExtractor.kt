package com.winlator.star.store.blsteam

import android.content.Context
import android.util.Log
import java.io.File

/**
 * The native engine (`rustls`) takes a single PEM trust-bundle FILE for its HTTPS calls
 * (Steam directory, CDN). Android's CA store is a directory of hashed-filename PEMs under
 * `/system/etc/security/cacerts`, so this helper concatenates every `*.0` there into
 * `filesDir/blsteam_cacert.pem` on first run and reuses it afterwards.
 *
 * If the system directory is unreadable (some OEM builds, work profiles) it falls back to the
 * bundled `assets/blsteam_cacert.pem` (a stock Mozilla-derived bundle) so TLS can still verify.
 *
 * Derived from WinNative's `CaBundleExtractor` (GPL-3.0-or-later); see
 * `app/src/main/cpp/bl-steam-client/NOTICE.md`.
 */
object CaBundleExtractor {

    private const val TAG = "BL_STEAM_CA"
    private const val OUT_NAME = "blsteam_cacert.pem"
    private const val ASSET_NAME = "blsteam_cacert.pem"
    private const val SYS_CA_DIR = "/system/etc/security/cacerts"

    /**
     * Ensures the bundle exists; returns its absolute path. Empty string on total failure
     * (in which case the engine's TLS handshakes will be rejected).
     */
    fun ensureBundle(context: Context): String {
        val out = File(context.filesDir, OUT_NAME)
        if (out.exists() && out.length() > 1024) {
            // Sanity floor: a single empty file means a prior extraction failed.
            return out.absolutePath
        }

        val src = File(SYS_CA_DIR)
        val pems = if (src.isDirectory) {
            src.listFiles { f -> f.isFile && f.name.endsWith(".0") } ?: emptyArray()
        } else {
            emptyArray()
        }

        if (pems.isNotEmpty()) {
            try {
                out.bufferedWriter().use { w ->
                    for (f in pems) {
                        try {
                            f.bufferedReader().use { r -> r.copyTo(w) }
                            w.newLine()
                        } catch (e: Exception) {
                            Log.w(TAG, "skipped ${f.name}: ${e.message}")
                        }
                    }
                }
                if (out.length() > 1024) {
                    Log.i(TAG, "bundle ready from system store: ${out.absolutePath} (${pems.size} certs, ${out.length()} bytes)")
                    return out.absolutePath
                }
            } catch (e: Exception) {
                Log.e(TAG, "system-store bundle write failed", e)
            }
        } else {
            Log.w(TAG, "$SYS_CA_DIR unreadable or empty — using bundled fallback")
        }

        // Fallback: the bundled PEM asset.
        return try {
            context.assets.open(ASSET_NAME).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            Log.i(TAG, "bundle ready from asset: ${out.absolutePath} (${out.length()} bytes)")
            out.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "asset bundle extraction failed — TLS will fail", e)
            ""
        }
    }
}
