package com.winlator.star.store

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.winlator.star.ui.theme.WinlatorTheme

class QrLoginActivity : ComponentActivity(), SteamQrAuthManager.QrAuthListener {

    private var qrBitmap by mutableStateOf<Bitmap?>(null)
    private var statusText by mutableStateOf("Connecting\u2026")
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
            REACH_OK       -> "Steam servers are reachable but CM connection timed out.\nPort 27017 may be blocked \u2014 try a VPN or mobile data."
            REACH_BLOCKED  -> "Steam servers are blocked on your network.\nYou need a VPN to use Steam here."
            REACH_NO_NET   -> "No internet connection detected.\nCheck your Wi-Fi or mobile data."
            else           -> "Connection timed out. Check your network."
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

        statusText = "Connecting to Steam\u2026"
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
                            runOnUiThread { statusText = "Connecting to Steam CM server\u2026"; isLoading = true; isError = false }
                        }
                        event == "SteamBlocked" -> {
                            reachState = REACH_BLOCKED
                            runOnUiThread {
                                statusText = "Steam is blocked on your network.\nA VPN is required."
                                isLoading = false; isError = true; showRetry = true
                            }
                        }
                        event == "NoInternet" -> {
                            reachState = REACH_NO_NET
                            runOnUiThread {
                                statusText = "No internet connection."
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
                            runOnUiThread { onFailure("Disconnected from Steam.") }
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
        statusText = "Scan with the Steam app on your phone"
        isLoading = false
        isError = false
        showQr(challengeUrl)
    }

    override fun onQrRefreshed(newChallengeUrl: String) {
        showQr(newChallengeUrl)
    }

    override fun onSuccess(username: String, refreshToken: String) {
        SteamRepository.getInstance().loginWithToken(username, refreshToken)
        statusText = "Signed in as $username"
        isLoading = false
        isError = false
        startActivity(Intent(this, SteamGamesActivity::class.java))
        finish()
    }

    override fun onFailure(reason: String) {
        statusText = "Failed: $reason"
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
            statusText = "QR error \u2014 open in browser:\n$url"
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
            .background(androidx.compose.ui.graphics.Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Sign in via QR Code",
            fontSize = 22.sp,
            color = androidx.compose.ui.graphics.Color.White,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Open the Steam app \u2192 \u2630 \u2192 Sign in via QR code",
            fontSize = 13.sp,
            color = androidx.compose.ui.graphics.Color(0xFFAAAAAA),
        )
        Spacer(Modifier.height(24.dp))

        // QR code card
        Box(
            modifier = Modifier
                .background(androidx.compose.ui.graphics.Color.White)
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (qrBitmap != null) {
                Image(
                    bitmap = qrBitmap!!.asImageBitmap(),
                    contentDescription = "QR Code",
                    modifier = Modifier.size(260.dp),
                )
            } else {
                Box(
                    modifier = Modifier.size(260.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            color = androidx.compose.ui.graphics.Color(0xFF0055FF),
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
                color = androidx.compose.ui.graphics.Color(0xFF0055FF),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.height(8.dp))
        }

        Text(
            text = statusText,
            fontSize = 13.sp,
            color = if (isError) androidx.compose.ui.graphics.Color.Red else androidx.compose.ui.graphics.Color(0xFFAAAAAA),
        )
        Spacer(Modifier.height(20.dp))

        if (showRetry) {
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF0055FF)),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(8.dp),
            ) { Text("Retry", color = androidx.compose.ui.graphics.Color.White) }
            Spacer(Modifier.height(8.dp))
        }

        TextButton(onClick = onCancel) {
            Text("\u2190 Back", color = androidx.compose.ui.graphics.Color(0xFFAAAAAA))
        }
    }
}
