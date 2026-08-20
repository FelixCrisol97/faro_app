import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme/app_accent.dart';
import 'core_providers.dart';

/// Theme brightness — Apariencia's Claro/Oscuro segmented control.
/// Persists across sessions (README.md "State management").
class ThemeIsDarkNotifier extends Notifier<bool> {
  @override
  bool build() => ref.watch(settingsRepositoryProvider).loadIsDark();

  void set(bool isDark) {
    state = isDark;
    ref.read(settingsRepositoryProvider).saveIsDark(isDark);
  }

  void toggle() => set(!state);
}

final themeIsDarkProvider =
    NotifierProvider<ThemeIsDarkNotifier, bool>(ThemeIsDarkNotifier.new);

/// App-wide accent (one of six brand hues) — a single setting, not
/// per-element. Persists across sessions.
class AccentNotifier extends Notifier<AppAccent> {
  @override
  AppAccent build() => ref.watch(settingsRepositoryProvider).loadAccent();

  void set(AppAccent accent) {
    state = accent;
    ref.read(settingsRepositoryProvider).saveAccent(accent);
  }
}

final accentProvider =
    NotifierProvider<AccentNotifier, AppAccent>(AccentNotifier.new);
