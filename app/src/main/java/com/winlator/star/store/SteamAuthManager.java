package com.winlator.star.store;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;

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
                Log.e(TAG, "Login ExecutionException", cause);
                String msg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
                mainHandler.post(() -> listener.onFailure(msg));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "Auth interrupted");
            } catch (Exception e) {
                Log.e(TAG, "Login error", e);
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
