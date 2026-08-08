package com.winlator.star.store

import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.winlator.star.R
import com.winlator.star.ui.theme.WinlatorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import java.util.concurrent.atomic.AtomicBoolean

class EpicLoginActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "BH_EPIC"
        private const val AUTH_URL = "https://www.epicgames.com/id/login" +
                "?redirectUrl=https%3A%2F%2Fwww.epicgames.com%2Fid%2Fapi%2Fredirect" +
                "%3FclientId%3D" + EpicAuthClient.CLIENT_ID +
                "%26responseType%3Dcode"
        private const val REDIRECT_HOST = "https://www.epicgames.com/id/api/redirect"
    }

    private val codeCaptured = AtomicBoolean(false)

    // Themed auto-dismiss bar — system Toasts render as an unreadable black box on this ROM
    // (targetSDK 28); reuse the shared UninstallResultBar for readable feedback.
    private var resultBarMsg by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "EpicLoginActivity: loading auth page")
        setContent {
            WinlatorTheme {
                EpicLoginWebView(
                    authUrl = AUTH_URL,
                    redirectHost = REDIRECT_HOST,
                    codeCaptured = codeCaptured,
                    onJsonPage = { view -> handleJsonPage(view) },
                )
                resultBarMsg?.let { UninstallResultBar(it) { resultBarMsg = null } }
            }
        }
    }

    private fun handleJsonPage(view: WebView) {
        if (!codeCaptured.compareAndSet(false, true)) return
        view.stopLoading()
        view.evaluateJavascript(
            "(function(){ try { return document.body.innerText; } catch(e){ return ''; } })()",
        ) { json ->
            if (json == null) { codeCaptured.set(false); return@evaluateJavascript }
            var result = json
            if (result.startsWith("\"")) result = result.substring(1, result.length - 1)
            result = result.replace("\\n", "").replace("\\r", "").replace("\\\"", "\"")

            val authCode = extractField(result, "authorizationCode")
            if (authCode.isNullOrEmpty()) {
                Log.e(TAG, "authorizationCode not found in redirect page (len=${result.length})")
                codeCaptured.set(false)
                            resultBarMsg = getString(R.string.store_epic_login_failed)
                view.loadUrl(AUTH_URL)
                return@evaluateJavascript
            }

            Log.d(TAG, "Epic auth code captured, exchanging for tokens...")
            lifecycleScope.launch(Dispatchers.IO) {
                val tokenResult = EpicAuthClient.exchangeCode(authCode)
                if (tokenResult == null) {
                    Log.e(TAG, "Epic token exchange failed")
                    withContext(Dispatchers.Main) {
                        codeCaptured.set(false)
                        resultBarMsg = getString(R.string.store_epic_login_failed)
                        view.loadUrl(AUTH_URL)
                    }
                    return@launch
                }

                val creds = EpicCredentialStore.Credentials()
                creds.accessToken = tokenResult.accessToken
                creds.refreshToken = tokenResult.refreshToken
                creds.accountId = tokenResult.accountId
                creds.displayName = tokenResult.displayName
                creds.expiresAt = tokenResult.expiresAt
                EpicCredentialStore.save(this@EpicLoginActivity, creds)

                Log.d(TAG, "Epic login saved OK")   // don't log displayName (PII)
                withContext(Dispatchers.Main) { finish() }
            }
        }
    }

    private fun extractField(json: String, key: String): String? {
        val search = "\"$key\":\""
        val start = json.indexOf(search)
        if (start < 0) return null
        val valueStart = start + search.length
        val end = json.indexOf("\"", valueStart)
        if (end < 0) return null
        return json.substring(valueStart, end)
    }
}

@Composable
private fun EpicLoginWebView(
    authUrl: String,
    redirectHost: String,
    codeCaptured: AtomicBoolean,
    onJsonPage: (WebView) -> Unit,
) {
    val client = rememberWebViewClient(redirectHost, codeCaptured, onJsonPage)
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = EpicAuthClient.USER_AGENT
                }
                webViewClient = client
                loadUrl(authUrl)
            }
        },
    )
}

@Composable
private fun rememberWebViewClient(
    redirectHost: String,
    codeCaptured: AtomicBoolean,
    onJsonPage: (WebView) -> Unit,
): WebViewClient {
    return object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String?) {
            if (url != null && url.startsWith(redirectHost)) {
                if (!codeCaptured.get()) onJsonPage(view)
            }
        }
    }
}
