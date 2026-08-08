package com.winlator.star.store

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color as ComposeColor
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.winlator.star.R
import com.winlator.star.ui.theme.WinlatorTheme

class QrLoginActivity : AppCompatActivity(), SteamQrAuthManager.QrAuthListener {

    private var qrBitmap by mutableStateOf<Bitmap?>(null)
    private var statusText by mutableStateOf("")
    private var isLoading by mutableStateOf(true)
    private var isError by mutableStateOf(false)
    private var showRetry by mutableStateOf(false)

    private var connectWaitListener: SteamRepository.SteamEventListener? = null
    private var reachState = REACH_UNKNOWN
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val connectTimeoutRunnable = Runnable {
        connectWaitListener?.let { SteamRepository.getInstance().removeListener(it) }
        connectWaitListener = null
        val msg = when (reachState) {
            REACH_OK       -> getString(R.string.steam_qr_timeout_reachable)
            REACH_BLOCKED  -> getString(R.string.steam_qr_blocked)
            REACH_NO_NET   -> getString(R.string.steam_qr_no_internet_detail)
            else           -> getString(R.string.steam_qr_timeout)
        }
        onFailure(msg)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            WinlatorTheme {
                QrLoginScreen(
                    qrBitmap = qrBitmap,
                    statusText = statusText,
                    isLoading = isLoading,
                    isError = isError,
                    showRetry = showRetry,
                    onRetry = { startQrAuth() },
                    onCancel = { finish() },
                )
            }
        }

        startQrAuth()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(connectTimeoutRunnable)
        connectWaitListener?.let { SteamRepository.getInstance().removeListener(it) }
        connectWaitListener = null
        SteamQrAuthManager.getInstance().cancel()
        super.onDestroy()
    }

    private fun startQrAuth() {
        mainHandler.removeCallbacks(connectTimeoutRunnable)
        connectWaitListener?.let { SteamRepository.getInstance().removeListener(it) }
        connectWaitListener = null
        reachState = REACH_UNKNOWN

        statusText = getString(R.string.steam_connecting)
        isLoading = true
        isError = false
        qrBitmap = null
        showRetry = false

        val repo = SteamRepository.getInstance()
        if (repo.isConnected) {
            SteamQrAuthManager.getInstance().startQrLogin(this)
        } else {
            val listener = object : SteamRepository.SteamEventListener {
                override fun onEvent(event: String) {
                    when {
                        event == "Reachable" -> {
                            reachState = REACH_OK
                            runOnUiThread { statusText = getString(R.string.steam_connecting_cm); isLoading = true; isError = false }
                        }
                        event == "SteamBlocked" -> {
                            reachState = REACH_BLOCKED
                            runOnUiThread {
                                statusText = getString(R.string.steam_network_blocked)
                                isLoading = false; isError = true; showRetry = true
                            }
                        }
                        event == "NoInternet" -> {
                            reachState = REACH_NO_NET
                            runOnUiThread {
                                statusText = getString(R.string.steam_no_internet)
                                isLoading = false; isError = true; showRetry = true
                            }
                        }
                        event == "Connected" -> {
                            repo.removeListener(this)
                            connectWaitListener = null
                            mainHandler.removeCallbacks(connectTimeoutRunnable)
                            runOnUiThread { SteamQrAuthManager.getInstance().startQrLogin(this@QrLoginActivity) }
                        }
                        event.startsWith("Disconnected") -> {
                            repo.removeListener(this)
                            connectWaitListener = null
                            mainHandler.removeCallbacks(connectTimeoutRunnable)
                            runOnUiThread { onFailure(getString(R.string.steam_disconnected)) }
                        }
                    }
                }
            }
            connectWaitListener = listener
            repo.addListener(listener)
            mainHandler.postDelayed(connectTimeoutRunnable, 10_000L)
        }
    }

    override fun onQrReady(challengeUrl: String) {
        statusText = getString(R.string.steam_qr_scan_phone)
        isLoading = false
        isError = false
        showQr(challengeUrl)
    }

    override fun onQrRefreshed(newChallengeUrl: String) {
        showQr(newChallengeUrl)
    }

    override fun onSuccess(username: String, refreshToken: String) {
        SteamRepository.getInstance().loginWithToken(username, refreshToken)
        statusText = getString(R.string.steam_signed_in_as_plain, username)
        isLoading = false
        isError = false
        startActivity(Intent(this, SteamGamesActivity::class.java))
        finish()
    }

    override fun onFailure(reason: String) {
        statusText = if (reason.isBlank()) {
            getString(R.string.steam_qr_auth_failed_generic)
        } else {
            getString(R.string.steam_failed_reason, reason)
        }
        isLoading = false
        isError = true
        qrBitmap = null
        showRetry = true
    }

    private fun showQr(url: String) {
        try {
            val size = 260
            val writer = QRCodeWriter()
            val matrix = writer.encode(url, BarcodeFormat.QR_CODE, size, size)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            qrBitmap = bmp
        } catch (e: Exception) {
            statusText = getString(R.string.steam_qr_error_browser, url)
            isError = true
        }
    }

    companion object {
        private const val REACH_UNKNOWN = 0
        private const val REACH_OK      = 1
        private const val REACH_BLOCKED = 2
        private const val REACH_NO_NET  = 3
    }
}

@Composable
private fun QrLoginScreen(
    qrBitmap: Bitmap?,
    statusText: String,
    isLoading: Boolean,
    isError: Boolean,
    showRetry: Boolean,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.steam_qr_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.steam_qr_instructions),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        // QR code card \u2014 deliberately white regardless of theme; scanners need the contrast.
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(ComposeColor.White)
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (qrBitmap != null) {
                Image(
                    bitmap = qrBitmap!!.asImageBitmap(),
                    contentDescription = stringResource(R.string.steam_qr_code_description),
                    modifier = Modifier.size(260.dp),
                )
            } else {
                Box(
                    modifier = Modifier.size(260.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            // Fixed dark spinner: it sits on the always-white QR card, so a
                            // light accent preset would make a primary-tinted one invisible.
                            color = ComposeColor(0xFF444444),
                            strokeWidth = 3.dp,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.height(8.dp))
        }

        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = if (isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        // Advisory: a QR-originated session is occasionally dropped by Steam's CM.
        // Recovery is handled automatically, but if it keeps happening, username +
        // password is the more durable path — tell the user so they aren't stuck.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp),
        ) {
            Text(
                text = stringResource(R.string.steam_qr_reliability_note),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(20.dp))

        if (showRetry) {
            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(8.dp),
            ) { Text(stringResource(R.string.steam_retry)) }
            Spacer(Modifier.height(8.dp))
        }

        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.common_back_arrow))
        }
    }
}
