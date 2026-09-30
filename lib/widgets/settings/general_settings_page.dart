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

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flauncher/flauncher_channel.dart';
import 'package:flauncher/l10n/app_localizations.dart';
import 'focusable_settings_tile.dart';
import 'brightness_settings_page.dart';
import 'date_time_format_page.dart';
import 'back_button_action_page.dart';
import 'wifi_usage_period_page.dart';
import 'screensaver_clock_style_page.dart';


class GeneralSettingsPage extends StatelessWidget {
  static const String routeName = "general_settings_panel";

  const GeneralSettingsPage({super.key});

  @override
  Widget build(BuildContext context) {
    AppLocalizations localizations = AppLocalizations.of(context)!;

    return Column(
      children: [
        Text(localizations.system, style: Theme.of(context).textTheme.titleLarge),
        const Divider(),
        Expanded(
          child: SingleChildScrollView(
            child: Column(
              children: [
                FocusableSettingsTile(
                  autofocus: true,
                  leading: const Icon(Icons.brightness_6),
                  title: Text(localizations.brightnessScheduler, style: Theme.of(context).textTheme.bodyMedium),
                  onPressed: () => Navigator.of(context).pushNamed(BrightnessSettingsPage.routeName),
                ),
                FocusableSettingsTile(
                  leading: const Icon(Icons.screenshot_monitor),
                  title: Text(localizations.screensaverSettings, style: Theme.of(context).textTheme.bodyMedium),
                  onPressed: () => _openScreensaverSettings(),
                ),
                FocusableSettingsTile(
                  leading: const Icon(Icons.watch_later_outlined),
                  title: Text(localizations.screensaverClockStyle, style: Theme.of(context).textTheme.bodyMedium),
                  onPressed: () => Navigator.of(context).pushNamed(ScreensaverClockStylePage.routeName),
                ),
                FocusableSettingsTile(
                  leading: const Icon(Icons.date_range),
                  title: Text(localizations.dateAndTimeFormat, style: Theme.of(context).textTheme.bodyMedium),
                  onPressed: () => Navigator.of(context).pushNamed(DateTimeFormatPage.routeName),
                ),
                FocusableSettingsTile(
                  leading: const Icon(Icons.arrow_back),
                  title: Text(localizations.backButtonAction, style: Theme.of(context).textTheme.bodyMedium),
                  onPressed: () => Navigator.of(context).pushNamed(BackButtonActionPage.routeName),
                ),
                FocusableSettingsTile(
                  leading: const Icon(Icons.wifi),
                  title: Text(localizations.wifiUsagePeriod, style: Theme.of(context).textTheme.bodyMedium),
                  onPressed: () => Navigator.of(context).pushNamed(WifiUsagePeriodPage.routeName),
                ),
                _KeySentryTile(),
                _SentryWatchdogTile(),

              ],
            ),
          ),
        ),
      ],
    );
  }

  Future<void> _openScreensaverSettings() async {
    const platform = MethodChannel('me.efesser.flauncher/method');
    platform.invokeMethod('openScreensaverSettings');
  }
}

/// Opens the system accessibility settings to enable/disable
/// [KeySentryService], and reflects its current state.
class _KeySentryTile extends StatefulWidget {
  @override
  State<_KeySentryTile> createState() => _KeySentryTileState();
}

class _KeySentryTileState extends State<_KeySentryTile> with WidgetsBindingObserver {
  final FLauncherChannel _channel = FLauncherChannel();
  bool? _enabled;
  bool _failed = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Coming back from the system accessibility settings: re-read the state.
    if (state == AppLifecycleState.resumed) {
      _refresh();
    }
  }

  Future<void> _refresh() async {
    bool? enabled;
    try {
      enabled = await _channel.isKeySentryEnabled();
    } catch (_) {
      // Native side too old or channel broken: show an error state, retry on press.
    }
    if (mounted) {
      setState(() {
        _enabled = enabled;
        _failed = enabled == null;
      });
    }
  }

  Future<void> _pressed() async {
    if (_failed) {
      await _refresh();
      return;
    }
    await _channel.openAccessibilitySettings();
  }

  @override
  Widget build(BuildContext context) {
    AppLocalizations localizations = AppLocalizations.of(context)!;
    ThemeData theme = Theme.of(context);

    return FocusableSettingsTile(
      leading: const Icon(Icons.shield_outlined),
      title: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(localizations.keySentry, style: theme.textTheme.bodyMedium),
          const SizedBox(height: 2),
          Text(
            localizations.keySentrySubtitle,
            style: theme.textTheme.bodySmall?.copyWith(color: Colors.white.withOpacity(0.6)),
          ),
        ],
      ),
      trailing: _failed
          ? const Icon(Icons.error_outline, size: 18, color: Colors.white38)
          : _enabled == null
              ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
              : Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(
                      _enabled! ? Icons.check_circle : Icons.cancel,
                      size: 18,
                      color: _enabled! ? Colors.green : Colors.white.withOpacity(0.4),
                    ),
                    const SizedBox(width: 6),
                    Text(
                      _enabled! ? localizations.keySentryOn : localizations.keySentryOff,
                      style: theme.textTheme.bodySmall?.copyWith(
                        color: _enabled! ? Colors.green : Colors.white.withOpacity(0.4),
                      ),
                    ),
                  ],
                ),
      onPressed: _pressed,
    );
  }
}

/// Status tile for the always-on sentry mode: guides the installer through
/// the permissions it needs (usage access for the watchdog polling, overlay
/// for the fullscreen cover). The cycle itself cannot be turned off.
class _SentryWatchdogTile extends StatefulWidget {
  @override
  State<_SentryWatchdogTile> createState() => _SentryWatchdogTileState();
}

class _SentryWatchdogTileState extends State<_SentryWatchdogTile> with WidgetsBindingObserver {
  final FLauncherChannel _channel = FLauncherChannel();
  bool? _usageStatsGranted;
  bool? _overlayPermitted;
  bool _failed = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Coming back from the system permission settings: re-read the state.
    if (state == AppLifecycleState.resumed) {
      _refresh();
    }
  }

  Future<void> _refresh() async {
    bool? usageStats;
    bool? overlay;
    try {
      final results = await Future.wait([
        _channel.checkUsageStatsPermission(),
        _channel.checkOverlayPermission(),
      ]);
      usageStats = results[0];
      overlay = results[1];
    } catch (_) {
      // Native side too old or channel broken: show an error state, retry on press.
    }
    if (mounted) {
      setState(() {
        _usageStatsGranted = usageStats;
        _overlayPermitted = overlay;
        _failed = usageStats == null || overlay == null;
      });
    }
  }

  Future<void> _pressed() async {
    if (_failed) {
      await _refresh();
      return;
    }
    if (_usageStatsGranted == false) {
      await _channel.requestUsageStatsPermission();
    } else if (_overlayPermitted == false) {
      await _channel.requestOverlayPermission();
    }
  }

  @override
  Widget build(BuildContext context) {
    AppLocalizations localizations = AppLocalizations.of(context)!;
    ThemeData theme = Theme.of(context);
    bool needsUsageStats = _usageStatsGranted == false;
    bool needsOverlay = !needsUsageStats && _overlayPermitted == false;
    bool missingSomething = needsUsageStats || needsOverlay;

    return FocusableSettingsTile(
      leading: const Icon(Icons.security),
      title: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(localizations.sentryWatchdog, style: theme.textTheme.bodyMedium),
          const SizedBox(height: 2),
          Text(
            needsUsageStats
                ? localizations.usagePermissionNeeded
                : needsOverlay
                    ? localizations.overlayPermissionNeeded
                    : localizations.sentryWatchdogSubtitle,
            style: theme.textTheme.bodySmall?.copyWith(
              color: missingSomething ? Colors.orange : Colors.white.withOpacity(0.6),
            ),
          ),
        ],
      ),
      trailing: _failed
          ? const Icon(Icons.error_outline, size: 18, color: Colors.white38)
          : _usageStatsGranted == null || _overlayPermitted == null
              ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
              : Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(
                      missingSomething ? Icons.warning : Icons.check_circle,
                      size: 18,
                      color: missingSomething ? Colors.orange : Colors.green,
                    ),
                    if (!missingSomething) ...[
                      const SizedBox(width: 6),
                      Text(
                        localizations.keySentryOn,
                        style: theme.textTheme.bodySmall?.copyWith(color: Colors.green),
                      ),
                    ],
                  ],
                ),
      onPressed: _pressed,
    );
  }
}
