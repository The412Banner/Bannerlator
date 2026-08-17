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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

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
    private static final String TAG = "GameSessionFgService";
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
        // The typed startForeground overload (with the declared foregroundServiceType) exists since
        // API 29 — same gating as the store/download and unpack foreground services
        // (DownloadForegroundService, UnpackService). targetSdk 28 => classic FGS semantics.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
        NotificationChannel existing = nm.getNotificationChannel(XServerDisplayActivity.NOTIFICATION_CHANNEL_ID);
        if (existing != null && existing.getImportance() > NotificationManager.IMPORTANCE_LOW) {
            nm.deleteNotificationChannel(XServerDisplayActivity.NOTIFICATION_CHANNEL_ID);
            existing = null;
        }
        if (existing == null) {
            NotificationChannel channel = new NotificationChannel(
                    XServerDisplayActivity.NOTIFICATION_CHANNEL_ID, "Winlator",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Winlator XServer Messages");
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(@Nullable String label) {
        Intent contentIntent = new Intent(this, XServerDisplayActivity.class);
        contentIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, contentIntent,
                PendingIntent.FLAG_IMMUTABLE);
        String text = (label != null && !label.isEmpty())
                ? label + " is running — do not swipe this notification"
                : "Winlator is running, do not kill or swipe this notification";
        return new NotificationCompat.Builder(this, XServerDisplayActivity.NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_ab_gear_0011)
                .setContentTitle("Winlator")
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setAutoCancel(false)
                .build();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.i(TAG, "Task removed (user swipe). Tearing down session and exiting process.");
        // Recents-swipe destroys the activity WITHOUT running XServerDisplayActivity.exit(), so the
        // wine session would otherwise survive as an orphan (its stale wineserver/process tree then
        // fouls the next launch). Defensively tear it down here — the activity's own teardown, if it
        // happens to be racing, is idempotent — then drop the notification, stop the service and
        // exit the process, so swipe behaves like the pre-existing "close everything" flow. Mirrors
        // WinNative's SessionKeepAliveService.onTaskRemoved().
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                ProcessHelper.terminateAllWineProcessesAndWait(1500, true);
            } catch (Throwable t) {
                Log.w(TAG, "Defensive wine cleanup on task removal failed", t);
            }
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            Process.killProcess(Process.myPid());
        }, 1500L);
    }

    @Override
    public void onDestroy() {
        // Nothing to release here: the foreground notification is auto-removed when the service
        // stops, and wine teardown is owned by XServerDisplayActivity.exit() (in-app exit) and
        // onTaskRemoved() (recents-swipe). An extra sweep here would be unsafe — the system may
        // destroy the service while a session is legitimately running in the background.
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
