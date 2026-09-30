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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Watchdog in a separate process (":heartbeat"):
 * every 5 seconds it re-launches {@link SentryService} and refreshes the
 * heartbeat timestamp in device-protected prefs. If the main process is
 * killed, this process survives and revives the watchdog within 5 s.
 * {@link SentryRestartReceiver} treats a stale timestamp (>30 s) as a dead
 * heartbeat and restarts the watchdog itself.
 */
public class SentryHeartbeatService extends Service {

    private static final String CHANNEL_ID = "SentryHeartbeatServiceChannel";
    private static final int NOTIFICATION_ID = 2;
    private static final long BEAT_INTERVAL_MS = 5_000;

    private final AtomicBoolean beating = new AtomicBoolean(false);
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.heartbeat_channel_name),
                NotificationManager.IMPORTANCE_MIN);
        channel.setDescription(getString(R.string.heartbeat_channel_description));
        ((NotificationManager) getSystemService(NotificationManager.class))
                .createNotificationChannel(channel);

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.heartbeat_notification_title))
                .setContentText(getString(R.string.heartbeat_notification_text))
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_MIN)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        startBeating();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startBeating();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        beating.set(false);
        main.removeCallbacksAndMessages(null);
        sendBroadcast(new Intent(this, SentryRestartReceiver.class));
    }

    private void startBeating() {
        if (beating.getAndSet(true)) {
            return;
        }
        main.post(this::beat);
    }

    private void beat() {
        if (!beating.get()) {
            return;
        }
        SentryService.sentryPrefs(this)
                .edit()
                .putLong(SentryService.KEY_HEARTBEAT_TIMESTAMP, System.currentTimeMillis())
                .apply();
        try {
            startForegroundService(new Intent(this, SentryService.class));
        } catch (Exception ignored) {
        }
        main.postDelayed(this::beat, BEAT_INTERVAL_MS);
    }
}
