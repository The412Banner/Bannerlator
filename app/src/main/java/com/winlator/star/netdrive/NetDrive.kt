package com.winlator.star.netdrive

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The kinds of network storage a drive can be. */
enum class NetKind(val label: String, val short: String, val defaultPort: Int) {
    SMB("Windows / NAS share (SMB)", "SMB", 445),
    SFTP("SFTP", "SFTP", 22),
    WEBDAV("WebDAV", "WebDAV", 80),
    FTP("FTP", "FTP", 21),
}

/**
 * One network drive: where it is and how to log in. [folder] is where it opens (an SMB share, a folder on the
 * server; "" = the top, which for SMB lists the shares). A blank [user] logs in as a guest (SMB) or anonymously
 * (FTP). [password] stays in memory and in [NetDriveStore]'s sealed file only.
 */
data class NetDrive(
    val id: String,
    val kind: NetKind,
    val name: String,
    val host: String,
    val port: Int = 0,
    val user: String = "",
    val password: String = "",
    val folder: String = "",
    val https: Boolean = false,
) {
    val realPort: Int get() = if (port > 0) port else if (kind == NetKind.WEBDAV && https) 443 else kind.defaultPort
    val isGuest: Boolean get() = user.isBlank()

    /** Same server and place (two entries for one share are one drive). */
    fun samePlace(o: NetDrive): Boolean =
        kind == o.kind && host.equals(o.host, ignoreCase = true) && realPort == o.realPort && folder.trim('/') == o.folder.trim('/')

    // Never print the password.
    override fun toString(): String = "NetDrive($id, $kind, $name, $host:$realPort/$folder)"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("kind", kind.name).put("name", name).put("host", host).put("port", port)
        .put("user", user).put("password", password).put("folder", folder).put("https", https)

    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        fun fromJson(o: JSONObject): NetDrive? = runCatching {
            NetDrive(
                id = o.getString("id"),
                kind = NetKind.valueOf(o.getString("kind")),
                name = o.optString("name"),
                host = o.getString("host"),
                port = o.optInt("port", 0),
                user = o.optString("user"),
                password = o.optString("password"),
                folder = o.optString("folder"),
                https = o.optBoolean("https", false),
            )
        }.getOrNull()
    }
}

/**
 * The saved network drives, as one JSON list sealed with an Android Keystore AES-256-GCM key (the Xbox account
 * store's pattern). No plaintext fallback: without the Keystore nothing is saved. Android backup is off for the app,
 * so the file never leaves the device.
 */
object NetDriveStore {
    private const val TAG = "BL_NETDRIVE"
    private const val KEY_ALIAS = "bannerlator_netdrives_v1"
    private const val FILE = "drives.bin"
    private val lock = Any()
    @Volatile private var cache: List<NetDrive>? = null

    private fun file(ctx: Context): File = File(NetEngine.dir(ctx).apply { mkdirs() }, FILE)

    fun list(ctx: Context): List<NetDrive> = synchronized(lock) {
        cache?.let { return it }
        val f = file(ctx)
        val out = if (!f.isFile) emptyList() else try {
            val bytes = f.readBytes()
            require(bytes.size in 13..4_000_000)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            val arr = JSONArray(String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8))
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(NetDrive::fromJson) }
        } catch (e: Exception) {
            Log.w(TAG, "saved drives unreadable: ${e.javaClass.simpleName}")
            emptyList()
        }
        cache = out
        out
    }

    /** Adds [d], or replaces the saved drive with the same id. */
    fun save(ctx: Context, d: NetDrive) = synchronized(lock) {
        val now = list(ctx).filter { it.id != d.id } + d
        write(ctx, now)
    }

    fun remove(ctx: Context, id: String) = synchronized(lock) {
        write(ctx, list(ctx).filter { it.id != id })
    }

    private fun write(ctx: Context, drives: List<NetDrive>) {
        val arr = JSONArray().apply { drives.forEach { put(it.toJson()) } }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(arr.toString().toByteArray(Charsets.UTF_8))
        check(cipher.iv.size == 12)
        val f = file(ctx)
        val staged = File(f.parentFile, "$FILE.tmp")
        staged.writeBytes(sealed)
        check(staged.renameTo(f)) { "The network drives could not be saved" }
        cache = drives
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
