package com.winlator.star.store;

/** Simple data class holding one GOG game's metadata. */
public class GogGame {
    public final String gameId;
    public final String title;
    public final String imageUrl;
    public final String description;
    public final String developer;
    public final String category;
    public final int generation; // 1 or 2 (0 = unknown)
    /**
     * NEW (gap #8): gamesdb.gog.com 2:3 vertical box-art URL, or null until backfilled / when the
     * gamesdb mapping misses. Purely additive — every existing caller uses the 7-arg constructor
     * below and gets null, and all cover rendering falls back to {@link #imageUrl} when this is null.
     */
    public final String verticalCover;

    /** Backward-compatible 7-arg constructor (no vertical cover). Delegates with verticalCover=null. */
    public GogGame(String gameId, String title, String imageUrl,
                   String description, String developer, String category, int generation) {
        this(gameId, title, imageUrl, description, developer, category, generation, null);
    }

    public GogGame(String gameId, String title, String imageUrl,
                   String description, String developer, String category, int generation,
                   String verticalCover) {
        this.gameId     = gameId;
        this.title      = title;
        this.imageUrl   = imageUrl;
        this.description = description;
        this.developer  = developer;
        this.category   = category;
        this.generation = generation;
        this.verticalCover = verticalCover;
    }
}
