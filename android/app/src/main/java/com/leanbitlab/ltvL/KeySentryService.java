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

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Captures remote-control keys (HOME, TV, GUIDE, DVR, SETTINGS, APP_SWITCH) and
 * re-asserts the launcher whenever another launcher grabs the screen.
 * <p>
 * Key filtering is
 * requested through {@code flagRequestFilterKeyEvents} in
 * {@code res/xml/accessibility_service_config.xml}, so {@link #onKeyEvent}
 * receives hardware key events before they reach the foreground app.
 */
public class KeySentryService extends AccessibilityService {

    /**
     * Launchers that must never stay in the foreground. When one of them takes
     * the screen (TYPE_WINDOW_STATE_CHANGED), we press BACK and bring Arc
     * Launcher back to the front.
     */
    public static final Set<String> RIVAL_LAUNCHERS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "com.tivo.hydra.app",
            "com.google.android.tvlauncher",
            "com.android.launcher")));

    public static boolean isEnabled(Context context) {
        ComponentName expected = new ComponentName(context, KeySentryService.class);
        String enabled = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) {
            return false;
        }
        for (String entry : enabled.split(":")) {
            // Some OEMs store the short form ("pkg/.Class"); unflatten expands it.
            ComponentName componentName = ComponentName.unflattenFromString(entry);
            if (expected.equals(componentName)) {
                return true;
            }
        }
        return false;
    }

    public static void openSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            // Defensive: make sure key filtering survives OEMs that trim flags
            // declared in the service config.
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null
                || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || event.getPackageName() == null) {
            return;
        }
        String packageName = event.getPackageName().toString();
        if (!RIVAL_LAUNCHERS.contains(packageName)) {
            return;
        }
        // No synthetic BACK: it is delivered asynchronously and can land once
        // Arc is already in the foreground, closing its open panels/dialogs.
        // Bringing the launcher back to the front is enough to recover.
        bringLauncherToFront();
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        switch (keyCode) {
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_TV:
            case KeyEvent.KEYCODE_GUIDE:
            case KeyEvent.KEYCODE_DVR:
            case KeyEvent.KEYCODE_SETTINGS:
            case KeyEvent.KEYCODE_APP_SWITCH:
                // Consume DOWN and UP: the system and many apps react on
                // key-up (PhoneWindowManager launches HOME on UP).
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && keyCode == KeyEvent.KEYCODE_HOME) {
                    bringLauncherToFront();
                }
                return true;
            default:
                return super.onKeyEvent(event);
        }
    }

    private void bringLauncherToFront() {
        try {
            Intent intent = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(intent);
        } catch (Exception ignored) {
        }
    }
}
