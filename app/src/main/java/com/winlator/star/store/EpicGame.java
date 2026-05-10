/*
 * Epic Games integration for BannerHub
 *
 * Credits: The Epic Games Store API pipeline, OAuth flow, manifest download
 * architecture, CDN selection logic, chunk decompression, and launch arguments
 * are based on the research and implementation of The GameNative Team.
 * https://github.com/utkarshdalal/GameNative
 */
package com.winlator.star.store;

/**
 * Data model for one Epic Games library entry.
 *
 * Key identifiers:
 *   appName      — Legendary/Epic app slug (e.g. "Samorost3", "fn") — used in manifest URL and launch args
 *   namespace    — Epic product namespace (e.g. "snapcathq") — required for catalog + manifest API
 *   catalogItemId— Epic catalog UUID — required for catalog + manifest API
 */
public class EpicGame {
    public String appName       = "";   // from library API records[].appName
    public String namespace     = "";   // from library API records[].namespace
    public String catalogItemId = "";   // from library API records[].catalogItemId
    public String title         = "";
    public String developer     = "";
    public String description   = "";
    public String artCover      = "";   // DieselGameBoxTall — tall portrait art
    public String artSquare     = "";   // DieselGameBox or Thumbnail
    public String version       = "";   // versionId from manifest API (for update checks)
    public boolean isInstalled  = false;
    public String installPath   = "";
    public long   installSize   = 0L;
    public boolean canRunOffline = true;
    public boolean isDLC                 = false;
    public String  baseGameCatalogItemId = "";  // catalog ID of the base game (set when isDLC=true)
    public String  releaseDate           = "";  // ISO date from catalog viewableDate / effectiveDate
}
