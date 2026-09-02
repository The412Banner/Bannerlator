package com.winlator.star.store;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.winlator.star.store.blsteam.BlAuthResult;
import com.winlator.star.store.blsteam.BlSteamEngine;
import com.winlator.star.store.blsteam.BlSteamEngineLog;
import com.winlator.star.store.blsteam.BlSteamSession;

import in.dragonbra.javasteam.enums.EOSType;
import in.dragonbra.javasteam.steam.authentication.AuthPollResult;
import in.dragonbra.javasteam.steam.authentication.AuthSessionDetails;
import in.dragonbra.javasteam.steam.authentication.QrAuthSession;
import in.dragonbra.javasteam.steam.authentication.SteamAuthentication;

/**
 * Handles the Steam QR code authentication flow.
 *
 * Written in Java (not Kotlin) to avoid Kotlin 2.2.0 metadata incompatibility.
 *
 * Flow:
 *   startQrLogin(listener)
 *     → beginAuthSessionViaQR() → QrAuthSession → getChallengeUrl() → onQrReady()
 *     → background thread polls for URL changes every 3s → onQrRefreshed()
 *     → pollingWaitForResult() → onSuccess() / onFailure()
 *
 * The challenge URL changes every ~30 seconds. We poll getChallengeUrl() on a
 * background thread and notify the UI whenever it changes so the QR image
 * can be regenerated.
 *
 * Rust engine (use_rust_steam_engine ON): the same listener is driven by
 * libblsteam.so's QR auth session ({@link BlSteamSession#startLoginWithQr}) —
 * BeginAuthSessionViaQR, the challenge-URL rotation (pushed, not polled) and the
 * approval poll loop all run natively on the engine's live channel.
 */
public final class SteamQrAuthManager {

    private static final String TAG = "SteamQrAuth";

    // -------------------------------------------------------------------------
    // Singleton
    // -------------------------------------------------------------------------

    private static final SteamQrAuthManager INSTANCE = new SteamQrAuthManager();
    public static SteamQrAuthManager getInstance() { return INSTANCE; }
    private SteamQrAuthManager() {}

    // -------------------------------------------------------------------------
    // Callback interface (delivered on main thread)
    // -------------------------------------------------------------------------

    public interface QrAuthListener {
        /** Initial QR URL ready — display QR for this URL. */
        void onQrReady(String challengeUrl);
        /** Steam rotated the QR challenge — refresh the displayed QR. */
        void onQrRefreshed(String newChallengeUrl);
        /** User approved the QR login on their phone. */
        void onSuccess(String username, String refreshToken);
        /** Auth failed (timeout, cancelled, network error). */
        void onFailure(String reason);
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled = false;
    private volatile QrAuthListener currentListener = null;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Begin QR login. The SteamClient must already be connected.
     * Calls listener callbacks on the main thread.
     */
    public void startQrLogin(QrAuthListener listener) {
        cancelled = false;
        currentListener = listener;
        if (SteamRepository.getInstance().isRustEngine()) {
            startQrLoginRust(listener);
            return;
        }

        new Thread(() -> {
            try {
                SteamAuthentication auth =
                        new SteamAuthentication(SteamRepository.getInstance().getSteamClient());

                AuthSessionDetails details = new AuthSessionDetails();
                details.clientOSType       = EOSType.AndroidUnknown;
                details.deviceFriendlyName = "Android Device";
                details.persistentSession  = true;
                // No IAuthenticator needed for QR — user approves on their phone.

                QrAuthSession session = auth.beginAuthSessionViaQR(details).get();

                // Deliver initial URL to UI.
                String initialUrl = session.getChallengeUrl();
                mainHandler.post(() -> listener.onQrReady(initialUrl));

                // Background thread: poll for URL rotation every 3 s.
                startUrlRefreshWatcher(session, listener);

                // Block until the user approves (or it times out / is cancelled).
                AuthPollResult result = session.pollingWaitForResult().get();

                if (cancelled) return;

                String refreshToken = result.getRefreshToken();
                String accountName  = result.getAccountName();
                String finalName    = (accountName != null && !accountName.isEmpty())
                                      ? accountName : "Steam User";

                SteamRepository.getInstance().saveSession(finalName, refreshToken);
                mainHandler.post(() -> listener.onSuccess(finalName, refreshToken));

            } catch (java.util.concurrent.ExecutionException e) {
                if (cancelled) return;
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                Log.e(TAG, "QR auth ExecutionException: " + SteamLogRedactor.redact(Log.getStackTraceString(cause)));
                String msg = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
                mainHandler.post(() -> listener.onFailure(msg));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "QR auth interrupted");
            } catch (Exception e) {
                if (cancelled) return;
                Log.e(TAG, "QR auth error: " + SteamLogRedactor.redact(Log.getStackTraceString(e)));
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                mainHandler.post(() -> listener.onFailure(msg));
            }
        }, "SteamQrLogin").start();
    }

    /** Cancel any pending QR auth session. */
    public void cancel() {
        cancelled = true;
        currentListener = null;
        if (rustQrActive) {
            rustQrActive = false;
            BlSteamSession s = BlSteamEngine.INSTANCE.session();
            if (s != null) {
                try { s.cancelLogin(); } catch (Throwable t) { Log.w(TAG, "cancelLogin failed", t); }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Rust engine path
    // -------------------------------------------------------------------------

    private volatile boolean rustQrActive = false;

    private void startQrLoginRust(QrAuthListener listener) {
        BlSteamSession session = BlSteamEngine.INSTANCE.session();
        if (session == null || !BlSteamEngine.INSTANCE.isConnected()) {
            mainHandler.post(() -> listener.onFailure("Not connected to Steam"));
            return;
        }
        rustQrActive = true;
        final boolean[] shown = { false };
        Log.i(TAG, "QR sign-in starting on the Rust engine");
        BlSteamEngineLog.log("AUTH", "QR sign-in started");
        try {
            session.startLoginWithQr(
                    url -> {
                        if (cancelled || listener != currentListener) return;
                        final boolean first;
                        synchronized (shown) { first = !shown[0]; shown[0] = true; }
                        mainHandler.post(() -> {
                            if (cancelled || listener != currentListener) return;
                            if (first) listener.onQrReady(url); else listener.onQrRefreshed(url);
                        });
                    },
                    result -> onRustQrResult(result, listener));
        } catch (Throwable t) {
            rustQrActive = false;
            Log.e(TAG, "Rust QR login could not start: " + SteamLogRedactor.redact(Log.getStackTraceString(t)));
            String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            mainHandler.post(() -> listener.onFailure(msg));
        }
    }

    private void onRustQrResult(BlAuthResult result, QrAuthListener listener) {
        if (cancelled || listener != currentListener) return;
        if (result.getSuccess() && !result.getRefreshToken().isEmpty()) {
            rustQrActive = false;
            String accountName = result.getAccountName();
            String finalName = (accountName != null && !accountName.isEmpty()) ? accountName : "Steam User";
            // The QR poll never learns the SteamID directly; the refresh token's JWT `sub` carries it.
            long sid = result.getSteamId();
            if (sid == 0L) sid = SteamSessionManager.jwtSteamId64(result.getRefreshToken());
            SteamRepository.getInstance().saveSession(finalName, result.getRefreshToken(), sid);
            Log.i(TAG, "QR sign-in OK on the Rust engine (steamId known=" + (sid != 0L) + ")");
            BlSteamEngineLog.log("AUTH", "QR sign-in OK (steamId known=" + (sid != 0L) + ")");
            mainHandler.post(() -> listener.onSuccess(finalName, result.getRefreshToken()));
            return;
        }
        if (result.getHadRemoteInteraction() && result.getErrorMessage().isEmpty()) {
            // The phone scanned the code; approval still pending — the poll loop continues.
            Log.i(TAG, "QR scanned on the phone — waiting for approval");
            return;
        }
        rustQrActive = false;
        String msg = result.getErrorMessage().isEmpty()
                ? SteamRepository.eresultName(result.getErrorCode()) : result.getErrorMessage();
        Log.w(TAG, "QR sign-in failed on the Rust engine: eresult=" + result.getErrorCode()
                + " " + SteamLogRedactor.redact(msg));
        BlSteamEngineLog.log("AUTH", "QR sign-in FAILED eresult=" + result.getErrorCode()
                + " (" + SteamRepository.eresultName(result.getErrorCode()) + ")");
        mainHandler.post(() -> listener.onFailure(msg));
    }

    // -------------------------------------------------------------------------
    // URL rotation watcher
    // -------------------------------------------------------------------------

    /**
     * Polls getChallengeUrl() every 3 seconds and notifies the UI when the URL
     * changes. Steam rotates the challenge URL roughly every 30 seconds.
     * Stops automatically when the auth completes (pollingWaitForResult returns)
     * or when cancel() is called.
     */
    private void startUrlRefreshWatcher(QrAuthSession session, QrAuthListener listener) {
        new Thread(() -> {
            String lastUrl = session.getChallengeUrl();
            while (!cancelled) {
                try { Thread.sleep(3_000); } catch (InterruptedException e) { break; }
                if (cancelled) break;
                try {
                    String currentUrl = session.getChallengeUrl();
                    if (currentUrl != null && !currentUrl.equals(lastUrl)) {
                        lastUrl = currentUrl;
                        String finalUrl = currentUrl;
                        mainHandler.post(() -> {
                            if (!cancelled && listener == currentListener) {
                                listener.onQrRefreshed(finalUrl);
                            }
                        });
                    }
                } catch (Exception e) {
                    Log.w(TAG, "URL watcher error: " + SteamLogRedactor.redact(Log.getStackTraceString(e)));
                    break;
                }
            }
        }, "QrUrlWatcher").start();
    }
}
