import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

import '../models/server.dart';
import '../models/servers_state.dart';

/// Persists the Administración-configured server/database list, plus
/// databases not assigned to a server yet ("Sin grupo").
///
/// Stored as a single JSON blob under one key for now — fine at the
/// expected scale (tens of servers, each with tens of databases). Move to
/// a real local database (sqlite) if that stops being true.
///
/// Edits here are rare and deliberate (Administración, one window at a
/// time), so unlike `FavoritosRepository`/`HistorialRepository` this stays
/// a blind `saveAll(state)` overwrite rather than a read-merge-write —
/// see `ServersNotifier._persist()`.
///
/// Storage key versioning (2026-08-12, added alongside "Sin grupo"): the
/// old `_legacyKey` held a bare `List<Server>` and is never written to or
/// deleted anymore — it's an inert backup of whatever existed right before
/// upgrading. `_key` holds the new `{servers, ungroupedDatabases}` shape.
/// `load()` prefers `_key`; if that's absent (a config from before this
/// version), it falls back to decoding `_legacyKey` and wraps it as
/// `ServersState(servers: ..., ungroupedDatabases: const [])` — identical
/// to today's behavior byte-for-byte. A hypothetical downgrade to an older
/// Faro build would read the untouched legacy key and work exactly as
/// before, losing only edits made after the upgrade — never anything that
/// existed prior to it.
class ServersRepository {
  const ServersRepository(this._prefs);

  final SharedPreferences _prefs;

  static const _key = 'faro.servers.v2';
  static const _legacyKey = 'faro.servers';

  ServersState load() {
    final raw = _prefs.getString(_key);
    if (raw != null) {
      try {
        return ServersState.fromJson(jsonDecode(raw) as Map<String, Object?>);
      } catch (_) {
        // Corrupt/unreadable — fall through to the legacy key rather than
        // crashing app startup; worst case this loses only what was
        // written under `_key`, and `_legacyKey` is still untouched.
      }
    }

    final legacyRaw = _prefs.getString(_legacyKey);
    if (legacyRaw == null) return const ServersState();
    try {
      final decoded = jsonDecode(legacyRaw) as List;
      final servers = decoded
          .map((e) => Server.fromJson(e as Map<String, Object?>))
          .toList();
      return ServersState(servers: servers);
    } catch (_) {
      return const ServersState();
    }
  }

  Future<void> saveAll(ServersState state) {
    return _prefs.setString(_key, jsonEncode(state.toJson()));
  }
}
