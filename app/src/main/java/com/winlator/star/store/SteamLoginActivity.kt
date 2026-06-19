package com.winlator.star.store

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.winlator.star.ui.theme.WinlatorTheme

class SteamLoginActivity : ComponentActivity(), SteamAuthManager.AuthListener {

    private var username by mutableStateOf("")
    private var password by mutableStateOf("")
    private var isLoading by mutableStateOf(false)
    private var statusText by mutableStateOf("")
    private var isStatusError by mutableStateOf(false)
    private var guardDialog by mutableStateOf<GuardDialogData?>(null)

    private var connectWaitListener: SteamRepository.SteamEventListener? = null
    private var pendingUsername: String? = null
    private var pendingPassword: String? = null
    private var reachState = 0
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val connectTimeoutRunnable = Runnable {
        connectWaitListener?.let { SteamRepository.getInstance().removeListener(it) }
        connectWaitListener = null
        pendingUsername = null; pendingPassword = null
        val msg = when (reachState) {
            1    -> "Steam CM connection timed out. Port 27017 may be blocked — try a VPN or mobile data."
            2    -> "Steam is blocked on your network. A VPN is required."
            3    -> "No internet connection detected."
            else -> "Could not reach Steam servers. Check your network."
        }
        onFailure(msg)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WinlatorTheme {
                SteamLoginScreen(
                    username = username,
                    password = password,
                    isLoading = isLoading,
                    statusText = statusText,
                    isStatusError = isStatusError,
                    onUsernameChange = { username = it },
                    onPasswordChange = { password = it },
                    onLoginClick = { onLoginClicked() },
                    onQrClick = {
                        startActivity(Intent(this@SteamLoginActivity, QrLoginActivity::class.java))
                    },
                )
                guardDialog?.let { data ->
                    SteamGuardDialog(
                        title = data.title,
                        message = data.message,
                        onDismiss = { guardDialog = null },
                        onSubmit = { code ->
                            guardDialog = null
                            statusText = "Verifying…"
                            isStatusError = false
                            isLoading = true
                            SteamAuthManager.getInstance().submitGuardCode(code)
                        },
                        onCancel = {
                            guardDialog = null
                            SteamAuthManager.getInstance().cancelAuth()
                            statusText = "Sign-in cancelled."
                            isStatusError = false
                            isLoading = false
                        },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(connectTimeoutRunnable)
        connectWaitListener?.let { SteamRepository.getInstance().removeListener(it) }
        connectWaitListener = null
        super.onDestroy()
        SteamAuthManager.getInstance().cancelAuth()
    }

    private fun onLoginClicked() {
        val u = username.trim()
        val p = password
        if (u.isEmpty()) { statusText = "Enter your username."; isStatusError = true; return }
        if (p.isEmpty())  { statusText = "Enter your password."; isStatusError = true; return }
        hideKeyboard()
        isStatusError = false
        statusText = "Connecting to Steam\u2026"
        isLoading = true

        val repo = SteamRepository.getInstance()
        if (repo.isConnected) {
            SteamAuthManager.getInstance().startCredentialLogin(u, p, this)
        } else {
            pendingUsername = u
            pendingPassword = p
            val listener = object : SteamRepository.SteamEventListener {
                override fun onEvent(event: String) {
                    when {
                        event == "Reachable"     -> reachState = 1
                        event == "SteamBlocked"  -> reachState = 2
                        event == "NoInternet"    -> reachState = 3
                        event == "Connected" -> {
                            repo.removeListener(this)
                            connectWaitListener = null
                            mainHandler.removeCallbacks(connectTimeoutRunnable)
                            val u = pendingUsername ?: return
                            val p = pendingPassword ?: return
                            pendingUsername = null; pendingPassword = null
                            runOnUiThread { SteamAuthManager.getInstance().startCredentialLogin(u, p, this@SteamLoginActivity) }
                        }
                        event.startsWith("Disconnected") -> {
                            repo.removeListener(this)
                            connectWaitListener = null
                            pendingUsername = null; pendingPassword = null
                            runOnUiThread { onFailure("Could not connect to Steam") }
                        }
                    }
                }
            }
            reachState = 0
            connectWaitListener = listener
            repo.addListener(listener)
            mainHandler.postDelayed(connectTimeoutRunnable, 10_000L)
        }
    }

    override fun onSteamGuardEmailRequired(emailDomain: String, codeWrong: Boolean) {
        isLoading = false
        statusText = ""
        guardDialog = GuardDialogData(
            title     = if (codeWrong) "Incorrect code — try again" else "Steam Guard",
            message   = "Enter the code Steam sent to your email ending in \u2026$emailDomain",
            isNumeric = false,
        )
    }

    override fun onSteamGuardTotpRequired(codeWrong: Boolean) {
        isLoading = false
        statusText = ""
        guardDialog = GuardDialogData(
            title     = if (codeWrong) "Incorrect code — try again" else "Steam Guard",
            message   = "Enter the code from your Steam Guard Mobile Authenticator app",
            isNumeric = true,
        )
    }

    override fun onDeviceConfirmationRequired() {
        statusText = "Approve the login in your Steam mobile app\u2026"
        isStatusError = false
        isLoading = true
    }

    override fun onSuccess(username: String, refreshToken: String) {
        SteamRepository.getInstance().loginWithToken(username, refreshToken)
        statusText = "Signed in!"
        isStatusError = false
        isLoading = false
        startActivity(Intent(this, SteamGamesActivity::class.java))
        finish()
    }

    override fun onFailure(reason: String) {
        isLoading = false
        statusText = "Sign-in failed: $reason"
        isStatusError = true
    }

    private fun hideKeyboard() {
        currentFocus?.let { v ->
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(v.windowToken, 0)
        }
    }
}

private data class GuardDialogData(
    val title: String,
    val message: String,
    val isNumeric: Boolean,
)

@Composable
private fun SteamGuardDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(16.dp),
            color = androidx.compose.material3.MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = title, fontSize = 18.sp, color = Color.White)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    fontSize = 13.sp,
                    color = Color(0xFFAAAAAA),
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.take(5) },
                    label = { Text("Code") },
                    singleLine = true,
                    placeholder = { Text(if (title.contains("Authenticator")) "5-digit code" else "5-character code") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (title.contains("Authenticator")) KeyboardType.Number else KeyboardType.Text,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onSubmit(code.trim()) }),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF0055FF),
                        unfocusedBorderColor = Color(0xFF2A2A2A),
                        cursorColor = Color.White,
                        focusedLabelColor = Color(0xFFAAAAAA),
                        unfocusedLabelColor = Color(0xFF808080),
                    ),
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onCancel) { Text("Cancel", color = Color(0xFFAAAAAA)) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onSubmit(code.trim()) },
                        enabled = code.trim().isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0055FF)),
                    ) { Text("Submit", color = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun SteamLoginScreen(
    username: String,
    password: String,
    isLoading: Boolean,
    statusText: String,
    isStatusError: Boolean,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLoginClick: () -> Unit,
    onQrClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "Steam", fontSize = 32.sp, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Sign in to your account",
            fontSize = 14.sp,
            color = Color(0xFFAAAAAA),
        )
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            label = { Text("Username") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF0055FF),
                unfocusedBorderColor = Color(0xFF2A2A2A),
                cursorColor = Color.White,
                focusedLabelColor = Color(0xFFAAAAAA),
                unfocusedLabelColor = Color(0xFF808080),
            ),
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onLoginClick() }),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF0055FF),
                unfocusedBorderColor = Color(0xFF2A2A2A),
                cursorColor = Color.White,
                focusedLabelColor = Color(0xFFAAAAAA),
                unfocusedLabelColor = Color(0xFF808080),
            ),
        )
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onLoginClick,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0055FF)),
            shape = RoundedCornerShape(8.dp),
        ) { Text("Sign In", color = Color.White) }
        Spacer(Modifier.height(12.dp))

        TextButton(onClick = onQrClick) {
            Text("Sign in with QR Code", color = Color(0xFFAAAAAA))
        }
        Spacer(Modifier.height(16.dp))

        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = Color(0xFF0055FF),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.height(8.dp))
        }

        if (statusText.isNotEmpty()) {
            Text(
                text = statusText,
                fontSize = 13.sp,
                color = if (isStatusError) Color.Red else Color(0xFFAAAAAA),
            )
        }
    }
}
