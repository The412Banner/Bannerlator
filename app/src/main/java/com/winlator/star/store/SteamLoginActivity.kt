package com.winlator.star.store

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.winlator.star.R
import com.winlator.star.ui.theme.WinlatorTheme

class SteamLoginActivity : AppCompatActivity(), SteamAuthManager.AuthListener {

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
            1    -> getString(R.string.steam_cm_timeout)
            2    -> getString(R.string.steam_network_blocked)
            3    -> getString(R.string.steam_no_internet)
            else -> getString(R.string.steam_servers_unreachable)
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
                        isNumeric = data.isNumeric,
                        onDismiss = { guardDialog = null },
                        onSubmit = { code ->
                            guardDialog = null
                            statusText = getString(R.string.steam_verifying)
                            isStatusError = false
                            isLoading = true
                            SteamAuthManager.getInstance().submitGuardCode(code)
                        },
                        onCancel = {
                            guardDialog = null
                            SteamAuthManager.getInstance().cancelAuth()
                            statusText = getString(R.string.steam_sign_in_cancelled)
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
        if (u.isEmpty()) { statusText = getString(R.string.steam_enter_username); isStatusError = true; return }
        if (p.isEmpty())  { statusText = getString(R.string.steam_enter_password); isStatusError = true; return }
        hideKeyboard()
        isStatusError = false
        statusText = getString(R.string.steam_connecting)
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
                            runOnUiThread { onFailure(getString(R.string.steam_could_not_connect)) }
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
            title     = if (codeWrong) getString(R.string.steam_incorrect_code) else getString(R.string.steam_guard),
            message   = getString(R.string.steam_guard_email_prompt, emailDomain),
            isNumeric = false,
        )
    }

    override fun onSteamGuardTotpRequired(codeWrong: Boolean) {
        isLoading = false
        statusText = ""
        guardDialog = GuardDialogData(
            title     = if (codeWrong) getString(R.string.steam_incorrect_code) else getString(R.string.steam_guard),
            message   = getString(R.string.steam_guard_authenticator_prompt),
            isNumeric = true,
        )
    }

    override fun onDeviceConfirmationRequired() {
        statusText = getString(R.string.steam_approve_mobile)
        isStatusError = false
        isLoading = true
    }

    override fun onSuccess(username: String, refreshToken: String) {
        SteamRepository.getInstance().loginWithToken(username, refreshToken)
        statusText = getString(R.string.steam_signed_in)
        isStatusError = false
        isLoading = false
        startActivity(Intent(this, SteamGamesActivity::class.java))
        finish()
    }

    override fun onFailure(reason: String) {
        isLoading = false
        statusText = if (reason.isBlank()) {
            getString(R.string.steam_auth_failed_generic)
        } else {
            getString(R.string.steam_sign_in_failed, reason)
        }
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
    isNumeric: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.take(5) },
                    label = { Text(stringResource(R.string.steam_code)) },
                    singleLine = true,
                    placeholder = {
                        Text(stringResource(if (isNumeric) R.string.steam_five_digit_code else R.string.steam_five_character_code))
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (isNumeric) KeyboardType.Number else KeyboardType.Text,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onSubmit(code.trim()) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onSubmit(code.trim()) },
                        enabled = code.trim().isNotEmpty(),
                    ) { Text(stringResource(R.string.steam_submit)) }
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
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Steam",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.steam_sign_in_account),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            label = { Text(stringResource(R.string.steam_username)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text(stringResource(R.string.steam_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onLoginClick() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onLoginClick,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(8.dp),
        ) { Text(stringResource(R.string.steam_sign_in)) }
        Spacer(Modifier.height(12.dp))

        // QR sign-in re-enabled: a QR-originated session stores the same
        // username + refresh_token as a password login (SteamQrAuthManager →
        // saveSession), so it is recovered by the same logoff/reconnect path
        // (SteamRepository.onLoggedOff / reconnectNow). The QR screen shows an
        // advisory to fall back to username + password if downloads or the
        // session keep dropping after signing in this way.
        TextButton(onClick = onQrClick) {
            Text(stringResource(R.string.steam_sign_in_qr))
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

        if (statusText.isNotEmpty()) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = if (isStatusError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
