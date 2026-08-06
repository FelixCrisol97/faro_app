import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

import '../models/server.dart';

/// Persists the Administración-configured server/database list.
///
/// Stored as a single JSON blob under one key for now — fine at the
/// expected scale (tens of servers, each with tens of databases). Move to
/// a real local database (sqlite) if that stops being true.
///
/// Edits here are rare and deliberate (Administración, one window at a
/// time), so unlike `FavoritosRepository`/`HistorialRepository` this stays
/// a blind `saveAll(state)` overwrite rather than a read-merge-write —
/// see `ServersNotifier._persist()`.
class ServersRepository {
  const ServersRepository(this._prefs);

  final SharedPreferences _prefs;

  static const _key = 'faro.servers';

  List<Server> load() {
    final raw = _prefs.getString(_key);
    if (raw == null) return const [];
    final decoded = jsonDecode(raw) as List;
    return decoded
        .map((e) => Server.fromJson(e as Map<String, Object?>))
        .toList();
  }

  Future<void> saveAll(List<Server> servers) {
    final encoded = jsonEncode(servers.map((s) => s.toJson()).toList());
    return _prefs.setString(_key, encoded);
  }
}
