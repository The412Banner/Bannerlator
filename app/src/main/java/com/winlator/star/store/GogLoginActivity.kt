package com.winlator.star.store

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Message
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.winlator.star.R
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
        // dead ends. Do NOT reintroduce those changes ON THE MAIN WEBVIEW.
        //
        // The social-login (Google/Facebook/Apple) leg is a SEPARATE popup WebView
        // created by onCreateWindow; it is not covered by the rule above and
        // deliberately runs a real Chrome UA + third-party cookies, because that leg
        // talks to the identity provider, not to auth.gog.com.
        const val AUTH_URL =
            "https://auth.gog.com/auth" +
            "?client_id=46899977096215655" +
            "&redirect_uri=https%3A%2F%2Fembed.gog.com%2Fon_login_success%3Forigin%3Dclient" +
            "&response_type=token&layout=client2"

        private const val KEY_STATE = "bh_gog_oauth_state"

        private const val GALAXY_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) GOG Galaxy/2.0"

        // Real Chrome-on-Android UA. Must NOT contain the "; wv" token — that token is
        // one of the two signals Google uses to reject sign-in from an embedded WebView
        // (the other is the X-Requested-With header, suppressed in applyIdpHardening()).
        private const val CHROME_UA =
            "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/127.0.6533.103 Mobile Safari/537.36"

        private const val REDIRECT_PREFIX = "https://embed.gog.com/on_login_success"

        /** Appends the CSRF [state] to the base AUTH_URL. */
        @JvmStatic
        fun buildAuthUrl(state: String): String = "$AUTH_URL&state=${Uri.encode(state)}"

        /** Random URL-safe CSRF state (24 bytes → ~32 chars, well over the 16-byte floor). */
        @JvmStatic
        fun generateState(): String {
            val bytes = ByteArray(24)
            java.security.SecureRandom().nextBytes(bytes)
            return android.util.Base64.encodeToString(
                bytes,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
            )
        }

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

        /**
         * Hosts that serve a third-party identity provider's sign-in UI. These are the
         * pages that must NOT look like an embedded WebView, and the only ones we swap
         * the UA for.
         */
        @JvmStatic
        fun isGogHost(host: String?): Boolean {
            val h = host?.lowercase() ?: return false
            return h == "gog.com" || h.endsWith(".gog.com")
        }

        @JvmStatic
        fun isIdentityProviderHost(host: String?): Boolean {
            val h = host?.lowercase() ?: return false
            return h == "google.com" || h.endsWith(".google.com") ||
                h == "youtube.com" || h.endsWith(".youtube.com") ||
                h == "facebook.com" || h.endsWith(".facebook.com") ||
                h == "apple.com" || h.endsWith(".apple.com")
        }
    }

    private var webViewRef: WebView? = null
    private var popupWebView: WebView? = null

    private lateinit var contentHost: FrameLayout
    private lateinit var popupHost: FrameLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var errorPanel: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var titleText: TextView

    // CSRF state we sent on AUTH_URL; must survive WebView/Activity recreation.
    private var oauthState: String? = null

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics,
    ).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        oauthState = savedInstanceState?.getString(KEY_STATE) ?: generateState()

        setContentView(buildChrome())

        val webView = newWebView(GALAXY_UA, isPopup = false)
        webViewRef = webView
        // Index 0: buildChrome() already put popupHost and errorPanel in contentHost, and
        // they must stay ABOVE the page, not behind it.
        contentHost.addView(
            webView, 0,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    popupWebView != null -> dismissPopup()
                    webViewRef?.canGoBack() == true -> webViewRef?.goBack()
                    else -> finish()
                }
            }
        })

        webView.loadUrl(buildAuthUrl(oauthState!!))
    }

    // ---------------------------------------------------------------- chrome

    /**
     * Title bar (close + reload) → progress bar → content host. The old screen was a
     * bare WebView handed straight to setContentView, so a failed load left the user
     * staring at an undismissable white rectangle with no way back and no error text.
     */
    private fun buildChrome(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B0B0F.toInt())
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF15151C.toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        }
        titleText = TextView(this).apply {
            text = getString(R.string.gog_login_title)
            setTextColor(0xFFE6E6EA.toInt())
            textSize = 16f
            setPadding(dp(16), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val reload = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_rotate)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = getString(R.string.gog_login_reload)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { reloadLogin() }
        }
        val close = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = getString(R.string.gog_login_close)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { finish() }
        }
        bar.addView(titleText); bar.addView(reload); bar.addView(close)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3))
        }

        contentHost = FrameLayout(this).apply {
            setBackgroundColor(0xFF0B0B0F.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        popupHost = FrameLayout(this).apply {
            setBackgroundColor(0xFF0B0B0F.toInt())
            visibility = View.GONE
        }
        contentHost.addView(
            popupHost,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        errorPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF0B0B0F.toInt())
            setPadding(dp(24), dp(24), dp(24), dp(24))
            visibility = View.GONE
        }
        errorText = TextView(this).apply {
            setTextColor(0xFFE6E6EA.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
        }
        val retry = Button(this).apply {
            text = getString(R.string.gog_login_retry)
            setOnClickListener { reloadLogin() }
        }
        errorPanel.addView(errorText)
        errorPanel.addView(
            retry,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16) },
        )
        contentHost.addView(
            errorPanel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        root.addView(bar); root.addView(progressBar); root.addView(contentHost)
        return root
    }

    private fun showError(message: String) {
        runOnUiThread {
            errorText.text = message
            errorPanel.visibility = View.VISIBLE
            errorPanel.bringToFront()
            progressBar.visibility = View.GONE
        }
    }

    private fun clearError() {
        runOnUiThread { errorPanel.visibility = View.GONE }
    }

    private fun reloadLogin() {
        dismissPopup()
        clearError()
        val fresh = generateState()
        oauthState = fresh
        webViewRef?.let {
            it.settings.userAgentString = GALAXY_UA   // undo any IdP-leg UA swap
            it.loadUrl(buildAuthUrl(fresh))
        }
    }

    // -------------------------------------------------------------- webviews

    /**
     * Removes the two signals Google uses to reject sign-in from an embedded WebView:
     * the X-Requested-With header (suppressed by allow-listing zero origins — needs
     * androidx.webkit + a recent WebView provider, so it is feature-gated) and the
     * "; wv" UA token (absent from [CHROME_UA]).
     */
    private fun applyIdpHardening(webView: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            try {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(webView.settings, emptySet())
                Log.d(TAG, "X-Requested-With suppressed (empty origin allow-list)")
            } catch (e: Exception) {
                Log.w(TAG, "could not suppress X-Requested-With", e)
            }
        } else {
            Log.w(TAG, "REQUESTED_WITH_HEADER_ALLOW_LIST unsupported on this WebView build")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(userAgent: String, isPopup: Boolean): WebView = WebView(this).apply {
        setBackgroundColor(0xFF0B0B0F.toInt())   // no white flash while the page loads
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = userAgent
        // GOG's social-login buttons are window.open() popups. Without these two the
        // WebView silently drops the call and the user just sees the page do nothing.
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true
        applyIdpHardening(this)
        if (isPopup) {
            // Only the IdP leg. The main auth.gog.com view keeps default cookie policy —
            // enabling third-party cookies there was a proven dead end (see AUTH_URL note).
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }
        webViewClient = GogWebViewClient(isPopup)
        webChromeClient = GogChromeClient(isPopup)
    }

    private inner class GogChromeClient(private val isPopup: Boolean) : WebChromeClient() {
        override fun onConsoleMessage(cm: ConsoleMessage): Boolean {
            Log.d(TAG, "console[${cm.messageLevel()}] ${cm.message()} @${cm.sourceId()}:${cm.lineNumber()}")
            return true
        }

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            Log.d(TAG, "progress=$newProgress popup=$isPopup")
            progressBar.progress = newProgress
            progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
        }

        override fun onCreateWindow(
            view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?,
        ): Boolean {
            if (resultMsg == null) return false
            Log.d(TAG, "onCreateWindow dialog=$isDialog gesture=$isUserGesture")
            dismissPopup()
            val child = newWebView(CHROME_UA, isPopup = true)
            popupWebView = child
            popupHost.addView(
                child,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            popupHost.visibility = View.VISIBLE
            popupHost.bringToFront()
            titleText.text = getString(R.string.gog_login_provider_title)
            (resultMsg.obj as WebView.WebViewTransport).webView = child
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView?) {
            Log.d(TAG, "onCloseWindow popup=$isPopup")
            if (isPopup || window === popupWebView) dismissPopup()
        }
    }

    /**
     * Deferred teardown. handleImplicitRedirect() can fire from inside the POPUP's own
     * shouldOverrideUrlLoading (when the IdP hop finishes in the popup rather than the
     * opener), and destroying a WebView from within one of its own callbacks crashes
     * chromium. Posting hops to the next loop iteration, after the callback returns.
     */
    private fun dismissPopup() {
        val child = popupWebView ?: return
        // Detach the reference NOW so a popup opened in the same turn (onCreateWindow
        // replacing a stale one) is not the view the posted teardown destroys.
        popupWebView = null
        popupHost.post { destroyPopupView(child) }
    }

    private fun destroyPopupView(child: WebView) {
        child.stopLoading()
        child.webChromeClient = null
        child.loadUrl("about:blank")
        popupHost.removeView(child)
        child.destroy()
        if (popupWebView == null) {
            popupHost.visibility = View.GONE
            if (!isFinishing) titleText.text = getString(R.string.gog_login_title)
        }
    }

    private inner class GogWebViewClient(private val isPopup: Boolean) : WebViewClient() {

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            Log.d(TAG, "pageStarted[popup=$isPopup]: ${StoreLog.redactUrl(url)}")
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            Log.d(TAG, "pageFinished[popup=$isPopup]: ${StoreLog.redactUrl(url)}")
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            Log.e(TAG, "recvError: ${error?.errorCode} ${error?.description} url=${StoreLog.redactUrl(request?.url?.toString())} mainFrame=${request?.isForMainFrame}")
            if (request?.isForMainFrame == true) {
                showError(getString(R.string.gog_login_error_network, error?.description ?: ""))
            }
        }

        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
            val status = errorResponse?.statusCode ?: 0
            Log.e(TAG, "recvHttpError: $status ${errorResponse?.reasonPhrase} url=${StoreLog.redactUrl(request?.url?.toString())} mainFrame=${request?.isForMainFrame}")
            if (request?.isForMainFrame != true) return
            // The specific failure this screen used to render as a blank white page:
            // Google refuses OAuth from an embedded browser with 403 disallowed_useragent.
            if (isIdentityProviderHost(request.url?.host) && (status == 403 || status == 400)) {
                showError(getString(R.string.gog_login_error_idp_blocked))
            } else {
                showError(getString(R.string.gog_login_error_http, status))
            }
        }

        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            Log.e(TAG, "recvSslError: $error")
            // Do NOT proceed() — a real cert error should surface, not be silently bypassed.
            handler?.cancel()
            showError(getString(R.string.gog_login_error_ssl))
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            route(view, request.url)

        @Suppress("DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
            route(view, Uri.parse(url))

        /**
         * Both the main view and the popup can be the frame that lands on the redirect,
         * depending on whether GOG finishes the flow in the opener or in the popup.
         */
        private fun route(view: WebView, uri: Uri): Boolean {
            if (uri.toString().startsWith(REDIRECT_PREFIX)) {
                handleImplicitRedirect(uri)
                return true
            }
            // Back on GOG after an in-place IdP hop: restore the Galaxy UA. A plain
            // Chrome UA makes auth.gog.com serve a login form that never renders here
            // (commit 70ddcaab), so the Chrome UA must not outlive the IdP leg.
            if (!isPopup && isGogHost(uri.host) &&
                view.settings.userAgentString != GALAXY_UA
            ) {
                Log.d(TAG, "back on GOG → restoring Galaxy UA")
                view.settings.userAgentString = GALAXY_UA
                return false
            }
            // A main-frame hop to the IdP (GOG sometimes navigates in place instead of
            // opening a popup). Swap to the Chrome UA before the request goes out —
            // these hops are always GETs, so re-issuing via loadUrl loses nothing.
            if (!isPopup && isIdentityProviderHost(uri.host) &&
                view.settings.userAgentString != CHROME_UA
            ) {
                Log.d(TAG, "main-frame IdP hop → switching to Chrome UA")
                view.settings.userAgentString = CHROME_UA
                CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
                view.loadUrl(uri.toString())
                return true
            }
            return false
        }

        private fun handleImplicitRedirect(uri: Uri) {
            val fragment = uri.fragment ?: return
            val frag = Uri.parse("x://x?$fragment")

            // CSRF: we send a `state` on AUTH_URL and validate it if the auth server
            // echoes it back. MISMATCH-ONLY, deliberately: device test 2026-09-04 proved
            // GOG returns NO state at all on the social-login path (the provider round-trip
            // goes through external-accounts.gog.com/login/providers/<p>/back), so the
            // stricter "missing counts as mismatch" rule threw away a perfectly good
            // access_token and bounced the user back to the login form. This is the relaxation
            // the original note in d471bd26 called for. A present-but-wrong state is still
            // rejected, which is the case a forged redirect would actually produce.
            val expected = oauthState
            val returnedState = frag.getQueryParameter("state")
            if (expected != null && returnedState != null && returnedState != expected) {
                Log.e(TAG, "OAuth state mismatch — rejecting redirect")
                rejectLogin(getString(R.string.gog_login_error_verification))
                return
            }
            if (returnedState == null) Log.d(TAG, "redirect carried no state (expected on social login)")

            val accessToken = frag.getQueryParameter("access_token")
            if (accessToken == null) {
                // Name the keys, never the values — a fragment carries live tokens.
                Log.e(TAG, "redirect had no access_token; fragment keys=${frag.queryParameterNames}")
                rejectLogin(getString(R.string.gog_login_error_verification))
                return
            }
            val refreshToken = frag.getQueryParameter("refresh_token")
            val userId = frag.getQueryParameter("user_id")

            dismissPopup()
            runOnUiThread {
                clearError()
                titleText.text = getString(R.string.gog_login_finishing)
                webViewRef?.loadData(
                    "<html><body style='background:#0b0b0f;color:#e6e6ea;font-family:sans-serif;" +
                    "font-size:20px;text-align:center;padding-top:40%'>" +
                    "Logging in to GOG...</body></html>",
                    "text/html", "UTF-8",
                )
            }

            lifecycleScope.launch(Dispatchers.IO) {
                loginRunnable(accessToken, refreshToken, userId)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        oauthState?.let { outState.putString(KEY_STATE, it) }
    }

    override fun onDestroy() {
        popupWebView?.let { popupWebView = null; destroyPopupView(it) }
        webViewRef?.let {
            it.webChromeClient = null
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        webViewRef = null
        super.onDestroy()
    }

    /** Shows a themed error and reloads the login page with a fresh CSRF state. */
    private fun rejectLogin(message: String) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                android.app.AlertDialog.Builder(this, R.style.StoreAlertDialogDark)
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .show()
            }
            reloadLogin()
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

            Log.d(TAG, "GOG login saved OK")   // don't log username (PII)
            withContext(Dispatchers.Main) { finish() }
        } catch (e: Exception) {
            Log.e(TAG, "Login post-processing failed", e)
            withContext(Dispatchers.Main) {
                // Not a Compose screen (plain views via setContentView), so the shared
                // UninstallResultBar can't apply — use the themed dark dialog instead of a
                // black-box Toast.
                if (!isFinishing && !isDestroyed) {
                    android.app.AlertDialog.Builder(this@GogLoginActivity, R.style.StoreAlertDialogDark)
                        .setMessage(getString(R.string.gog_login_error_generic))
                        .setPositiveButton("OK", null)
                        .show()
                }
                reloadLogin()
            }
        }
    }
}
