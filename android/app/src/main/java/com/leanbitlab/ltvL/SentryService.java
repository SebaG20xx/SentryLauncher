/*
 * FLauncher
 * Copyright (C) 2024 LeanBitLab
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.omeda.arc;

import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Foreground watchdog (always on):
 * <ul>
 *   <li>Polls UsageStats every 350 ms (150 ms while a rival launcher is visible)
 *       to find out which package is in the foreground.</li>
 *   <li>When a rival launcher takes the screen, throws a fullscreen black
 *       overlay ("going home, please wait...") that swallows every key, and
 *       relaunches {@link MainActivity} until the launcher is back.</li>
 *   <li>Reassertion falls back to a notification when the overlay permission
 *       is missing.</li>
 *   <li>{@link SentryHeartbeatService} (separate process) and the
 *       {@link SentryRestartReceiver} alarm resurrect this service.</li>
 * </ul>
 */
public class SentryService extends Service {

    static final String PREFS = "sentry_prefs";
    static final String KEY_HEARTBEAT_TIMESTAMP = "heartbeat_timestamp";
    static final String ACTION_FORCE_REASSERT_FOCUS = "cl.sebag20xx.sentry.action.FORCE_REASSERT_FOCUS";

    private static final String CHANNEL_ID = "SentryServiceChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final int RESTORE_NOTIFICATION_ID = 1000;
    private static final int RESTART_ALARM_REQUEST_CODE = 1001;

    private static final long NORMAL_INTERVAL_MS = 350;
    private static final long ALERT_INTERVAL_MS = 150;
    private static final long ALERT_TIMEOUT_MS = 30_000;
    private static final long REASSERT_RETRY_MS = 2_000;
    private static final long RESTORE_NOTIFY_THROTTLE_MS = 10_000;
    static final long HEARTBEAT_STALE_MS = 30_000;
    static final long RESTART_ALARM_MS = 90_000;

    private WindowManager windowManager;
    private FrameLayout overlay;
    private boolean overlayShowing;
    /** One engagement per rival episode, either the overlay or the degraded notification path. */
    private boolean episodeEngaged;
    private boolean alertMode;
    private long lastRivalAtMs;
    private long lastDegradedReassertAtMs;
    private long lastRestoreNotificationAtMs;
    private volatile boolean destroyed;

    /** Last package seen moving to the foreground; survives the 60 s events window. */
    private String lastForegroundPackage;

    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private ScheduledFuture<?> tickFuture;

    static SharedPreferences sentryPrefs(Context context) {
        return context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    static void armRestartAlarm(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        PendingIntent pendingIntent = restartAlarmPendingIntent(context);
        alarmManager.cancel(pendingIntent);
        long at = SystemClock.elapsedRealtime() + RESTART_ALARM_MS;
        // Exact alarms carry the background FGS-launch exemption on Android 12+;
        // fall back to the inexact variant where exact alarms are not allowed.
        if (Build.VERSION.SDK_INT >= 31 && alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pendingIntent);
        } else {
            alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pendingIntent);
        }
    }

    static void cancelRestartAlarm(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            alarmManager.cancel(restartAlarmPendingIntent(context));
        }
    }

    private static PendingIntent restartAlarmPendingIntent(Context context) {
        return PendingIntent.getBroadcast(
                context,
                RESTART_ALARM_REQUEST_CODE,
                new Intent(context, SentryRestartReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        } catch (Exception ignored) {
            stopSelf();
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.sentry_channel_name),
                NotificationManager.IMPORTANCE_MIN);
        channel.setDescription(getString(R.string.sentry_channel_description));
        channel.setSound(null, null);
        channel.enableLights(false);
        channel.enableVibration(false);
        ((NotificationManager) getSystemService(NotificationManager.class)).createNotificationChannel(channel);

        Notification notification = buildForegroundNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_FORCE_REASSERT_FOCUS.equals(intent.getAction())) {
            reassertFocus();
        }
        if (tickFuture == null) {
            scheduleTick(NORMAL_INTERVAL_MS);
        }
        armRestartAlarm(this);
        try {
            startForegroundService(new Intent(this, SentryHeartbeatService.class));
        } catch (Exception ignored) {
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        destroyed = true;
        if (tickFuture != null) {
            tickFuture.cancel(true);
        }
        poller.shutdownNow();
        main.removeCallbacksAndMessages(null);
        removeOverlay();
    }

    private void scheduleTick(long delayMs) {
        if (destroyed) {
            return;
        }
        try {
            tickFuture = poller.schedule(() -> {
                String foreground = foregroundPackage();
                if (!destroyed) {
                    main.post(() -> onPolled(foreground));
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
        }
    }

    /** Runs on the main thread with the freshly polled foreground package. */
    private void onPolled(String foregroundPackage) {
        if (destroyed) {
            return;
        }
        boolean rival = foregroundPackage != null
                && KeySentryService.RIVAL_LAUNCHERS.contains(foregroundPackage);
        long now = SystemClock.uptimeMillis();
        if (rival) {
            lastRivalAtMs = now;
            alertMode = true;
            if (!episodeEngaged) {
                episodeEngaged = true;
                showOverlay();
            } else if (!overlayShowing && now - lastDegradedReassertAtMs >= REASSERT_RETRY_MS) {
                // Degraded mode (no overlay permission): keep fighting for the
                // front, at most once every couple of seconds.
                lastDegradedReassertAtMs = now;
                reassertFocus();
            }
        } else if (episodeEngaged) {
            endEpisode();
        }
        if (alertMode && now - lastRivalAtMs > ALERT_TIMEOUT_MS) {
            alertMode = false;
        }
        scheduleTick(alertMode ? ALERT_INTERVAL_MS : NORMAL_INTERVAL_MS);
    }

    /** The launcher is back in front: drop the overlay and the restore notification. */
    private void endEpisode() {
        episodeEngaged = false;
        removeOverlay();
        try {
            NotificationManager notificationManager =
                    (NotificationManager) getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.cancel(RESTORE_NOTIFICATION_ID);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Last package that moved to the foreground. When the 60 s usage-events
     * window yields nothing (blocked launch, idle screen) or access is
     * missing, falls back to the last known package so the watchdog keeps
     * fighting instead of giving up after a minute.
     * Blocking; call off the main thread.
     */
    String foregroundPackage() {
        if (!hasUsageStatsAccess()) {
            return lastForegroundPackage;
        }
        try {
            UsageStatsManager usageStatsManager =
                    (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            if (usageStatsManager == null) {
                return lastForegroundPackage;
            }
            long now = System.currentTimeMillis();
            UsageEvents events = usageStatsManager.queryEvents(now - 60_000, now);
            UsageEvents.Event event = new UsageEvents.Event();
            String packageName = null;
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    packageName = event.getPackageName();
                }
            }
            if (packageName != null) {
                lastForegroundPackage = packageName;
            }
            return packageName != null ? packageName : lastForegroundPackage;
        } catch (Exception ignored) {
            return lastForegroundPackage;
        }
    }

    private boolean hasUsageStatsAccess() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        if (appOps == null) {
            return false;
        }
        int mode = Build.VERSION.SDK_INT >= 29
                ? appOps.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName())
                : appOps.checkOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /** Fullscreen black overlay with a spinner, swallowing every key. */
    private void showOverlay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !Settings.canDrawOverlays(this)) {
            // Degraded mode: no overlay possible, notify once and fight for the front.
            lastDegradedReassertAtMs = SystemClock.uptimeMillis();
            reassertFocus();
            return;
        }
        FrameLayout frameLayout = new FrameLayout(this);
        frameLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frameLayout.setBackgroundColor(Color.BLACK);

        ProgressBar spinner = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        spinner.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        frameLayout.addView(spinner);

        TextView message = new TextView(this);
        message.setText(R.string.sentry_overlay_returning_home);
        message.setTextColor(Color.WHITE);
        message.setTextSize(22);
        message.setGravity(Gravity.CENTER);
        message.setShadowLayer(10, 0, 0, Color.BLACK);
        FrameLayout.LayoutParams messageParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        messageParams.bottomMargin = 200;
        frameLayout.addView(message, messageParams);

        frameLayout.setFocusableInTouchMode(true);
        frameLayout.requestFocus();
        frameLayout.setOnKeyListener((view, keyCode, event) -> true);

        WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, 0, PixelFormat.TRANSLUCENT);
        // Clear FLAG_NOT_FOCUSABLE so the overlay can swallow remote keys.
        layoutParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        layoutParams.gravity = Gravity.CENTER;
        try {
            windowManager.addView(frameLayout, layoutParams);
            overlay = frameLayout;
            overlayShowing = true;
            frameLayout.requestFocus();
        } catch (Exception ignored) {
            return;
        }
        reassertFocus();
        scheduleReassertCheck();
    }

    /**
     * Every 2 s: if a rival is still in the foreground, relaunch and check
     * again; once the launcher is back in front, end the episode.
     */
    private void scheduleReassertCheck() {
        if (destroyed) {
            return;
        }
        main.postDelayed(() -> {
            if (destroyed) {
                return;
            }
            try {
                poller.execute(() -> {
                    String foreground = foregroundPackage();
                    if (destroyed) {
                        return;
                    }
                    main.post(() -> {
                        if (destroyed || !overlayShowing) {
                            return;
                        }
                        if (foreground != null
                                && KeySentryService.RIVAL_LAUNCHERS.contains(foreground)) {
                            reassertFocus();
                            scheduleReassertCheck();
                        } else {
                            endEpisode();
                        }
                    });
                });
            } catch (RejectedExecutionException ignored) {
            }
        }, REASSERT_RETRY_MS);
    }

    private void removeOverlay() {
        overlayShowing = false;
        FrameLayout view = overlay;
        overlay = null;
        if (view != null) {
            try {
                windowManager.removeView(view);
            } catch (Exception ignored) {
            }
        }
    }

    /** Brings the launcher back; falls back to a notification without overlay permission. */
    private void reassertFocus() {
        if (!Settings.canDrawOverlays(this)) {
            notifyRestore();
            return;
        }
        try {
            Intent intent = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
        } catch (Exception ignored) {
            notifyRestore();
        }
    }

    private void notifyRestore() {
        long now = SystemClock.uptimeMillis();
        if (now - lastRestoreNotificationAtMs < RESTORE_NOTIFY_THROTTLE_MS) {
            return;
        }
        lastRestoreNotificationAtMs = now;
        try {
            NotificationManager notificationManager =
                    (NotificationManager) getSystemService(NotificationManager.class);
            if (notificationManager == null) {
                return;
            }
            Intent intent = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText(getString(R.string.sentry_restoring_interface))
                    .setOngoing(true)
                    .setCategory(Notification.CATEGORY_CALL)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build();
            notificationManager.notify(RESTORE_NOTIFICATION_ID, notification);
        } catch (Exception ignored) {
        }
    }

    private Notification buildForegroundNotification() {
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.sentry_notification_title))
                .setContentText(getString(R.string.sentry_notification_text))
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MIN)
                .build();
    }
}
