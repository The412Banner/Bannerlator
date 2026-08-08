package com.winlator.star.core;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.winlator.star.R;
import com.winlator.star.XServerDisplayActivity;

/**
 * Keeps a running container/game session alive while the app is backgrounded.
 *
 * <p>Without a foreground service, Android demotes the app to the cached-app bucket the moment it
 * loses focus (oom_score_adj 0 -&gt; 700 -&gt; 900, verified on-device) and the low-memory killer
 * reaps the guest wine/box64 processes; on return the app runs its own shutdown path
 * ("Shutting down..."). A real foreground service holds the process at perceptible priority so that
 * eviction can't happen. This replaces the old plain {@code NotificationManager.notify()} call in
 * {@link XServerDisplayActivity}, which posted the same "do not kill" notification but provided NO
 * process protection.
 *
 * <p>See {@code docs/session-foreground-service-plan.md}.
 */
public class GameSessionForegroundService extends Service {
    private static final String EXTRA_LABEL = "session_label";

    /** Intent that starts the session-keepalive service. {@code label} = the game/shortcut name (nullable). */
    public static Intent createIntent(Context context, @Nullable String label) {
        Intent intent = new Intent(context, GameSessionForegroundService.class);
        if (label != null) intent.putExtra(EXTRA_LABEL, label);
        return intent;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String label = intent != null ? intent.getStringExtra(EXTRA_LABEL) : null;
        createChannel();
        Notification notification = buildNotification(label);
        // startForeground(id, notification, type) requires API 34 for the typed overload; mirror the
        // gating used by the store/unpack foreground services (targetSdk 28 => classic FGS semantics).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(XServerDisplayActivity.NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(XServerDisplayActivity.NOTIFICATION_ID, notification);
        }
        // The activity stops us explicitly on exit; don't let the system resurrect us afterwards.
        return START_NOT_STICKY;
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        Context localized = ContextCompat.getContextForLanguage(this);
        NotificationChannel channel = new NotificationChannel(
                XServerDisplayActivity.NOTIFICATION_CHANNEL_ID,
                localized.getString(R.string.xserver_notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(localized.getString(R.string.xserver_notification_channel_description));
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification(@Nullable String label) {
        Context localized = ContextCompat.getContextForLanguage(this);
        Intent contentIntent = new Intent(this, XServerDisplayActivity.class);
        contentIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, contentIntent,
                PendingIntent.FLAG_IMMUTABLE);
        String text = (label != null && !label.isEmpty())
                ? localized.getString(R.string.game_session_running_named, label)
                : localized.getString(R.string.game_session_running);
        return new NotificationCompat.Builder(this, XServerDisplayActivity.NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_ab_gear_0011)
                .setContentTitle(localized.getString(R.string.xserver_notification_channel_name))
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setAutoCancel(false)
                .build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
