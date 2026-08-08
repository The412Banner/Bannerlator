package com.winlator.star.store

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.store.download.DownloadsButton
import com.winlator.star.store.download.Store
import com.winlator.star.store.download.StoreStyle
import com.winlator.star.ui.theme.WinlatorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import androidx.lifecycle.lifecycleScope
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

private data class FreeGameEntry(
    val title: String,
    val storeUrl: String,
    val thumbUrl: String,
    val dateRange: String,
)

class EpicFreeGamesActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "BH_EPIC_FREE"
    }

    private var statusText by mutableStateOf("")
    private var isLoading by mutableStateOf(true)
    private var currentFree by mutableStateOf<List<FreeGameEntry>>(emptyList())
    private var upcomingFree by mutableStateOf<List<FreeGameEntry>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusText = getString(com.winlator.star.R.string.epic_free_loading)
        setContent {
            WinlatorTheme {
                EpicFreeGamesScreen(
                    statusText = statusText,
                    isLoading = isLoading,
                    currentFree = currentFree,
                    upcomingFree = upcomingFree,
                    onBack = { finish() },
                    onGameClick = { g ->
                        if (g.storeUrl.isNotEmpty()) {
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(g.storeUrl)))
                            } catch (ex: Exception) {
                                Log.w(TAG, "Cannot open URL: ${g.storeUrl}")
                            }
                        }
                    },
                )
            }
        }
        fetchFreeGames()
    }

    private fun fetchFreeGames() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = "https://store-site-backend-static-ipv4.ak.epicgames.com" +
                        "/freeGamesPromotions?locale=en-US&country=US&allowCountries=US"
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")

                val code = conn.responseCode
                if (code != 200) {
                    conn.disconnect()
                    withContext(Dispatchers.Main) {
                        setStatus(getString(com.winlator.star.R.string.epic_free_http_error, code), false)
                    }
                    return@launch
                }

                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) sb.append(line)
                conn.disconnect()

                val root = JSONObject(sb.toString())
                val elements = root.optJSONObject("data")
                    ?.optJSONObject("Catalog")
                    ?.optJSONObject("searchStore")
                    ?.optJSONArray("elements")

                if (elements == null) {
                    withContext(Dispatchers.Main) {
                        setStatus(getString(com.winlator.star.R.string.epic_free_no_data), false)
                    }
                    return@launch
                }

                val current = mutableListOf<FreeGameEntry>()
                val upcoming = mutableListOf<FreeGameEntry>()

                for (i in 0 until elements.length()) {
                    val el = elements.getJSONObject(i)
                    val title = el.optString("title").ifBlank {
                        getString(com.winlator.star.R.string.epic_free_unknown_game)
                    }

                    var pageSlug = ""
                    val catalogNs = el.optJSONObject("catalogNs")
                    if (catalogNs != null) {
                        val mappings = catalogNs.optJSONArray("mappings")
                        if (mappings != null) {
                            for (m in 0 until mappings.length()) {
                                val mapping = mappings.getJSONObject(m)
                                if ("productHome" == mapping.optString("pageType")) {
                                    pageSlug = mapping.optString("pageSlug", "")
                                    break
                                }
                            }
                        }
                    }
                    if (pageSlug.isEmpty()) {
                        pageSlug = el.optString("productSlug", "")
                        if (pageSlug.endsWith("/home")) {
                            pageSlug = pageSlug.substring(0, pageSlug.length - 5)
                        }
                    }

                    val storeUrl = if (pageSlug.isEmpty()) ""
                    else "https://store.epicgames.com/en-US/p/$pageSlug"

                    var thumbUrl = ""
                    val keyImages = el.optJSONArray("keyImages")
                    if (keyImages != null) {
                        for (k in 0 until keyImages.length()) {
                            val img = keyImages.getJSONObject(k)
                            val type = img.optString("type", "")
                            if (type == "Thumbnail" || type == "OfferImageWide") {
                                thumbUrl = img.optString("url", "")
                                if (type == "Thumbnail") break
                            }
                        }
                    }

                    val promos = el.optJSONObject("promotions") ?: continue

                    val currentOffers = promos.optJSONArray("promotionalOffers")
                    if (currentOffers != null && currentOffers.length() > 0) {
                        val inner = currentOffers.getJSONObject(0).optJSONArray("promotionalOffers")
                        if (inner != null && inner.length() > 0) {
                            val discount = inner.getJSONObject(0).optJSONObject("discountSetting")
                            if (discount != null && discount.optInt("discountPercentage", -1) == 0) {
                                val endDate = inner.getJSONObject(0).optString("endDate", "")
                                current.add(
                                    FreeGameEntry(
                                        title = title,
                                        storeUrl = storeUrl,
                                        thumbUrl = thumbUrl,
                                        dateRange = formatDateRange(
                                            inner.getJSONObject(0).optString("startDate", ""),
                                            endDate,
                                        ),
                                    )
                                )
                                continue
                            }
                        }
                    }

                    val upcomingOffers = promos.optJSONArray("upcomingPromotionalOffers")
                    if (upcomingOffers != null && upcomingOffers.length() > 0) {
                        val inner = upcomingOffers.getJSONObject(0).optJSONArray("promotionalOffers")
                        if (inner != null && inner.length() > 0) {
                            val discount = inner.getJSONObject(0).optJSONObject("discountSetting")
                            if (discount != null && discount.optInt("discountPercentage", -1) == 0) {
                                val startDate = inner.getJSONObject(0).optString("startDate", "")
                                upcoming.add(
                                    FreeGameEntry(
                                        title = title,
                                        storeUrl = storeUrl,
                                        thumbUrl = thumbUrl,
                                        dateRange = formatDateRange(
                                            startDate,
                                            inner.getJSONObject(0).optString("endDate", ""),
                                        ),
                                    )
                                )
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    currentFree = current
                    upcomingFree = upcoming
                    isLoading = false
                    if (current.isEmpty() && upcoming.isEmpty()) {
                        setStatus(getString(com.winlator.star.R.string.epic_free_none_available), false)
                    } else {
                        val currentLabel = resources.getQuantityString(
                            com.winlator.star.R.plurals.epic_free_now_count,
                            current.size,
                            current.size,
                        )
                        val upcomingLabel = if (upcoming.isEmpty()) "" else resources.getQuantityString(
                            com.winlator.star.R.plurals.epic_free_coming_count,
                            upcoming.size,
                            upcoming.size,
                        )
                        setStatus(
                            if (upcomingLabel.isEmpty()) currentLabel
                            else getString(com.winlator.star.R.string.epic_free_status_joined, currentLabel, upcomingLabel),
                            false,
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetchFreeGames failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    setStatus(getString(com.winlator.star.R.string.epic_free_load_failed), false)
                }
            }
        }
    }

    private fun setStatus(msg: String, showSpinner: Boolean) {
        statusText = msg
        isLoading = showSpinner
    }

    private fun formatDateRange(start: String, end: String): String {
        val s = formatIsoDate(start)
        val e = formatIsoDate(end)
        if (s.isNotEmpty() && e.isNotEmpty()) return "$s \u2192 $e"
        if (s.isNotEmpty()) return getString(com.winlator.star.R.string.epic_free_from_date, s)
        if (e.isNotEmpty()) return getString(com.winlator.star.R.string.epic_free_until_date, e)
        return ""
    }

    private fun formatIsoDate(iso: String): String {
        if (iso.length < 10) return ""
        return try {
            val locale = resources.configuration.locales[0]
            val parser = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { isLenient = false }
            val formatter = SimpleDateFormat("MMM d", locale)
            val parsed = parser.parse(iso.substring(0, 10))
            if (parsed != null) formatter.format(parsed) else iso.substring(0, 10)
        } catch (_: Exception) {
            iso.substring(0, 10)
        }
    }
}

@Composable
private fun EpicFreeGamesScreen(
    statusText: String,
    isLoading: Boolean,
    currentFree: List<FreeGameEntry>,
    upcomingFree: List<FreeGameEntry>,
    onBack: () -> Unit,
    onGameClick: (FreeGameEntry) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onBack,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.height(40.dp),
            ) { Text("\u2190", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp) }
            Text(
                text = stringResource(com.winlator.star.R.string.epic_free_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = StoreStyle.accent(Store.EPIC), // Epic brand blue (store identity)
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
            Text(
                text = "EPIC",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .background(StoreStyle.accent(Store.EPIC), RoundedCornerShape(4.dp)) // Epic brand badge
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            // ⬇ cross-store Download Manager (global active-count badge).
            DownloadsButton()
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = statusText,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
        ) {
            if (currentFree.isNotEmpty()) {
                item {
                    SectionLabel(stringResource(com.winlator.star.R.string.epic_free_this_week), Color(0xFF00C853))
                }
                items(currentFree, key = { it.title + "_current" }) { game ->
                    FreeGameCard(game = game, isFree = true, onClick = { onGameClick(game) })
                }
            }
            if (upcomingFree.isNotEmpty()) {
                item {
                    SectionLabel(stringResource(com.winlator.star.R.string.epic_free_next_week), Color(0xFFFFAA00))
                }
                items(upcomingFree, key = { it.title + "_upcoming" }) { game ->
                    FreeGameCard(game = game, isFree = false, onClick = { onGameClick(game) })
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, color: Color) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = color,
        letterSpacing = 0.08.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
    )
}

@Composable
private fun FreeGameCard(
    game: FreeGameEntry,
    isFree: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(
            1.dp,
            // semantic free/soon accent border (not app-theme chrome)
            if (isFree) Color(0xFF0D5CA8) else Color(0xFF443300),
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(
                    if (isFree) com.winlator.star.R.string.epic_free_badge_free
                    else com.winlator.star.R.string.epic_free_badge_soon,
                ),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (isFree) Color(0xFF00C853) else Color(0xFFFFAA00),
                modifier = Modifier
                    .background(
                        if (isFree) Color(0xFF00330F) else Color(0xFF2B1A00),
                        RoundedCornerShape(3.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = game.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (game.dateRange.isNotEmpty()) {
                    Text(
                        text = game.dateRange,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (game.storeUrl.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "\u2192",
                    fontSize = 18.sp,
                    // free = Epic-brand "open store" cue; upcoming = muted
                    color = if (isFree) StoreStyle.accent(Store.EPIC) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
