package com.winlator.star.store

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.winlator.star.ui.theme.WinlatorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class AmazonLoginActivity : ComponentActivity() {

    private var pendingVerifier: String? = null
    private var pendingSerial: String? = null
    private var pendingClientId: String? = null

    private val codeCaptured = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pendingSerial = AmazonPKCEGenerator.generateDeviceSerial()
        pendingClientId = AmazonPKCEGenerator.generateClientId(pendingSerial!!)
        pendingVerifier = AmazonPKCEGenerator.generateCodeVerifier()
        val challenge = AmazonPKCEGenerator.generateCodeChallenge(pendingVerifier!!)

        Log.d(TAG, "AmazonLoginActivity: PKCE ready, loading auth page")

        setContent {
            WinlatorTheme {
                val authUrl = buildAuthUrl(pendingClientId!!, challenge)
                AmazonLoginWebView(
                    authUrl = authUrl,
                    onRedirectCaptured = { view, url -> handleCodeCapture(view, url) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    private fun handleCodeCapture(view: WebView?, url: String) {
        if (!isAmazonRedirect(url)) return
        if (!codeCaptured.compareAndSet(false, true)) return

        val code = extractAuthCode(url)
        if (code == null) {
            Log.e(TAG, "Amazon redirect missing auth code: $url")
            codeCaptured.set(false)
            return
        }

        view?.stopLoading()
        Log.d(TAG, "Amazon auth code captured, registering device...")

        val capturedCode = code
        val capturedVerifier = pendingVerifier!!
        val capturedSerial = pendingSerial!!
        val capturedClientId = pendingClientId!!

        lifecycleScope.launch(Dispatchers.IO) {
            val result = AmazonAuthClient.registerDevice(
                capturedCode, capturedVerifier, capturedSerial, capturedClientId
            )

            if (result == null) {
                Log.e(TAG, "Device registration failed")
                withContext(Dispatchers.Main) {
                    codeCaptured.set(false)
                    Toast.makeText(
                        this@AmazonLoginActivity,
                        "Amazon login failed, please try again",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                return@launch
            }

            val creds = AmazonCredentialStore.Credentials()
            creds.accessToken = result.accessToken
            creds.refreshToken = result.refreshToken
            creds.deviceSerial = capturedSerial
            creds.clientId = capturedClientId
            creds.expiresAt = System.currentTimeMillis() + (result.expiresIn * 1000L)
            AmazonCredentialStore.save(this@AmazonLoginActivity, creds)

            Log.d(TAG, "Amazon login saved OK, token expires in ${result.expiresIn}s")
            withContext(Dispatchers.Main) { finish() }
        }
    }

    companion object {
        private const val TAG = "BH_AMAZON"

        fun isAmazonRedirect(url: String): Boolean =
            url.contains("openid.oa2.authorization_code=")

        fun extractAuthCode(url: String): String? =
            Uri.parse(url).getQueryParameter("openid.oa2.authorization_code")

        fun buildAuthUrl(clientId: String, codeChallenge: String): String =
            "https://www.amazon.com/ap/signin" +
                "?openid.ns=http%3A%2F%2Fspecs.openid.net%2Fauth%2F2.0" +
                "&openid.claimed_id=http%3A%2F%2Fspecs.openid.net%2Fauth%2F2.0%2Fidentifier_select" +
                "&openid.identity=http%3A%2F%2Fspecs.openid.net%2Fauth%2F2.0%2Fidentifier_select" +
                "&openid.mode=checkid_setup" +
                "&openid.oa2.scope=device_auth_access" +
                "&openid.ns.oa2=http%3A%2F%2Fwww.amazon.com%2Fap%2Fext%2Foauth%2F2" +
                "&openid.oa2.response_type=code" +
                "&openid.oa2.code_challenge_method=S256" +
                "&openid.oa2.client_id=device%3A$clientId" +
                "&language=en_US" +
                "&marketPlaceId=ATVPDKIKX0DER" +
                "&openid.return_to=https%3A%2F%2Fwww.amazon.com" +
                "&openid.pape.max_auth_age=0" +
                "&openid.ns.pape=http%3A%2F%2Fspecs.openid.net%2Fextensions%2Fpape%2F1.0" +
                "&openid.assoc_handle=amzn_sonic_games_launcher" +
                "&pageId=amzn_sonic_games_launcher" +
                "&openid.oa2.code_challenge=$codeChallenge"
    }
}

@Composable
private fun AmazonLoginWebView(
    authUrl: String,
    onRedirectCaptured: (WebView?, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            WebView(context).also { wv ->
                val ws = wv.settings
                ws.javaScriptEnabled = true
                ws.domStorageEnabled = true
                ws.userAgentString = AmazonAuthClient.USER_AGENT
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        val url = request.url.toString()
                        if (isAmazonRedirect(url)) {
                            onRedirectCaptured(view, url)
                            return true
                        }
                        return false
                    }

                    @Suppress("DEPRECATION")
                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                        if (isAmazonRedirect(url)) {
                            onRedirectCaptured(view, url)
                            return true
                        }
                        return false
                    }

                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        if (isAmazonRedirect(url)) {
                            onRedirectCaptured(view, url)
                        }
                    }
                }
                wv.loadUrl(authUrl)
            }
        },
        onRelease = { it.destroy() },
        modifier = modifier,
    )
}

private fun isAmazonRedirect(url: String): Boolean =
    AmazonLoginActivity.isAmazonRedirect(url)
