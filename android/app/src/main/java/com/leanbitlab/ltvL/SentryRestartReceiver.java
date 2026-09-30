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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Fired by the 90 s re-arm alarm and by {@link SentryHeartbeatService} on death:
 * if the heartbeat timestamp is stale (>30 s, i.e. the heartbeat process is
 * gone), restarts {@link SentryService}, then always re-arms the alarm.
 */
public class SentryRestartReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        long lastBeat = SentryService.sentryPrefs(context)
                .getLong(SentryService.KEY_HEARTBEAT_TIMESTAMP, 0);
        if (System.currentTimeMillis() - lastBeat >= SentryService.HEARTBEAT_STALE_MS) {
            try {
                context.startForegroundService(new Intent(context, SentryService.class));
            } catch (Exception ignored) {
            }
        }
        SentryService.armRestartAlarm(context);
    }
}
