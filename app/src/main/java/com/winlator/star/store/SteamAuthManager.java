package com.winlator.star.store;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;

import com.winlator.star.store.blsteam.BlAuthResult;
import com.winlator.star.store.blsteam.BlAuthenticator;
import com.winlator.star.store.blsteam.BlSteamEngine;
import com.winlator.star.store.blsteam.BlSteamSession;

import in.dragonbra.javasteam.enums.EOSType;
import in.dragonbra.javasteam.steam.authentication.AuthPollResult;
import in.dragonbra.javasteam.steam.authentication.AuthSessionDetails;
import in.dragonbra.javasteam.steam.authentication.CredentialsAuthSession;
import in.dragonbra.javasteam.steam.authentication.IAuthenticator;
import in.dragonbra.javasteam.steam.authentication.SteamAuthentication;

/**
 * Handles the Steam credential authentication flow.
 *
 * Written in Java (not Kotlin) to avoid Kotlin 2.2.0 metadata incompatibility
 * with the base APK's kotlinc 1.9.x.
 *
 * JavaSteam's authentication API is CompletableFuture-based.
 * IAuthenticator methods return CompletableFuture<T>; the Steam Guard futures
 * are resolved from the UI thread when the user submits a code.
 *
 * Flow:
 *   startCredentialLogin(username, password, listener)
 *     → beginAuthSessionViaCredentials() returns CF<CredentialsAuthSession>
 *     → pollingWaitForResult() returns CF<AuthPollResult>
 *       → IAuthenticator.getEmailCode() / getTotpCode() called if Steam Guard needed
 *         → posts event to UI (main thread)
 *         → returns a CF that completes when submitGuardCode() is called from UI
 *     → onSuccess(username, refreshToken) posted to main thread
 *
 * Cancellation: cancelAuth() completes pending futures exceptionally.
 *
 * Rust engine (use_rust_steam_engine ON): the SAME listener contract is driven by
 * libblsteam.so's auth session ({@link BlSteamSession#startLoginWithCredentials}) on the engine's
 * live channel — RSA password, Steam Guard (email / mobile code with wrong-code re-prompt / mobile
 * confirmation) and the poll loop all run natively; the {@link BlAuthenticator} bridge below is the
 * same CompletableFuture shape as the JavaSteam IAuthenticator, resolved from the UI by
 * {@link #submitGuardCode}.
 */
public final class SteamAuthManager {

    private static final String TAG = "SteamAuth";

    // -------------------------------------------------------------------------
    // Singleton
    // -------------------------------------------------------------------------

    private static final SteamAuthManager INSTANCE = new SteamAuthManager();
    public static SteamAuthManager getInstance() { return INSTANCE; }
    private SteamAuthManager() {}

    // -------------------------------------------------------------------------
    // Callback interface (delivered on main thread)
    // -------------------------------------------------------------------------

    public interface AuthListener {
        void onSteamGuardEmailRequired(String emailDomain, boolean codeWrong);
        void onSteamGuardTotpRequired(boolean codeWrong);
        void onDeviceConfirmationRequired();
        void onSuccess(String username, String refreshToken);
        void onFailure(String reason);
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * Pending code future: when the user types a Steam Guard code in the dialog,
     * submitGuardCode() completes this future so pollingWaitForResult() can proceed.
     */
    private volatile CompletableFuture<String> pendingCodeFuture = null;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Begin credential login on a new background thread.
     * The SteamClient must already be connected (SteamForegroundService does this).
     */
    public void startCredentialLogin(String username, String password, AuthListener listener) {
        pendingCodeFuture = null;
        if (SteamRepository.getInstance().isRustEngine()) {
            startCredentialLoginRust(username, password, listener);
            return;
        }

        new Thread(() -> {
            try {
                SteamAuthentication auth =
                        new SteamAuthentication(SteamRepository.getInstance().getSteamClient());

                AuthSessionDetails details = new AuthSessionDetails();
                details.username       = username;
                details.password       = password;
                details.clientOSType   = EOSType.AndroidUnknown;
                details.deviceFriendlyName = "Android Device";
                details.persistentSession  = true;
                details.authenticator  = buildAuthenticator(listener);

                CredentialsAuthSession session =
                        auth.beginAuthSessionViaCredentials(details).get();
                AuthPollResult result = session.pollingWaitForResult().get();

                String refreshToken = result.getRefreshToken();
                String accountName  = result.getAccountName();
                String finalName    = (accountName != null && !accountName.isEmpty())
                                      ? accountName : username;

                SteamRepository.getInstance().saveSession(finalName, refreshToken);
                mainHandler.post(() -> listener.onSuccess(finalName, refreshToken));

            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                Log.e(TAG, "Login ExecutionException: " + SteamLogRedactor.redact(Log.getStackTraceString(cause)));
                String msg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
                mainHandler.post(() -> listener.onFailure(msg));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "Auth interrupted");
            } catch (Exception e) {
                Log.e(TAG, "Login error: " + SteamLogRedactor.redact(Log.getStackTraceString(e)));
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                mainHandler.post(() -> listener.onFailure(msg));
            }
        }, "SteamCredentialLogin").start();
    }

    /**
     * Deliver a Steam Guard code (email or TOTP) to the waiting auth future.
     * Call from the UI after the user types the code.
     */
    public void submitGuardCode(String code) {
        CompletableFuture<String> f = pendingCodeFuture;
        if (f != null) f.complete(code);
    }

    /** Cancel any pending auth and fail the pending code future. */
    public void cancelAuth() {
        CompletableFuture<String> f = pendingCodeFuture;
        if (f != null) f.completeExceptionally(new InterruptedException("User cancelled"));
        pendingCodeFuture = null;
        if (rustAuthActive) {
            rustAuthActive = false;
            BlSteamSession s = BlSteamEngine.INSTANCE.session();
            if (s != null) {
                try { s.cancelLogin(); } catch (Throwable t) { Log.w(TAG, "cancelLogin failed", t); }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Rust engine path
    // -------------------------------------------------------------------------

    /** True while a native credentials auth session is running (so cancelAuth() can abort it). */
    private volatile boolean rustAuthActive = false;

    /**
     * Credentials sign-in on the Rust engine. The engine's channel must be up (connect-only or
     * logged on) — SteamLoginActivity only calls in once the repository reports "Connected".
     * The native auth thread calls the {@link BlAuthenticator} for Steam Guard and blocks on the
     * returned futures exactly like JavaSteam's poll loop; the final {@link BlAuthResult} carries
     * the refresh token + account name + SteamID64, which are persisted through
     * {@link SteamRepository#saveSession(String, String, long)} before the UI is told.
     */
    private void startCredentialLoginRust(String username, String password, AuthListener listener) {
        BlSteamSession session = BlSteamEngine.INSTANCE.session();
        if (session == null || !BlSteamEngine.INSTANCE.isConnected()) {
            mainHandler.post(() -> listener.onFailure("Not connected to Steam"));
            return;
        }
        rustAuthActive = true;
        Log.i(TAG, "credentials sign-in starting on the Rust engine");
        try {
            session.startLoginWithCredentials(username, password, true, buildRustAuthenticator(listener),
                    result -> onRustAuthResult(result, username, listener));
        } catch (Throwable t) {
            rustAuthActive = false;
            Log.e(TAG, "Rust credentials login could not start: " + SteamLogRedactor.redact(Log.getStackTraceString(t)));
            String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            mainHandler.post(() -> listener.onFailure(msg));
        }
    }

    /** Native auth thread → main thread. Intermediate (remote-interaction) updates carry no token. */
    private void onRustAuthResult(BlAuthResult result, String username, AuthListener listener) {
        if (result.getSuccess() && !result.getRefreshToken().isEmpty()) {
            rustAuthActive = false;
            String accountName = result.getAccountName();
            String finalName = (accountName != null && !accountName.isEmpty()) ? accountName : username;
            long sid = result.getSteamId();
            if (sid == 0L) sid = SteamSessionManager.jwtSteamId64(result.getRefreshToken());
            SteamRepository.getInstance().saveSession(finalName, result.getRefreshToken(), sid);
            Log.i(TAG, "credentials sign-in OK on the Rust engine (steamId known=" + (sid != 0L) + ")");
            mainHandler.post(() -> listener.onSuccess(finalName, result.getRefreshToken()));
            return;
        }
        if (result.getHadRemoteInteraction() && result.getErrorMessage().isEmpty()) {
            // Steam saw the phone approve / interact; the poll loop keeps running.
            mainHandler.post(listener::onDeviceConfirmationRequired);
            return;
        }
        rustAuthActive = false;
        String msg = result.getErrorMessage().isEmpty()
                ? SteamRepository.eresultName(result.getErrorCode()) : result.getErrorMessage();
        Log.w(TAG, "credentials sign-in failed on the Rust engine: eresult=" + result.getErrorCode()
                + " " + SteamLogRedactor.redact(msg));
        mainHandler.post(() -> listener.onFailure(msg));
    }

    /** Same three prompts as {@link #buildAuthenticator}, on the engine's {@link BlAuthenticator} shape. */
    private BlAuthenticator buildRustAuthenticator(AuthListener listener) {
        return new BlAuthenticator() {
            @Override
            public CompletableFuture<Boolean> acceptDeviceConfirmation() {
                mainHandler.post(listener::onDeviceConfirmationRequired);
                return CompletableFuture.completedFuture(true);
            }

            @Override
            public CompletableFuture<String> getEmailCode(String email, boolean previousCodeWasIncorrect) {
                CompletableFuture<String> future = new CompletableFuture<>();
                pendingCodeFuture = future;
                final String domain = email != null ? email : "";
                mainHandler.post(() -> listener.onSteamGuardEmailRequired(domain, previousCodeWasIncorrect));
                return future;
            }

            @Override
            public CompletableFuture<String> getDeviceCode(boolean previousCodeWasIncorrect) {
                CompletableFuture<String> future = new CompletableFuture<>();
                pendingCodeFuture = future;
                mainHandler.post(() -> listener.onSteamGuardTotpRequired(previousCodeWasIncorrect));
                return future;
            }
        };
    }

    // -------------------------------------------------------------------------
    // IAuthenticator implementation
    // -------------------------------------------------------------------------

    private IAuthenticator buildAuthenticator(AuthListener listener) {
        return new IAuthenticator() {

            /**
             * Called when Steam wants device-confirmation (approve in mobile app).
             * Returns a completed future immediately — we just inform the UI.
             */
            @Override
            public CompletableFuture<Boolean> acceptDeviceConfirmation() {
                mainHandler.post(listener::onDeviceConfirmationRequired);
                return CompletableFuture.completedFuture(true);
            }

            /**
             * Called when Steam Guard email code is required.
             * Returns a future that completes when submitGuardCode() is called from UI.
             */
            @Override
            public CompletableFuture<String> getEmailCode(String email, boolean previousCodeWrong) {
                CompletableFuture<String> future = new CompletableFuture<>();
                pendingCodeFuture = future;
                mainHandler.post(() -> listener.onSteamGuardEmailRequired(email, previousCodeWrong));
                return future;
            }

            /**
             * Called when Steam Guard TOTP/device code is required (mobile authenticator).
             * Returns a future that completes when submitGuardCode() is called from UI.
             */
            @Override
            public CompletableFuture<String> getDeviceCode(boolean previousCodeWrong) {
                CompletableFuture<String> future = new CompletableFuture<>();
                pendingCodeFuture = future;
                mainHandler.post(() -> listener.onSteamGuardTotpRequired(previousCodeWrong));
                return future;
            }
        };
    }
}
