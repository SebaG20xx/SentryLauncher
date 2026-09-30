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
 * Re-arms the sentry watchdog at boot:
 * <ul>
 *   <li>BOOT_COMPLETED: start {@link SentryService}; if that fails, start
 *       {@link SentryHeartbeatService}; if that also fails, schedule the 90 s
 *       restart alarm.</li>
 *   <li>QUICKBOOT_POWERON (standard and HTC variants) and
 *       LOCKED_BOOT_COMPLETED (direct boot, before user unlock): start the
 *       heartbeat, which revives the watchdog as soon as it can run.</li>
 * </ul>
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (action == null) {
            return;
        }
        switch (action) {
            case Intent.ACTION_BOOT_COMPLETED:
                try {
                    context.startForegroundService(new Intent(context, SentryService.class));
                    break;
                } catch (Exception ignored) {
                }
                try {
                    context.startForegroundService(new Intent(context, SentryHeartbeatService.class));
                    break;
                } catch (Exception ignored) {
                }
                SentryService.armRestartAlarm(context);
                break;
            case Intent.ACTION_LOCKED_BOOT_COMPLETED:
            case "android.intent.action.QUICKBOOT_POWERON":
            case "com.htc.intent.action.QUICKBOOT_POWERON":
                try {
                    context.startForegroundService(new Intent(context, SentryHeartbeatService.class));
                } catch (Exception ignored) {
                    SentryService.armRestartAlarm(context);
                }
                break;
            default:
                break;
        }
    }
}
