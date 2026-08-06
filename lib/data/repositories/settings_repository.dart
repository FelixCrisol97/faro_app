import 'package:shared_preferences/shared_preferences.dart';

import '../../core/theme/app_accent.dart';

/// Theme (light/dark) + accent (one of six brand hues) — the only two
/// settings README.md calls out as persisting across sessions (Apariencia
/// screen). `loadAccent`'s lenient fallback also means a value persisted
/// under the old terracotta/sage naming (pre-2026-07-17 redesign) just
/// resolves to the default instead of crashing.
class SettingsRepository {
  const SettingsRepository(this._prefs);

  final SharedPreferences _prefs;

  static const _themeKey = 'faro.theme_is_dark';
  static const _accentKey = 'faro.accent';

  bool loadIsDark() => _prefs.getBool(_themeKey) ?? false;

  Future<void> saveIsDark(bool isDark) => _prefs.setBool(_themeKey, isDark);

  AppAccent loadAccent() {
    final stored = _prefs.getString(_accentKey);
    return AppAccent.values.firstWhere(
      (a) => a.name == stored,
      orElse: () => AppAccent.indigo,
    );
  }

  Future<void> saveAccent(AppAccent accent) =>
      _prefs.setString(_accentKey, accent.name);
}
