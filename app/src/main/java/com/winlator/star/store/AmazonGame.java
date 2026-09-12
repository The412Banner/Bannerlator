package com.winlator.star.store;

/**
 * Lightweight data holder for an Amazon Games library entry.
 *
 * Field notes:
 *  - productId:     "amzn1.adg.product.XXXX" — unique game identifier
 *  - entitlementId: top-level UUID from GetEntitlements — used for GetGameDownload
 *  - productSku:    FuelPump AMAZON_GAMES_FUEL_PRODUCT_SKU env var
 *  - isInstalled:   set by AmazonDownloadManager on successful install
 *  - installPath:   absolute path to install directory
 *  - versionId:     from GetGameDownload response, for update checks
 */
public class AmazonGame {

    public String productId      = "";
    public String entitlementId  = "";
    public String title          = "";
    public String artUrl         = "";   // iconUrl — used as cover art
    public String heroUrl        = "";   // backgroundUrl1
    public String developer      = "";
    public String publisher      = "";
    public String productSku     = "";
    public boolean isInstalled   = false;
    public String installPath    = "";
    public String versionId      = "";
    public long   downloadSize   = 0L;
    public long   installSize    = 0L;
    public boolean isDLC         = false;
    public String parentProductId = "";  // set when isDLC=true
    // Media for the detail page's "Media" tab (productDetail.details.screenshots[] / videos[] /
    // trailerImageUrl). Empty until the library is re-synced after this field was added.
    public java.util.List<String> screenshots = new java.util.ArrayList<>();
    public java.util.List<String> videos      = new java.util.ArrayList<>();   // direct mp4 URLs
    public String trailerImageUrl = "";  // poster for the first trailer when Amazon ships one

    public AmazonGame() {}

    /** Returns just the short game ID part after the last dot (for display). */
    public String shortId() {
        int dot = productId.lastIndexOf('.');
        return (dot >= 0 && dot < productId.length() - 1)
               ? productId.substring(dot + 1) : productId;
    }
}
