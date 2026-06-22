package com.winlator.star.store

import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class GogLoginActivity : ComponentActivity() {

    companion object {
        private const val TAG = "BH_GOG"
        // This config mirrors the proven-working star-compose store-integration
        // GogLoginActivity (the version that rendered the GOG login fine). Keep it
        // exactly: layout=client2 + the GOG Galaxy UA + a plain full-screen WebView
        // (setContentView). The white screen was a regression from the marcescence
        // Compose rewrite that hosted the WebView in a Compose AndroidView and then
        // mutated these params (drop layout / change UA / third-party cookies) — all
        // dead ends. Do NOT reintroduce those changes.
        const val AUTH_URL =
            "https://auth.gog.com/auth" +
            "?client_id=46899977096215655" +
            "&redirect_uri=https%3A%2F%2Fembed.gog.com%2Fon_login_success%3Forigin%3Dclient" +
            "&response_type=token&layout=client2"

        @JvmStatic
        fun parseJsonStringField(json: String?, key: String?): String? {
            if (json == null || key == null) return null
            val search = "\"$key\":\""
            val idx = json.indexOf(search)
            if (idx < 0) return null
            val start = idx + search.length
            val end = json.indexOf('"', start)
            if (end < 0) return null
            return json.substring(start, end)
        }
    }

    private var webViewRef: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) GOG Galaxy/2.0"
            webViewClient = GogWebViewClient()
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(cm: ConsoleMessage): Boolean {
                    Log.d(TAG, "console[${cm.messageLevel()}] ${cm.message()} @${cm.sourceId()}:${cm.lineNumber()}")
                    return true
                }
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    Log.d(TAG, "progress=$newProgress")
                }
            }
        }
        webViewRef = webView
        setContentView(webView)
        webView.loadUrl(AUTH_URL)
    }

    private inner class GogWebViewClient : WebViewClient() {

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            Log.d(TAG, "pageStarted: $url")
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            Log.d(TAG, "pageFinished: $url")
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            Log.e(TAG, "recvError: ${error?.errorCode} ${error?.description} url=${request?.url} mainFrame=${request?.isForMainFrame}")
        }

        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
            Log.e(TAG, "recvHttpError: ${errorResponse?.statusCode} ${errorResponse?.reasonPhrase} url=${request?.url} mainFrame=${request?.isForMainFrame}")
        }

        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            Log.e(TAG, "recvSslError: $error")
            // Do NOT proceed() — a real cert error should surface, not be silently bypassed.
            handler?.cancel()
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (uri.toString().startsWith("https://embed.gog.com/on_login_success")) {
                handleImplicitRedirect(uri)
                return true
            }
            return false
        }

        @Suppress("DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            if (url.startsWith("https://embed.gog.com/on_login_success")) {
                handleImplicitRedirect(Uri.parse(url))
                return true
            }
            return false
        }

        private fun handleImplicitRedirect(uri: Uri) {
            val fragment = uri.fragment ?: return
            val frag = Uri.parse("x://x?$fragment")
            val accessToken = frag.getQueryParameter("access_token") ?: return
            val refreshToken = frag.getQueryParameter("refresh_token")
            val userId = frag.getQueryParameter("user_id")

            webViewRef?.loadData(
                "<html><body style='background:#000;color:#ccc;font-family:sans-serif;" +
                "font-size:20px;text-align:center;padding-top:40%'>" +
                "Logging in to GOG...</body></html>",
                "text/html", "UTF-8",
            )

            lifecycleScope.launch(Dispatchers.IO) {
                loginRunnable(accessToken, refreshToken, userId)
            }
        }
    }

    private suspend fun loginRunnable(accessToken: String, refreshToken: String?, userId: String?) {
        try {
            var username = "Unknown"
            try {
                val url = URL("https://embed.gog.com/userData.json")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.setRequestProperty("Authorization", "Bearer $accessToken")
                val sb = StringBuilder()
                BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { br ->
                    var line: String?
                    while (br.readLine().also { line = it } != null) sb.append(line)
                }
                conn.disconnect()
                val parsed = parseJsonStringField(sb.toString(), "username")
                if (parsed != null) username = parsed
            } catch (_: Exception) {}

            val ed = getSharedPreferences("bh_gog_prefs", 0).edit()
            ed.putString("access_token", accessToken)
            if (refreshToken != null) ed.putString("refresh_token", refreshToken)
            if (userId != null) ed.putString("user_id", userId)
            ed.putString("username", username)
            val nowSec = System.currentTimeMillis() / 1000L
            ed.putInt("bh_gog_login_time", nowSec.toInt())
            ed.putInt("bh_gog_expires_in", 3600)
            ed.apply()

            Log.d(TAG, "Login saved for: $username")
            withContext(Dispatchers.Main) { finish() }
        } catch (e: Exception) {
            Log.e(TAG, "Login post-processing failed", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@GogLoginActivity, "Login error, please try again", Toast.LENGTH_SHORT).show()
                webViewRef?.loadUrl(AUTH_URL)
            }
        }
    }
}
