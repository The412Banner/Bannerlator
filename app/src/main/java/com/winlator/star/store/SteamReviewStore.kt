package com.winlator.star.store

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * On-device cache of the Steam user-review summaries fetched by [SteamReviewScores], kept in its own
 * small database file (`steam_reviews.db`) so the main Steam database (`steam.db`) is never touched.
 * It is only a cache: if it is missing, unreadable or downgraded, the ratings are simply fetched again.
 *
 * Never throws: a failed open, read or write is logged and answered with an empty map / false.
 */
internal object SteamReviewStore {

    private const val TAG = StorefrontLog.LIBRARY
    private const val DB_NAME = "steam_reviews.db"
    private const val DB_VERSION = 1

    private const val SQL_CREATE =
        "CREATE TABLE IF NOT EXISTS reviews (" +
            "app_id INTEGER PRIMARY KEY, " +
            "percent INTEGER NOT NULL, " +
            "total INTEGER NOT NULL, " +
            "label TEXT NOT NULL DEFAULT '', " +
            "updated INTEGER NOT NULL)"

    /**
     * One cached summary. [percent] is 0-100 positive, or -1 for "no rating" (a fetched app with too
     * few reviews is stored as -1 so it is not requested again); [total] is positive + negative
     * votes; [label] is Steam's rating word or ""; [updated] is the fetch time in epoch seconds.
     */
    data class Review(val percent: Int, val total: Int, val label: String, val updated: Long)

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(SQL_CREATE)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS reviews")
            onCreate(db)
        }

        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            onUpgrade(db, oldVersion, newVersion)
        }
    }

    @Volatile
    private var helper: Helper? = null

    private fun helper(ctx: Context): Helper =
        helper ?: synchronized(this) {
            helper ?: Helper(ctx.applicationContext).also { helper = it }
        }

    /** Every cached review by appId, read with a single SELECT. Empty on any failure. */
    fun all(ctx: Context): Map<Int, Review> = try {
        val out = HashMap<Int, Review>()
        helper(ctx).readableDatabase.rawQuery(
            "SELECT app_id, percent, total, label, updated FROM reviews", null,
        ).use { c ->
            while (c.moveToNext()) {
                out[c.getInt(0)] = Review(c.getInt(1), c.getInt(2), c.getString(3) ?: "", c.getLong(4))
            }
        }
        out
    } catch (e: Exception) {
        StorefrontLog.w(TAG, "review store: could not read the cached ratings", e)
        emptyMap()
    }

    /** Insert or replace [rows] in ONE transaction: all of them are saved, or none. False on failure. */
    fun putAll(ctx: Context, rows: Map<Int, Review>): Boolean {
        if (rows.isEmpty()) return true
        return try {
            val db = helper(ctx).writableDatabase
            db.beginTransaction()
            try {
                val stmt = db.compileStatement(
                    "INSERT OR REPLACE INTO reviews (app_id, percent, total, label, updated) " +
                        "VALUES (?, ?, ?, ?, ?)",
                )
                try {
                    for ((appId, r) in rows) {
                        stmt.clearBindings()
                        stmt.bindLong(1, appId.toLong())
                        stmt.bindLong(2, r.percent.toLong())
                        stmt.bindLong(3, r.total.toLong())
                        stmt.bindString(4, r.label)
                        stmt.bindLong(5, r.updated)
                        stmt.executeInsert()
                    }
                } finally {
                    stmt.close()
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            true
        } catch (e: Exception) {
            StorefrontLog.w(TAG, "review store: could not save ${rows.size} rating(s)", e)
            false
        }
    }

    /** [game] with its cached review filled in; unchanged when [reviews] has no entry for it. */
    fun withReview(game: SteamGame, reviews: Map<Int, Review>): SteamGame {
        val r = reviews[game.appId] ?: return game
        return game.copy(
            reviewPercent = r.percent,
            reviewTotal = r.total,
            reviewDesc = r.label,
            reviewUpdated = r.updated,
        )
    }
}
