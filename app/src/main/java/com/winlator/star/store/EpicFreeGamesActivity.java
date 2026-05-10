/*
 * Epic Games Free Games screen for BannerHub
 *
 * Fetches the freeGamesPromotions endpoint (no auth required) and displays
 * currently free and upcoming free games. Each card is tappable and opens
 * the Epic Store page in the device browser.
 */
package com.winlator.star.store;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen Free Games activity for Epic Games.
 *
 * Shows two sections:
 *   • FREE THIS WEEK  — currently 100% off promotions
 *   • FREE NEXT WEEK  — upcoming 100% off promotions
 *
 * Tapping any card opens the Epic Store page in the system browser.
 * No authentication required.
 */
public class EpicFreeGamesActivity extends Activity {

    private static final String TAG = "BH_EPIC_FREE";

    // Epic brand colours
    private static final int COLOR_ACCENT  = 0xFF0078F0;
    private static final int COLOR_ROOT_BG = 0xFF0D0D0D;
    private static final int COLOR_HDR_BG  = 0xFF0F1117;
    private static final int COLOR_CARD_BG = 0xFF0A1A2A;
    private static final int COLOR_CARD_BD = 0xFF0D5CA8;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private LinearLayout contentLayout;
    private ProgressBar  progressBar;
    private TextView     statusTV;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        fetchFreeGames();
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_ROOT_BG);

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setBackgroundColor(COLOR_HDR_BG);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), dp(8), dp(8), dp(8));

        Button backBtn = new Button(this);
        backBtn.setText("←");
        backBtn.setTextColor(0xFFFFFFFF);
        GradientDrawable backBtnBg = new GradientDrawable();
        backBtnBg.setColor(0xFF333333);
        backBtnBg.setCornerRadius(dp(4));
        backBtn.setBackground(backBtnBg);
        backBtn.setTextSize(16f);
        backBtn.setPadding(dp(12), 0, dp(12), 0);
        backBtn.setOnFocusChangeListener((v, hasFocus) -> {
            backBtnBg.setColor(hasFocus ? 0xFF555555 : 0xFF333333);
            backBtnBg.setStroke(hasFocus ? dp(2) : 0, hasFocus ? 0xFFFFD700 : 0x00000000);
        });
        backBtn.setOnClickListener(v -> finish());
        header.addView(backBtn, new LinearLayout.LayoutParams(-2, dp(40)));

        TextView titleTV = new TextView(this);
        titleTV.setText("Free Games");
        titleTV.setTextColor(COLOR_ACCENT);
        titleTV.setTextSize(18f);
        titleTV.setTypeface(null, Typeface.BOLD);
        titleTV.setPadding(dp(12), 0, 0, 0);
        header.addView(titleTV, new LinearLayout.LayoutParams(0, -2, 1f));

        // FREE badge in header
        TextView freeBadge = new TextView(this);
        freeBadge.setText("EPIC");
        freeBadge.setTextColor(0xFFFFFFFF);
        freeBadge.setTextSize(10f);
        freeBadge.setTypeface(null, Typeface.BOLD);
        freeBadge.setPadding(dp(8), dp(4), dp(8), dp(4));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(COLOR_ACCENT);
        badgeBg.setCornerRadius(dp(4));
        freeBadge.setBackground(badgeBg);
        header.addView(freeBadge, new LinearLayout.LayoutParams(-2, -2));

        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        // Status / progress row
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setBackgroundColor(0xFF111111);
        statusRow.setPadding(dp(12), dp(6), dp(12), dp(6));

        progressBar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleSmall);
        progressBar.setIndeterminate(true);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(dp(18), dp(18));
        pbLp.rightMargin = dp(8);
        statusRow.addView(progressBar, pbLp);

        statusTV = new TextView(this);
        statusTV.setText("Loading free games…");
        statusTV.setTextColor(0xFFCCCCCC);
        statusTV.setTextSize(13f);
        statusRow.addView(statusTV, new LinearLayout.LayoutParams(0, -2, 1f));

        root.addView(statusRow, new LinearLayout.LayoutParams(-1, -2));

        // Scrollable content
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(COLOR_ROOT_BG);

        contentLayout = new LinearLayout(this);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setPadding(dp(12), dp(12), dp(12), dp(12));
        scrollView.addView(contentLayout, new FrameLayout.LayoutParams(-1, -2));

        root.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    // ── Fetch + parse ─────────────────────────────────────────────────────────

    private void fetchFreeGames() {
        new Thread(() -> {
            try {
                String url = "https://store-site-backend-static-ipv4.ak.epicgames.com"
                        + "/freeGamesPromotions?locale=en-US&country=US&allowCountries=US";
                HttpURLConnection conn =
                        (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                int code = conn.getResponseCode();
                if (code != 200) {
                    conn.disconnect();
                    uiHandler.post(() -> setStatus("Could not load free games (HTTP " + code + ")", false));
                    return;
                }

                BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                conn.disconnect();

                JSONObject root = new JSONObject(sb.toString());
                JSONArray elements = root.optJSONObject("data")
                        .optJSONObject("Catalog")
                        .optJSONObject("searchStore")
                        .optJSONArray("elements");

                if (elements == null) {
                    uiHandler.post(() -> setStatus("No data returned from Epic", false));
                    return;
                }

                List<FreeGameEntry> currentFree  = new ArrayList<>();
                List<FreeGameEntry> upcomingFree = new ArrayList<>();

                for (int i = 0; i < elements.length(); i++) {
                    JSONObject el = elements.getJSONObject(i);
                    String title = el.optString("title", "Unknown");

                    // Build store URL from catalogNs.mappings[].pageSlug (pageType=productHome)
                    String pageSlug = "";
                    JSONObject catalogNs = el.optJSONObject("catalogNs");
                    if (catalogNs != null) {
                        JSONArray mappings = catalogNs.optJSONArray("mappings");
                        if (mappings != null) {
                            for (int m = 0; m < mappings.length(); m++) {
                                JSONObject mapping = mappings.getJSONObject(m);
                                if ("productHome".equals(mapping.optString("pageType"))) {
                                    pageSlug = mapping.optString("pageSlug", "");
                                    break;
                                }
                            }
                        }
                    }
                    // Fallback: productSlug
                    if (pageSlug.isEmpty()) {
                        pageSlug = el.optString("productSlug", "");
                        // strip /home suffix if present
                        if (pageSlug.endsWith("/home")) {
                            pageSlug = pageSlug.substring(0, pageSlug.length() - 5);
                        }
                    }

                    String storeUrl = pageSlug.isEmpty()
                            ? ""
                            : "https://store.epicgames.com/en-US/p/" + pageSlug;

                    // Thumbnail URL
                    String thumbUrl = "";
                    JSONArray keyImages = el.optJSONArray("keyImages");
                    if (keyImages != null) {
                        for (int k = 0; k < keyImages.length(); k++) {
                            JSONObject img = keyImages.getJSONObject(k);
                            String type = img.optString("type", "");
                            if ("Thumbnail".equals(type) || "OfferImageWide".equals(type)) {
                                thumbUrl = img.optString("url", "");
                                if ("Thumbnail".equals(type)) break; // prefer Thumbnail
                            }
                        }
                    }

                    JSONObject promos = el.optJSONObject("promotions");
                    if (promos == null) continue;

                    // Currently free
                    JSONArray currentOffers = promos.optJSONArray("promotionalOffers");
                    if (currentOffers != null && currentOffers.length() > 0) {
                        JSONArray inner = currentOffers.getJSONObject(0)
                                .optJSONArray("promotionalOffers");
                        if (inner != null && inner.length() > 0) {
                            JSONObject discount = inner.getJSONObject(0)
                                    .optJSONObject("discountSetting");
                            if (discount != null && discount.optInt("discountPercentage", -1) == 0) {
                                // Grab end date
                                String endDate = inner.getJSONObject(0)
                                        .optString("endDate", "");
                                currentFree.add(new FreeGameEntry(title, storeUrl, thumbUrl,
                                        formatDateRange(
                                                inner.getJSONObject(0).optString("startDate", ""),
                                                endDate)));
                                continue;
                            }
                        }
                    }

                    // Upcoming free
                    JSONArray upcomingOffers = promos.optJSONArray("upcomingPromotionalOffers");
                    if (upcomingOffers != null && upcomingOffers.length() > 0) {
                        JSONArray inner = upcomingOffers.getJSONObject(0)
                                .optJSONArray("promotionalOffers");
                        if (inner != null && inner.length() > 0) {
                            JSONObject discount = inner.getJSONObject(0)
                                    .optJSONObject("discountSetting");
                            if (discount != null && discount.optInt("discountPercentage", -1) == 0) {
                                String startDate = inner.getJSONObject(0)
                                        .optString("startDate", "");
                                upcomingFree.add(new FreeGameEntry(title, storeUrl, thumbUrl,
                                        formatDateRange(startDate,
                                                inner.getJSONObject(0).optString("endDate", ""))));
                            }
                        }
                    }
                }

                final List<FreeGameEntry> fCurrent  = currentFree;
                final List<FreeGameEntry> fUpcoming = upcomingFree;
                uiHandler.post(() -> renderGames(fCurrent, fUpcoming));

            } catch (Exception e) {
                Log.w(TAG, "fetchFreeGames failed: " + e.getMessage());
                uiHandler.post(() -> setStatus("Failed to load free games", false));
            }
        }, "epic-free-fetch").start();
    }

    // ── Render ────────────────────────────────────────────────────────────────

    private void renderGames(List<FreeGameEntry> current, List<FreeGameEntry> upcoming) {
        progressBar.setVisibility(android.view.View.GONE);

        if (current.isEmpty() && upcoming.isEmpty()) {
            setStatus("No free games available right now", false);
            return;
        }

        setStatus(current.size() + " free now" +
                (upcoming.isEmpty() ? "" : "  •  " + upcoming.size() + " coming soon"), false);

        if (!current.isEmpty()) {
            contentLayout.addView(makeSectionLabel("FREE THIS WEEK", 0xFF00C853));
            for (FreeGameEntry g : current) {
                contentLayout.addView(makeGameCard(g, true));
            }
        }

        if (!upcoming.isEmpty()) {
            contentLayout.addView(makeSectionLabel("FREE NEXT WEEK", 0xFFFFAA00));
            for (FreeGameEntry g : upcoming) {
                contentLayout.addView(makeGameCard(g, false));
            }
        }
    }

    private TextView makeSectionLabel(String text, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(11f);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(6);
        tv.setLayoutParams(lp);
        return tv;
    }

    private LinearLayout makeGameCard(FreeGameEntry g, boolean isFree) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_CARD_BG);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), isFree ? COLOR_CARD_BD : 0xFF443300);
        card.setBackground(bg);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.bottomMargin = dp(8);
        card.setLayoutParams(cardLp);

        // Badge
        TextView badge = new TextView(this);
        badge.setText(isFree ? "FREE" : "SOON");
        badge.setTextColor(isFree ? 0xFF00C853 : 0xFFFFAA00);
        badge.setTextSize(10f);
        badge.setTypeface(null, Typeface.BOLD);
        badge.setPadding(dp(6), dp(3), dp(6), dp(3));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(isFree ? 0xFF00330F : 0xFF2B1A00);
        badgeBg.setCornerRadius(dp(3));
        badge.setBackground(badgeBg);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(-2, -2);
        badgeLp.rightMargin = dp(12);
        card.addView(badge, badgeLp);

        // Text column
        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        card.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView titleTV = new TextView(this);
        titleTV.setText(g.title);
        titleTV.setTextColor(0xFFEEEEEE);
        titleTV.setTextSize(14f);
        titleTV.setTypeface(null, Typeface.BOLD);
        titleTV.setMaxLines(2);
        titleTV.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textCol.addView(titleTV, new LinearLayout.LayoutParams(-1, -2));

        if (!g.dateRange.isEmpty()) {
            TextView dateTV = new TextView(this);
            dateTV.setText(g.dateRange);
            dateTV.setTextColor(0xFF888888);
            dateTV.setTextSize(11f);
            LinearLayout.LayoutParams dateLp = new LinearLayout.LayoutParams(-2, -2);
            dateLp.topMargin = dp(2);
            textCol.addView(dateTV, dateLp);
        }

        // Arrow / link indicator if URL available
        if (!g.storeUrl.isEmpty()) {
            TextView arrowTV = new TextView(this);
            arrowTV.setText("→");
            arrowTV.setTextColor(isFree ? 0xFF0078F0 : 0xFF888866);
            arrowTV.setTextSize(18f);
            LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(-2, -2);
            arrowLp.leftMargin = dp(8);
            card.addView(arrowTV, arrowLp);

            card.setClickable(true);
            card.setFocusable(true);
            card.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(g.storeUrl)));
                } catch (Exception ex) {
                    Log.w(TAG, "Cannot open URL: " + g.storeUrl);
                }
            });
            card.setOnFocusChangeListener((v, hasFocus) -> {
                bg.setStroke(hasFocus ? dp(2) : dp(1),
                        hasFocus ? 0xFFFFD700 : (isFree ? COLOR_CARD_BD : 0xFF443300));
            });
        }

        return card;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void setStatus(String msg, boolean showSpinner) {
        statusTV.setText(msg);
        progressBar.setVisibility(showSpinner
                ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    /** Format an ISO date range like "Dec 19" → "Jan 2" */
    private String formatDateRange(String start, String end) {
        String s = formatIsoDate(start);
        String e = formatIsoDate(end);
        if (!s.isEmpty() && !e.isEmpty()) return s + " → " + e;
        if (!s.isEmpty()) return "From " + s;
        if (!e.isEmpty()) return "Until " + e;
        return "";
    }

    private String formatIsoDate(String iso) {
        if (iso == null || iso.length() < 10) return "";
        try {
            // iso = "2024-12-19T..." or "2024-12-19"
            String[] parts = iso.substring(0, 10).split("-");
            if (parts.length < 3) return iso.substring(0, 10);
            int month = Integer.parseInt(parts[1]);
            int day   = Integer.parseInt(parts[2]);
            String[] months = {"Jan","Feb","Mar","Apr","May","Jun",
                               "Jul","Aug","Sep","Oct","Nov","Dec"};
            String mon = (month >= 1 && month <= 12) ? months[month - 1] : parts[1];
            return mon + " " + day;
        } catch (Exception e) {
            return iso.substring(0, 10);
        }
    }

    private int dp(int dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp,
                getResources().getDisplayMetrics()));
    }

    // ── Data model ────────────────────────────────────────────────────────────

    private static class FreeGameEntry {
        final String title;
        final String storeUrl;
        final String thumbUrl;
        final String dateRange;

        FreeGameEntry(String title, String storeUrl, String thumbUrl, String dateRange) {
            this.title     = title;
            this.storeUrl  = storeUrl;
            this.thumbUrl  = thumbUrl;
            this.dateRange = dateRange;
        }
    }
}
