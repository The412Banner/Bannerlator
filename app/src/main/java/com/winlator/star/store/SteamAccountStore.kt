package com.winlator.star.store

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * True when [name] is a real persona name. Blank and the app's own stand-ins ("Friend", "Friend <id>",
 * "You") are NOT: they appear while a profile has not loaded yet and must never be saved as an account's name.
 */
internal fun isRealSteamName(name: String?): Boolean {
    if (name.isNullOrBlank()) return false
    val n = name.trim()
    return !n.equals("Friend", ignoreCase = true) && !n.equals("You", ignoreCase = true) &&
        !Regex("Friend \\d+").matches(n)
}

/**
 * Every Steam account the user has signed in to, so the app can swap between them like the real
 * Steam client's "Change account" — without asking for the password again.
 *
 * ## Model
 * The list lives in its own SharedPreferences file (`steam_accounts`, same plaintext-token posture as
 * `steam_prefs`: an app-private file, `allowBackup=false`). [SteamPrefs]'s single-account keys
 * (`username`, `refresh_token`, `steam_id_64`, `account_id`, `display_name`) stay the **active
 * account's view**, so every consumer that reads them (the CM session, SteamLite / AppSteam
 * launchers, cloud saves, friends, achievements) keeps working unchanged. The list mirrors that view:
 * [syncActive] / [touchActive] upsert the active account into the list whenever a session is saved,
 * a token rotates or a logon resolves the SteamID.
 *
 * ## Upgrade
 * 3.1.6 had no list. The first [init] on 3.1.7 finds none and mirrors the signed-in account into it,
 * so an in-place upgrade stays logged in with no re-login.
 *
 * ## Per-account data
 * The library DB (`steam_games` …) is shared by appId and is deliberately NOT cleared on a switch
 * (installed games stay installed). What the Library SHOWS is narrowed to what the active account
 * owns via [visibleAppIds] (an owned-appId snapshot per account, recorded at each library sync). The
 * account-specific side files — pending achievement queue, Save Manager status sidecar, cached
 * achievement progress — follow the account through [bindActiveFiles].
 *
 * Never logs a token or a username.
 */
object SteamAccountStore {

    private const val TAG = "BH_STEAM_ACCTS"
    private const val PREFS = "steam_accounts"
    private const val K_LIST = "accounts_v1"
    /** SteamID64 whose licenses currently fill steam_license_apps / steam_games (the DB's owner). */
    private const val K_LIBRARY_OWNER = "library_owner"
    /** SteamID64 whose pending-achievement / save-status sidecar files occupy the active-view paths. */
    private const val K_FILES_OWNER = "files_owner"
    /** Set on the 3.1.6 -> 3.1.7 upgrade: the library DB still holds the single legacy account's data. */
    private const val K_LEGACY_PENDING = "legacy_owner_pending"
    private const val K_OWNED_PREFIX = "owned_"
    /** `installer_<appId>` = SteamID64 of the account the game belongs to: its downloader, or the owner found at launch (absent = unknown). */
    private const val K_INSTALLER_PREFIX = "installer_"

    /** How long a switch waits for the target's logon before reporting "still connecting". */
    private const val SESSION_WAIT_MS = 25_000L

    /** One saved login. [refreshToken] is empty when Steam rejected it ("sign-in expired"). */
    /** A name for an account whose Steam profile name isn't known yet; never the sign-in name. */
    fun unnamedLabel(steamId64: Long): String = "Steam account …" + steamId64.toString().takeLast(4)

    data class Account(
        val steamId64: Long,
        val username: String,
        val refreshToken: String,
        val accountId: Int,
        val displayName: String,
        val avatarUrl: String,
        val lastUsed: Long,
    ) {
        val needsLogin: Boolean get() = refreshToken.isEmpty()
        /**
         * What the account chooser shows: the Steam profile name. The sign-in name is never shown (it is often
         * an e-mail address); before the profile name is known the account is named by the end of its Steam ID.
         */
        val label: String get() = if (isRealSteamName(displayName)) displayName else unnamedLabel(steamId64)

        /** Never prints the login token or the username (a data class would otherwise print every field). */
        override fun toString(): String = "Account(steamId64=$steamId64, needsLogin=$needsLogin)"
    }

    /** What [launchAccountCheck] decided for a SteamLite / Headless Steam launch. */
    sealed class LaunchCheck {
        /** Launch as usual. */
        object Proceed : LaunchCheck()

        /** The launch must not go on silently: the caller shows a dialog. */
        sealed class Warn : LaunchCheck()

        /**
         * The game belongs to a saved account ([ownerSteamId], named [ownerName]) other than the one
         * signed in ([activeName]); the launch can switch to it.
         */
        data class Mismatch(val ownerSteamId: Long, val ownerName: String, val activeName: String) : Warn()

        /** Every saved account's game list is known and none of them owns the game. */
        object NoOwner : Warn()
    }

    enum class SwitchResult {
        /** Target is logged on. */
        SWITCHED,
        /** Target is active but the session is still coming up (slow / offline network). */
        SWITCHED_CONNECTING,
        /** Target is active but Steam says it is signed in on another device. */
        SIGNED_IN_ELSEWHERE,
        /** The target's saved token is gone or was rejected: send the user to the login screen. */
        NEEDS_LOGIN,
        ALREADY_ACTIVE,
        UNKNOWN_ACCOUNT,
        /** A download is running or queued — downloads belong to the account that owns the license. */
        DOWNLOAD_ACTIVE,
        /** A game holds the Steam session (SteamLite) or is announced as "in game". */
        GAME_RUNNING,
        NO_NETWORK,
        /** Another switch is already running (rapid double tap). */
        BUSY,
        FAILED,
    }

    private val lock = Any()
    private val filesLock = Any()
    @Volatile private var appContext: Context? = null
    private val switching = AtomicBoolean(false)
    @Volatile private var ownedCache: Pair<Long, Set<Int>>? = null

    // ── Setup / migration ─────────────────────────────────────────────────────────────────────

    /** Idempotent. Migrates the single-account install into the list on first run. */
    fun init(ctx: Context) {
        if (appContext != null) return
        synchronized(lock) {
            if (appContext != null) return
            val app = ctx.applicationContext
            SteamPrefs.init(app)
            appContext = app
            val sp = prefs()
            val hadList = sp.contains(K_LIST)
            val list = readLocked().toMutableList()
            val changed = upsertActiveLocked(list, touch = !hadList)
            if (changed || !hadList) writeLocked(list)
            if (!hadList && SteamPrefs.isLoggedIn) {
                // 3.1.6 upgrade: everything on disk belongs to the one account that was signed in.
                sp.edit().putBoolean(K_LEGACY_PENDING, true).putLong(K_FILES_OWNER, activeSid()).apply()
            }
            registerSecretsLocked(list)
        }
    }

    private fun prefs(): SharedPreferences =
        appContext!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The active account's SteamID64 (prefs, else the refresh token's JWT `sub`); 0 = unknown. */
    private fun activeSid(): Long {
        val s = SteamPrefs.steamId64
        if (s != 0L) return s
        return SteamSessionManager.jwtSteamId64(SteamPrefs.refreshToken)
    }

    /** Re-register every saved token with the log redactor (call after it was cleared). */
    fun registerSecrets(ctx: Context) {
        init(ctx)
        synchronized(lock) { registerSecretsLocked(readLocked()) }
    }

    private fun registerSecretsLocked(list: List<Account>) {
        for (a in list) {
            SteamLogRedactor.registerSecret(a.username)
            SteamLogRedactor.registerSecret(a.refreshToken)
        }
    }

    // ── List ──────────────────────────────────────────────────────────────────────────────────

    /** Saved accounts, most recently used first. */
    fun list(ctx: Context): List<Account> {
        init(ctx)
        return synchronized(lock) { readLocked() }.sortedByDescending { it.lastUsed }
    }

    fun find(ctx: Context, steamId64: Long): Account? {
        if (steamId64 == 0L) return null
        return list(ctx).firstOrNull { it.steamId64 == steamId64 }
    }

    /** The signed-in account's SteamID64, 0 when signed out / unknown. */
    fun activeSteamId(ctx: Context): Long {
        init(ctx)
        return if (SteamPrefs.isLoggedIn) activeSid() else 0L
    }

    private fun readLocked(): List<Account> {
        val raw = prefs().getString(K_LIST, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Account(
                    steamId64 = o.optLong("sid", 0L),
                    username = o.optString("user", ""),
                    refreshToken = o.optString("token", ""),
                    accountId = o.optInt("aid", 0),
                    // An older build could save a stand-in ("Friend") here: read it as "unknown".
                    displayName = o.optString("name", "").takeIf { isRealSteamName(it) }.orEmpty(),
                    avatarUrl = o.optString("avatar", ""),
                    lastUsed = o.optLong("last", 0L),
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "saved account list unreadable (${t.javaClass.simpleName}) — treating as empty")
            emptyList()
        }
    }

    private fun writeLocked(list: List<Account>) {
        val arr = JSONArray()
        for (a in list) {
            arr.put(
                JSONObject()
                    .put("sid", a.steamId64)
                    .put("user", a.username)
                    .put("token", a.refreshToken)
                    .put("aid", a.accountId)
                    .put("name", a.displayName)
                    .put("avatar", a.avatarUrl)
                    .put("last", a.lastUsed),
            )
        }
        prefs().edit().putString(K_LIST, arr.toString()).apply()
    }

    /** sid match when both ids are known, otherwise the (unique) login name. */
    private fun indexOfLocked(list: List<Account>, sid: Long, user: String): Int =
        list.indexOfFirst { e ->
            if (sid != 0L && e.steamId64 != 0L) e.steamId64 == sid
            else e.username.equals(user, ignoreCase = true)
        }

    /** Mirror the active-account prefs into [list]. Returns true when [list] changed. */
    private fun upsertActiveLocked(list: MutableList<Account>, touch: Boolean): Boolean {
        if (!SteamPrefs.isLoggedIn) return false
        val user = SteamPrefs.username
        val token = SteamPrefs.refreshToken
        val sid = activeSid()
        val aid = SteamPrefs.accountId.takeIf { it != 0 } ?: (sid and 0xFFFFFFFFL).toInt()
        val name = SteamPrefs.displayName.takeIf { isRealSteamName(it) }.orEmpty()
        val now = System.currentTimeMillis()
        val idx = indexOfLocked(list, sid, user)
        if (idx < 0) {
            list.add(Account(sid, user, token, aid, name, "", now))
            return true
        }
        val old = list[idx]
        val updated = old.copy(
            steamId64 = if (sid != 0L) sid else old.steamId64,
            username = user,
            refreshToken = token,
            accountId = if (aid != 0) aid else old.accountId,
            displayName = name.ifBlank { old.displayName },
            lastUsed = if (touch) now else old.lastUsed,
        )
        if (updated == old) return false
        list[idx] = updated
        return true
    }

    /**
     * Keep the saved copy of the ACTIVE account current — a rotated refresh token, the SteamID the
     * logon just reported, the persona name. Cheap and idempotent; call after any change to the
     * active-account prefs.
     */
    fun syncActive(ctx: Context): Unit = syncActive(ctx, touch = false)

    /** [syncActive] plus "used just now" — a login or switch made this account the active one. */
    fun touchActive(ctx: Context): Unit = syncActive(ctx, touch = true)

    private fun syncActive(ctx: Context, touch: Boolean) {
        try {
            init(ctx)
            if (!SteamPrefs.isLoggedIn) return
            bindActiveFiles(ctx, activeSid())
            synchronized(lock) {
                val list = readLocked().toMutableList()
                if (upsertActiveLocked(list, touch)) writeLocked(list)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "syncActive failed (${t.javaClass.simpleName})")
        }
    }

    /** Persona name / avatar learned after logon (the Profile refresh). */
    fun updateIdentity(ctx: Context, steamId64: Long, displayName: String?, avatarUrl: String?) {
        if (steamId64 == 0L) return
        try {
            init(ctx)
            val realName = displayName?.takeIf { isRealSteamName(it) }
            // The signed-in account's own name also lives in the active prefs view (JavaSteam never writes it).
            if (realName != null && SteamPrefs.isLoggedIn && activeSid() == steamId64 && SteamPrefs.displayName != realName) {
                SteamPrefs.displayName = realName
            }
            synchronized(lock) {
                val list = readLocked().toMutableList()
                val idx = list.indexOfFirst { it.steamId64 == steamId64 }
                if (idx < 0) return
                val old = list[idx]
                val updated = old.copy(
                    displayName = realName ?: old.displayName,
                    avatarUrl = avatarUrl?.takeIf { it.isNotBlank() } ?: old.avatarUrl,
                )
                if (updated == old) return
                list[idx] = updated
                writeLocked(list)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "updateIdentity failed (${t.javaClass.simpleName})")
        }
    }

    /** Steam rejected this account's token: keep the entry (name, avatar) but make it re-login. */
    fun markNeedsLogin(ctx: Context, steamId64: Long) {
        init(ctx)
        synchronized(lock) {
            val list = readLocked().toMutableList()
            val idx = list.indexOfFirst { it.steamId64 == steamId64 }
            if (idx < 0) return
            list[idx] = list[idx].copy(refreshToken = "")
            writeLocked(list)
        }
    }

    /**
     * Forget one saved account (its login only — installed games and files are untouched). Used for
     * a NON-active account; the active one is removed by [SteamRepository.logout], which also ends
     * its session.
     */
    fun remove(ctx: Context, steamId64: Long) {
        init(ctx)
        removeWhere(ctx) { it.steamId64 == steamId64 }
    }

    /** Called by [SteamRepository.logout] (explicit sign-out) BEFORE the active prefs are cleared. */
    fun forgetActive(ctx: Context) {
        init(ctx)
        val user = SteamPrefs.username
        val sid = activeSid()
        removeWhere(ctx) { e ->
            if (sid != 0L && e.steamId64 != 0L) e.steamId64 == sid else e.username.equals(user, ignoreCase = true)
        }
    }

    private fun removeWhere(ctx: Context, match: (Account) -> Boolean) {
        val removed = ArrayList<Account>()
        synchronized(lock) {
            val list = readLocked().toMutableList()
            val iter = list.iterator()
            while (iter.hasNext()) {
                val a = iter.next()
                if (match(a)) { removed.add(a); iter.remove() }
            }
            if (removed.isEmpty()) return
            writeLocked(list)
            val sp = prefs()
            val owner = sp.getLong(K_LIBRARY_OWNER, 0L)
            val ed = sp.edit()
            for (a in removed) {
                // The DB owner's snapshot stays: it is what keeps a different account from inheriting
                // the library rows until its own sync lands (see visibleAppIds).
                if (a.steamId64 != owner) ed.remove(K_OWNED_PREFIX + a.steamId64)
            }
            // Games recorded as belonging to a removed account no longer have that account to switch to.
            val removedIds = removed.map { it.steamId64 }.filter { it != 0L }.toSet()
            for ((key, value) in sp.all) {
                if (key.startsWith(K_INSTALLER_PREFIX) && value is Long && value in removedIds) ed.remove(key)
            }
            ed.apply()
            ownedCache = null
        }
        for (a in removed) if (a.steamId64 != 0L) SteamSelfProfileCache.clearFor(ctx, a.steamId64)
    }

    // ── Library ownership (what the Library UI shows) ─────────────────────────────────────────

    /**
     * Record the account's owned appIds (called once the license -> app links are complete).
     * Also marks the account as the owner of the DB's current license rows.
     */
    fun recordOwnedApps(ctx: Context, steamId64: Long, appIds: Collection<Int>) {
        if (steamId64 == 0L) return
        init(ctx)
        synchronized(lock) {
            prefs().edit()
                .putString(K_OWNED_PREFIX + steamId64, appIds.joinToString(","))
                .putLong(K_LIBRARY_OWNER, steamId64)
                .remove(K_LEGACY_PENDING)
                .apply()
            ownedCache = steamId64 to appIds.toHashSet()
        }
    }

    /**
     * The appIds the Library may show for the active account, or null = show every cached row (the
     * pre-multi-account behaviour). Filtering only applies once it can matter: with two or more saved
     * accounts, or when the cached rows belong to a different account than the active one (a new
     * sign-in before its first sync lands — it then sees an empty library, never the old account's).
     */
    fun visibleAppIds(ctx: Context): Set<Int>? {
        init(ctx)
        val sid = if (SteamPrefs.isLoggedIn) activeSid() else 0L
        if (sid == 0L) return null
        return synchronized(lock) {
            recordLegacyOwnerLocked(ctx, sid)
            val sp = prefs()
            val owner = sp.getLong(K_LIBRARY_OWNER, 0L)
            val foreignRows = owner != 0L && owner != sid
            if (!foreignRows && readLocked().size < 2) return@synchronized null
            val cached = ownedCache
            val snap = if (cached != null && cached.first == sid) cached.second else loadOwnedLocked(sp, sid)
            snap ?: if (foreignRows) emptySet() else null
        }
    }

    private fun loadOwnedLocked(sp: SharedPreferences, sid: Long): Set<Int>? {
        val set = parseOwnedLocked(sp, sid) ?: return null
        ownedCache = sid to set
        return set
    }

    private fun parseOwnedLocked(sp: SharedPreferences, sid: Long): Set<Int>? {
        val csv = sp.getString(K_OWNED_PREFIX + sid, null) ?: return null
        val set = HashSet<Int>()
        for (part in csv.split(',')) part.trim().toIntOrNull()?.let { set.add(it) }
        return set
    }

    /**
     * The appIds [sid] owned at its last library sync, or null when it has no snapshot (it has not
     * synced since snapshots were recorded). Does not disturb the active account's cache.
     */
    private fun ownedSnapshotLocked(sp: SharedPreferences, sid: Long): Set<Int>? {
        val cached = ownedCache
        return if (cached != null && cached.first == sid) cached.second else parseOwnedLocked(sp, sid)
    }

    /** 3.1.6 upgrade: snapshot the legacy account's license rows once, before another account syncs. */
    private fun recordLegacyOwnerLocked(ctx: Context, sid: Long) {
        val sp = prefs()
        if (!sp.getBoolean(K_LEGACY_PENDING, false) || sid == 0L) return
        sp.edit().remove(K_LEGACY_PENDING).apply()
        if (sp.contains(K_LIBRARY_OWNER)) return
        val ids = try { SteamDatabase.getInstance(ctx).getLicensedAppIds() } catch (t: Throwable) { return }
        if (ids.isEmpty()) return
        sp.edit().putString(K_OWNED_PREFIX + sid, ids.joinToString(",")).putLong(K_LIBRARY_OWNER, sid).apply()
    }

    // ── Account-specific side files ───────────────────────────────────────────────────────────

    /**
     * Make the active-view sidecar files belong to [sid]: park the previous owner's pending
     * achievement queue + Save Manager status under its SteamID, bring [sid]'s back, and drop the
     * cached achievement progress (earned state is per account; the next fetch refills it). Without
     * this a queued unlock from one account could be pushed to another account's profile.
     */
    private fun bindActiveFiles(ctx: Context, sid: Long) {
        if (sid == 0L) return
        synchronized(filesLock) {
            val sp = prefs()
            val owner = sp.getLong(K_FILES_OWNER, 0L)
            if (owner == sid) return
            sp.edit().putLong(K_FILES_OWNER, sid).apply()
            try {
                SteamAchievementStore.swapPendingQueue(ctx, owner, sid)
                SaveSyncStore.swapStatusSidecar(owner, sid)
                if (owner != 0L) SteamDatabase.getInstance(ctx).clearAchievements()
            } catch (t: Throwable) {
                Log.w(TAG, "account side files swap failed (${t.javaClass.simpleName})")
            }
        }
    }

    // ── Which account a game belongs to ───────────────────────────────────────────────────────

    /**
     * Remember the ACTIVE account as the one that just installed or updated [appId] (called when a
     * download completes, by both download engines). With nobody signed in the entry is dropped, so
     * an old value can never be attributed to a download made by an unknown account.
     */
    fun recordInstaller(ctx: Context, appId: Int) {
        if (appId <= 0) return
        try {
            val sid = activeSteamId(ctx)
            val ed = prefs().edit()
            if (sid != 0L) ed.putLong(K_INSTALLER_PREFIX + appId, sid) else ed.remove(K_INSTALLER_PREFIX + appId)
            ed.apply()
        } catch (t: Throwable) {
            Log.w(TAG, "recordInstaller failed (${t.javaClass.simpleName})")
        }
    }

    /** Forget who downloaded [appId] (the game was uninstalled). A no-op until the store is initialised. */
    fun clearInstaller(appId: Int) {
        if (appContext == null) return
        try {
            prefs().edit().remove(K_INSTALLER_PREFIX + appId).apply()
        } catch (t: Throwable) {
            Log.w(TAG, "clearInstaller failed (${t.javaClass.simpleName})")
        }
    }

    /**
     * Whether a SteamLite / Headless Steam launch of [appId] may go on with the account signed in now.
     * Prefs reads only, so it is cheap enough for the main thread. Never throws.
     *
     * The account a game belongs to is the one recorded as its installer, provided it is still saved.
     * Otherwise (no record, or its account was removed) the saved accounts' owned-game snapshots decide:
     * the signed-in account first, then the most recently used others. The first owner found is recorded
     * as the game's account. When none owns it the answer is [LaunchCheck.NoOwner], but only if every
     * saved account has a snapshot: a missing one (not synced yet) makes it unknown and the launch
     * proceeds. Nobody signed in, or no [appId]: [LaunchCheck.Proceed].
     */
    fun launchAccountCheck(ctx: Context, appId: Int): LaunchCheck {
        if (appId <= 0) return LaunchCheck.Proceed
        return try {
            val active = activeSteamId(ctx)
            if (active == 0L) return LaunchCheck.Proceed
            synchronized(lock) { launchAccountCheckLocked(appId, active) }
        } catch (t: Throwable) {
            Log.w(TAG, "launchAccountCheck failed (${t.javaClass.simpleName})")
            LaunchCheck.Proceed
        }
    }

    private fun launchAccountCheckLocked(appId: Int, active: Long): LaunchCheck {
        val sp = prefs()
        val saved = readLocked().sortedByDescending { it.lastUsed }
        val recorded = sp.getLong(K_INSTALLER_PREFIX + appId, 0L)
        var owner = if (recorded != 0L && saved.any { it.steamId64 == recorded }) recorded else 0L
        if (owner == 0L) {
            val snapshots = saved.map { it.steamId64 to ownedSnapshotLocked(sp, it.steamId64) }
            val owning = snapshots.firstOrNull { it.first == active && it.second?.contains(appId) == true }
                ?: snapshots.firstOrNull { it.second?.contains(appId) == true }
            if (owning == null) {
                return if (snapshots.all { it.second != null }) LaunchCheck.NoOwner else LaunchCheck.Proceed
            }
            owner = owning.first
            sp.edit().putLong(K_INSTALLER_PREFIX + appId, owner).apply()
        }
        if (owner == active) return LaunchCheck.Proceed
        val ownerName = saved.first { it.steamId64 == owner }.label
        val activeName = saved.firstOrNull { it.steamId64 == active }?.label
            ?: SteamPrefs.displayName.takeIf { isRealSteamName(it) } ?: unnamedLabel(active)
        return LaunchCheck.Mismatch(owner, ownerName, activeName)
    }

    // ── Switching ─────────────────────────────────────────────────────────────────────────────

    /**
     * Swap the live session to saved account [steamId64] without asking for its password: end the
     * current CM session (other saved accounts are untouched), make the target the active account,
     * log it on from its saved refresh token. Runs on a worker thread; [onResult] is delivered on the
     * main thread. Safe against rapid double taps ([SwitchResult.BUSY]).
     *
     * Refused (nothing changes) while a download is running/queued ([SwitchResult.DOWNLOAD_ACTIVE]:
     * downloads belong to the account that owns the license, so the safest rule is to make the user
     * pause or cancel first), while a game holds the Steam session, and with no network.
     */
    fun switchTo(ctx: Context, steamId64: Long, onResult: (SwitchResult, Account?) -> Unit) {
        val app = ctx.applicationContext
        if (!switching.compareAndSet(false, true)) {
            onResult(SwitchResult.BUSY, null)
            return
        }
        val main = Handler(Looper.getMainLooper())
        Thread({
            var result = SwitchResult.FAILED
            var account: Account? = null
            try {
                val r = doSwitch(app, steamId64)
                result = r.first
                account = r.second
            } catch (t: Throwable) {
                Log.w(TAG, "account switch failed (${t.javaClass.simpleName})")
            } finally {
                switching.set(false)
            }
            main.post { onResult(result, account) }
        }, "SteamAccountSwitch").start()
    }

    private fun doSwitch(app: Context, steamId64: Long): Pair<SwitchResult, Account?> {
        init(app)
        val repo = SteamRepository.getInstance()
        repo.initialize(app)
        val target = find(app, steamId64) ?: return SwitchResult.UNKNOWN_ACCOUNT to null
        val leavingSid = if (SteamPrefs.isLoggedIn) activeSid() else 0L
        if (leavingSid == target.steamId64) {
            if (!repo.isLoggedIn) repo.reconnectNow()
            return SwitchResult.ALREADY_ACTIVE to target
        }
        // Blockers first: a NEEDS_LOGIN answer makes the caller end the session to open the login screen.
        sessionSwapBlocker(app)?.let { return it to target }
        if (target.needsLogin) return SwitchResult.NEEDS_LOGIN to target
        if (!hasNetwork(app)) return SwitchResult.NO_NETWORK to target

        // Anything still attributed to "whoever is active" is about to change hands.
        synchronized(lock) { recordLegacyOwnerLocked(app, leavingSid) }
        if (leavingSid != 0L) syncActive(app)   // the account we leave: keep its latest rotated token

        var started = false
        try {
            repo.endSessionForSwitch()
            bindActiveFiles(app, target.steamId64)
            synchronized(lock) {
                val list = readLocked().toMutableList()
                val idx = list.indexOfFirst { it.steamId64 == target.steamId64 }
                if (idx >= 0) {
                    list[idx] = list[idx].copy(lastUsed = System.currentTimeMillis())
                    writeLocked(list)
                }
                SteamPrefs.setActiveAccount(
                    target.username, target.refreshToken, target.steamId64, target.accountId, target.displayName,
                )
            }
            ownedCache = null
            repo.emit("AccountSwitched:${target.steamId64}")
            started = true
            val outcome = repo.startSessionForActiveAccount(SESSION_WAIT_MS)
            val result = when (outcome) {
                SteamRepository.SwitchOutcome.LOGGED_ON -> SwitchResult.SWITCHED
                SteamRepository.SwitchOutcome.PENDING -> SwitchResult.SWITCHED_CONNECTING
                SteamRepository.SwitchOutcome.SIGNED_IN_ELSEWHERE -> SwitchResult.SIGNED_IN_ELSEWHERE
                SteamRepository.SwitchOutcome.TOKEN_REJECTED -> {
                    // Read Steam's answer before the session is ended (ending it resets the status).
                    val definite = rejectionIsDefinite(repo.lastSessionStatus)
                    repo.endSessionKeepSaved()
                    if (definite) {
                        // Dead token: sign the (now empty) session out WITHOUT forgetting anyone, keep the
                        // entry flagged so the chooser says "sign-in expired", and let the caller open the
                        // login screen pre-filled. The other accounts' tokens are never touched.
                        markNeedsLogin(app, target.steamId64)
                        SwitchResult.NEEDS_LOGIN
                    } else {
                        // Throttled, rate limited or an ambiguous denial (quick switching triggers these):
                        // the saved login is not proven bad, so it is kept and the switch can be retried.
                        SwitchResult.FAILED
                    }
                }
                else -> SwitchResult.FAILED
            }
            return result to find(app, target.steamId64)
        } finally {
            if (!started) repo.cancelAccountSwitch()
        }
    }

    /**
     * True when Steam's last logon answer says the saved login itself is bad (wrong, expired or revoked).
     * Throttling, rate limits and a plain "access denied" are NOT definite: the same login may well work
     * a moment later, so it must not be erased.
     */
    private fun rejectionIsDefinite(status: String?): Boolean {
        val s = status ?: return false
        return s.contains("InvalidPassword") || s.contains("Expired") || s.contains("Revoked")
    }

    /**
     * Game screens ([com.winlator.star.XServerDisplayActivity]) currently open in this process. A game
     * session blocks an account switch whatever its launch mode (Headless Steam, Goldberg and Raw games
     * don't always announce themselves as in-game), and it covers the exit-time cloud-save and
     * achievement upload, which runs while the screen is still open.
     */
    private val gameSessions = java.util.concurrent.atomic.AtomicInteger(0)

    /** A game screen opened. Pair with [gameSessionClosed]. */
    fun gameSessionOpened() {
        gameSessions.incrementAndGet()
    }

    /** A game screen closed (never goes below zero). */
    fun gameSessionClosed() {
        gameSessions.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    /**
     * Why the live session must NOT be swapped right now, or null when it can: a game holds the Steam
     * session / is announced as in-game ([SwitchResult.GAME_RUNNING]), or a download is running or
     * queued ([SwitchResult.DOWNLOAD_ACTIVE]). Checked by a switch and by "Add account" (which also
     * ends the session). Safe from any thread.
     */
    fun sessionSwapBlocker(ctx: Context): SwitchResult? {
        val repo = SteamRepository.getInstance()
        repo.initialize(ctx.applicationContext)
        if (gameSessions.get() > 0 || repo.isSuspendedForRealSteam || repo.inGameAppId != 0) return SwitchResult.GAME_RUNNING
        if (downloadsBusy(repo)) return SwitchResult.DOWNLOAD_ACTIVE
        return null
    }

    /**
     * "Add account": end the session (keeping every saved login, this one included) so the login
     * screen starts from a clean slate, then call [onReady] on the main thread. As in the real Steam
     * client, you leave the current account to sign in to another.
     */
    fun beginAddAccount(ctx: Context, onReady: () -> Unit) {
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread({
            try {
                init(app)
                SteamRepository.getInstance().endSessionKeepSaved()
            } catch (t: Throwable) {
                Log.w(TAG, "add-account sign-out failed (${t.javaClass.simpleName})")
            }
            main.post { onReady() }
        }, "SteamAddAccount").start()
    }

    private fun downloadsBusy(repo: SteamRepository): Boolean =
        repo.isDownloadActive ||
            SteamDepotDownloader.activeDownloads.isNotEmpty() ||
            DownloadQueue.queuedCount() > 0

    private fun hasNetwork(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (t: Throwable) {
        true   // can't tell: let the logon itself decide
    }
}
