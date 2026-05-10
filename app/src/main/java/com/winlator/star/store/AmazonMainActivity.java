package com.winlator.star.store;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Entry point for the Amazon Games integration.
 *
 * Shows either a login card or a signed-in card depending on whether
 * AmazonCredentialStore has a valid access_token.
 *
 * Launched from the side menu (ID=11 / 0xb).
 * On resume (return from AmazonLoginActivity) refreshes card visibility.
 */
public class AmazonMainActivity extends Activity {

    private LinearLayout loginCard;
    private LinearLayout loggedInCard;
    private TextView     statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0D0D0D);

        loginCard    = buildLoginCard();
        loggedInCard = buildLoggedInCard();

        root.addView(loginCard,    new FrameLayout.LayoutParams(-1, -1));
        root.addView(loggedInCard, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        refreshView();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshView();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void refreshView() {
        if (loginCard == null) return;
        boolean loggedIn = AmazonCredentialStore.isLoggedIn(this);
        loginCard.setVisibility(loggedIn ? View.GONE  : View.VISIBLE);
        loggedInCard.setVisibility(loggedIn ? View.VISIBLE : View.GONE);

        if (loggedIn && statusView != null) {
            AmazonCredentialStore.Credentials creds = AmazonCredentialStore.load(this);
            if (creds != null) {
                long minutesLeft = (creds.expiresAt - System.currentTimeMillis()) / 60000L;
                statusView.setText("Signed in to Amazon Games\nToken expires in ~"
                        + minutesLeft + " min");
            }
        }
    }

    // ── Login card ────────────────────────────────────────────────────────────

    private LinearLayout buildLoginCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(0xFF1A1410);
        card.setGravity(Gravity.CENTER);
        int pad = dp(40);
        card.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Amazon Games");
        title.setTextSize(32f);
        title.setTextColor(0xFFFF9900);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(null, Typeface.BOLD);
        card.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Sign in to access your Amazon game library");
        sub.setTextSize(14f);
        sub.setTextColor(0xFFAAAAAA);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-2, -2);
        subLp.topMargin = dp(16);
        card.addView(sub, subLp);

        Button loginBtn = new Button(this);
        loginBtn.setText("Login with Amazon");
        loginBtn.setBackgroundColor(0xFFFF9900);
        loginBtn.setTextColor(0xFF000000);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-2, dp(48));
        btnLp.topMargin = dp(24);
        loginBtn.setOnClickListener(v ->
                startActivity(new Intent(this, AmazonLoginActivity.class)));
        card.addView(loginBtn, btnLp);

        return card;
    }

    // ── Logged-in card ────────────────────────────────────────────────────────

    private LinearLayout buildLoggedInCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(0xFF1A1410);
        card.setGravity(Gravity.CENTER);
        int pad = dp(40);
        card.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Amazon Games");
        title.setTextSize(32f);
        title.setTextColor(0xFFFF9900);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(null, Typeface.BOLD);
        card.addView(title);

        statusView = new TextView(this);
        statusView.setText("");
        statusView.setTextSize(13f);
        statusView.setTextColor(0xFFCCCCCC);
        statusView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-2, -2);
        statusLp.topMargin = dp(16);
        card.addView(statusView, statusLp);

        Button libraryBtn = new Button(this);
        libraryBtn.setText("View Game Library");
        libraryBtn.setBackgroundColor(0xFFFF9900);
        libraryBtn.setTextColor(0xFF000000);
        LinearLayout.LayoutParams libLp = new LinearLayout.LayoutParams(-2, dp(48));
        libLp.topMargin = dp(24);
        libraryBtn.setOnClickListener(v ->
                startActivity(new Intent(this, AmazonGamesActivity.class)));
        card.addView(libraryBtn, libLp);

        Button signOutBtn = new Button(this);
        signOutBtn.setText("Sign Out");
        signOutBtn.setBackgroundColor(0xFF444444);
        signOutBtn.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams soLp = new LinearLayout.LayoutParams(-2, dp(48));
        soLp.topMargin = dp(16);
        signOutBtn.setOnClickListener(v -> signOut());
        card.addView(signOutBtn, soLp);

        return card;
    }

    // ── Sign out ──────────────────────────────────────────────────────────────

    private void signOut() {
        AmazonCredentialStore.Credentials creds = AmazonCredentialStore.load(this);
        if (creds != null && creds.accessToken != null) {
            String token = creds.accessToken;
            new Thread(() -> AmazonAuthClient.deregisterDevice(token)).start();
        }
        AmazonCredentialStore.clear(this);
        refreshView();
        Toast.makeText(this, "Signed out of Amazon Games", Toast.LENGTH_SHORT).show();
    }
}
