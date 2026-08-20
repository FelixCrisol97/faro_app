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
  static const _collapsedServerIdsKey = 'faro.administracion_collapsed_servers';

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

  /// Which server cards Administración should render collapsed — real gap
  /// reported 2026-08-12: every card always opened back up on returning to
  /// the tab, with no way to keep a rarely-touched server tucked away.
  /// Stores which servers are collapsed rather than which are expanded, so
  /// a newly-added server defaults to open without needing its id written
  /// down anywhere first.
  Set<String> loadCollapsedServerIds() =>
      (_prefs.getStringList(_collapsedServerIdsKey) ?? const []).toSet();

  Future<void> saveCollapsedServerIds(Set<String> ids) =>
      _prefs.setStringList(_collapsedServerIdsKey, ids.toList());
}
