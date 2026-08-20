import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

import '../models/history_entry.dart';

/// Persists Historial — same shape as `ServersRepository`. Every open
/// window (main or query window) can append an entry after a run finishes,
/// so `HistoryNotifier` reads fresh from here immediately before every
/// save instead of blindly overwriting with its own in-memory copy — see
/// that notifier's doc comment.
class HistorialRepository {
  const HistorialRepository(this._prefs);

  final SharedPreferences _prefs;

  static const _key = 'faro.historial';

  List<HistoryEntry> load() {
    final raw = _prefs.getString(_key);
    if (raw == null) return const [];
    final decoded = jsonDecode(raw) as List;
    return decoded
        .map((e) => HistoryEntry.fromJson(e as Map<String, Object?>))
        .toList();
  }

  Future<void> saveAll(List<HistoryEntry> entries) {
    final encoded = jsonEncode(entries.map((e) => e.toJson()).toList());
    return _prefs.setString(_key, encoded);
  }
}
