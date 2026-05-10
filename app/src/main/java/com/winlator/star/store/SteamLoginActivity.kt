package com.winlator.star.store

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*

/**
 * Steam credential login screen.
 *
 * Phase 2: full username/password UI with Steam Guard (email code + TOTP) support.
 *
 * The SteamClient must already be connected — SteamForegroundService is started
 * from SteamMainActivity before navigating here, so the connection is in progress.
 *
 * Flow:
 *   User enters credentials → SteamAuthManager.startCredentialLogin()
 *     → if Steam Guard required → dialog shown → user submits code
 *     → onSuccess → SteamRepository.loginWithToken() → SteamGamesActivity
 */
class SteamLoginActivity : Activity(), SteamAuthManager.AuthListener {

    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var btnQr: Button
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar

    private var connectWaitListener: SteamRepository.SteamEventListener? = null
    private var pendingUsername: String? = null
    private var pendingPassword: String? = null
    private var reachState = 0  // 0=unknown, 1=ok, 2=blocked, 3=no-internet
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

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(connectTimeoutRunnable)
        connectWaitListener?.let { SteamRepository.getInstance().removeListener(it) }
        connectWaitListener = null
        super.onDestroy()
        SteamAuthManager.getInstance().cancelAuth()
    }

    // -------------------------------------------------------------------------
    // UI construction (programmatic — no XML resource needed)
    // -------------------------------------------------------------------------

    private fun buildUi() {
        val root = ScrollView(this).apply {
            setBackgroundColor(BG_DARK)
        }
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(64), dp(32), dp(32))
        }
        root.addView(ll)

        // Steam wordmark
        ll.addView(TextView(this).apply {
            text = "Steam"
            textSize = 32f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, wrapLp().also { it.bottomMargin = dp(8) })

        ll.addView(TextView(this).apply {
            text = "Sign in to your account"
            textSize = 14f
            setTextColor(GRAY_TEXT)
            gravity = Gravity.CENTER
        }, wrapLp().also { it.bottomMargin = dp(32) })

        // Username
        etUsername = styledEdit("Username", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        etUsername.imeOptions = EditorInfo.IME_ACTION_NEXT
        ll.addView(etUsername, fullLp(dp(52)).also { it.bottomMargin = dp(12) })

        // Password
        etPassword = styledEdit("Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        etPassword.imeOptions = EditorInfo.IME_ACTION_DONE
        etPassword.setOnEditorActionListener { _, _, _ -> onLoginClicked(); true }
        ll.addView(etPassword, fullLp(dp(52)).also { it.bottomMargin = dp(24) })

        // Login button
        btnLogin = Button(this).apply {
            text = "Sign In"
            setTextColor(Color.WHITE)
            setBackgroundColor(STEAM_BLUE)
            setOnClickListener { onLoginClicked() }
        }
        ll.addView(btnLogin, fullLp(dp(52)).also { it.bottomMargin = dp(12) })

        // QR login link
        btnQr = Button(this).apply {
            text = "Sign in with QR Code"
            setTextColor(GRAY_TEXT)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                startActivity(Intent(this@SteamLoginActivity, QrLoginActivity::class.java))
            }
        }
        ll.addView(btnQr, fullLp(dp(44)).also { it.bottomMargin = dp(16) })

        // Progress spinner
        progressBar = ProgressBar(this).apply { visibility = View.GONE }
        ll.addView(progressBar, wrapLp().also { it.bottomMargin = dp(8) })

        // Status / error text
        tvStatus = TextView(this).apply {
            setTextColor(GRAY_TEXT)
            textSize = 13f
            gravity = Gravity.CENTER
        }
        ll.addView(tvStatus, wrapLp())

        setContentView(root)
    }

    // -------------------------------------------------------------------------
    // Login action
    // -------------------------------------------------------------------------

    private fun onLoginClicked() {
        val username = etUsername.text.toString().trim()
        val password  = etPassword.text.toString()
        if (username.isEmpty()) { tvStatus.text = "Enter your username."; return }
        if (password.isEmpty())  { tvStatus.text = "Enter your password."; return }
        hideKeyboard()
        setLoading(true, "Connecting to Steam\u2026")

        val repo = SteamRepository.getInstance()
        if (repo.isConnected) {
            SteamAuthManager.getInstance().startCredentialLogin(username, password, this)
        } else {
            pendingUsername = username
            pendingPassword = password
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

    // -------------------------------------------------------------------------
    // SteamAuthManager.AuthListener callbacks (always on main thread)
    // -------------------------------------------------------------------------

    override fun onSteamGuardEmailRequired(emailDomain: String, codeWrong: Boolean) {
        setLoading(false, "")
        showCodeDialog(
            title     = if (codeWrong) "Incorrect code \u2014 try again" else "Steam Guard",
            message   = "Enter the code Steam sent to your email ending in \u2026$emailDomain",
            isNumeric = false,
        )
    }

    override fun onSteamGuardTotpRequired(codeWrong: Boolean) {
        setLoading(false, "")
        showCodeDialog(
            title     = if (codeWrong) "Incorrect code \u2014 try again" else "Steam Guard",
            message   = "Enter the code from your Steam Guard Mobile Authenticator app",
            isNumeric = true,
        )
    }

    override fun onDeviceConfirmationRequired() {
        setLoading(true, "Approve the login in your Steam mobile app\u2026")
    }

    override fun onSuccess(username: String, refreshToken: String) {
        // saveSession() already called by SteamAuthManager.
        // Kick off CM login so LoggedOnCallback fills SteamID / cellId.
        SteamRepository.getInstance().loginWithToken(username, refreshToken)
        setLoading(false, "Signed in!")
        startActivity(Intent(this, SteamGamesActivity::class.java))
        finish()
    }

    override fun onFailure(reason: String) {
        setLoading(false, "Sign-in failed: $reason")
    }

    // -------------------------------------------------------------------------
    // Steam Guard dialog
    // -------------------------------------------------------------------------

    private fun showCodeDialog(title: String, message: String, isNumeric: Boolean) {
        val input = EditText(this).apply {
            inputType = if (isNumeric)
                InputType.TYPE_CLASS_NUMBER
            else
                InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            hint = if (isNumeric) "5-digit code" else "5-character code"
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(input)
            .setPositiveButton("Submit") { _, _ ->
                val code = input.text.toString().trim()
                if (code.isEmpty()) {
                    tvStatus.text = "No code entered."
                    return@setPositiveButton
                }
                setLoading(true, "Verifying\u2026")
                SteamAuthManager.getInstance().submitGuardCode(code)
            }
            .setNegativeButton("Cancel") { _, _ ->
                SteamAuthManager.getInstance().cancelAuth()
                setLoading(false, "Sign-in cancelled.")
            }
            .setCancelable(false)
            .show()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun setLoading(loading: Boolean, status: String) {
        btnLogin.isEnabled     = !loading
        btnQr.isEnabled        = !loading
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        tvStatus.text = status
        tvStatus.setTextColor(if (status.startsWith("Sign-in failed")) Color.RED else GRAY_TEXT)
    }

    private fun hideKeyboard() {
        currentFocus?.let { v ->
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(v.windowToken, 0)
        }
    }

    private fun styledEdit(hint: String, inputType: Int): EditText =
        EditText(this).apply {
            this.hint = hint
            this.inputType = inputType
            setHintTextColor(0xFF808080.toInt())
            setTextColor(Color.WHITE)
            setBackgroundColor(INPUT_BG)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun fullLp(height: Int) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height)

    private fun wrapLp() =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )

    companion object {
        private val BG_DARK    = 0xFF1B1B1B.toInt()
        private val INPUT_BG   = 0xFF2A2A2A.toInt()
        private val STEAM_BLUE = 0xFF1A3A5C.toInt()
        private val GRAY_TEXT  = 0xFFAAAAAA.toInt()
    }
}
