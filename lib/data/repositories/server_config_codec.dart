import 'dart:convert';

import 'package:uuid/uuid.dart';

import '../../core/constants/db_engine.dart';
import '../models/database_credentials.dart';
import '../models/server.dart';

const _uuid = Uuid();

/// Result of [ServerConfigCodec.decode] — the parsed servers, plus any
/// credentials the file happened to include, keyed by the *normalized*
/// server/database id (so callers can hand them straight to
/// `CredentialsRepository` without re-deriving ids themselves).
class ParsedServerConfig {
  const ParsedServerConfig({
    required this.servers,
    required this.serverCredentials,
    required this.databaseCredentials,
  });

  final List<Server> servers;
  final Map<String, DatabaseCredentials> serverCredentials;
  final Map<String, DatabaseCredentials> databaseCredentials;
}

/// Serializes/parses the Administración server list to/from a
/// human-editable JSON file, so a whole environment (servers + their
/// databases) can be authored outside the app and loaded in one shot
/// instead of clicking through "Agregar servidor"/"Agregar base de datos"
/// one at a time.
///
/// Credentials are excluded by default — they normally live only in
/// `CredentialsRepository` (OS secure storage), never in a plaintext file.
/// Both directions support them as an explicit opt-in instead, since a
/// hand-authored file is often exactly where you *want* to bootstrap known
/// logins in one shot: [encode] only embeds a server/database's
/// username/password if the caller passes it in (the export UI asks first,
/// since this makes the file sensitive); [decode] picks up `username`/
/// `password` when a file happens to include them, returned separately so
/// the caller can persist them properly rather than keeping them on the
/// `Server`/`DatabaseEntry` models themselves.
///
/// [decode] is lenient about `id`/`mode` (fills in a fresh uuid / read-only
/// default when absent) so a hand-written file doesn't need to invent ids —
/// [Server.fromJson]/[DatabaseEntry.fromJson] themselves stay strict, since
/// they're also used to load the app's own previously-exported state. `mode`
/// is a per-database field now; a file exported before 2026-07-18 (when it
/// lived on the server instead) still works — its server-level `mode`
/// becomes the fallback for any database in that file with no `mode` of
/// its own.
class ServerConfigCodec {
  static String encode(
    List<Server> servers, {
    Map<String, DatabaseCredentials> serverCredentials = const {},
    Map<String, DatabaseCredentials> databaseCredentials = const {},
  }) {
    final serialized = servers.map((s) {
      final json = s.toJson();
      final creds = serverCredentials[s.id];
      final databases =
          (json['databases'] as List).asMap().entries.map((entry) {
        final db =
            Map<String, Object?>.from(entry.value as Map<String, Object?>);
        final dbCreds = databaseCredentials[s.databases[entry.key].id];
        if (dbCreds != null) {
          db['username'] = dbCreds.username;
          db['password'] = dbCreds.password;
        }
        return db;
      }).toList();
      return {
        ...json,
        'databases': databases,
        if (creds != null) 'username': creds.username,
        if (creds != null) 'password': creds.password
      };
    }).toList();
    return const JsonEncoder.withIndent('  ').convert(serialized);
  }

  static ParsedServerConfig decode(String raw) {
    final decoded = jsonDecode(raw) as List;
    final servers = <Server>[];
    final serverCredentials = <String, DatabaseCredentials>{};
    final databaseCredentials = <String, DatabaseCredentials>{};

    for (final e in decoded) {
      final json = e as Map<String, Object?>;
      final normalized = _normalizeServer(json);
      servers.add(Server.fromJson(normalized));

      final serverId = normalized['id'] as String;
      final serverCreds = _credentialsOf(json);
      if (serverCreds != null) serverCredentials[serverId] = serverCreds;

      final rawDatabases =
          (json['databases'] as List? ?? const []).cast<Map<String, Object?>>();
      final normalizedDatabases = normalized['databases'] as List;
      for (var i = 0; i < rawDatabases.length; i++) {
        final dbCreds = _credentialsOf(rawDatabases[i]);
        if (dbCreds != null) {
          final databaseId =
              (normalizedDatabases[i] as Map<String, Object?>)['id'] as String;
          databaseCredentials[databaseId] = dbCreds;
        }
      }
    }

    return ParsedServerConfig(
      servers: servers,
      serverCredentials: serverCredentials,
      databaseCredentials: databaseCredentials,
    );
  }

  static DatabaseCredentials? _credentialsOf(Map<String, Object?> json) {
    final username = json['username'] as String?;
    if (username == null || username.isEmpty) return null;
    return (username: username, password: json['password'] as String? ?? '');
  }

  static Map<String, Object?> _normalizeServer(Map<String, Object?> json) {
    // `json['mode']` here is a pre-2026-07-18 server-level mode (back when
    // it lived on `Server`, not `DatabaseEntry`) — passed down only as a
    // fallback for databases that don't specify their own `mode`.
    final legacyServerMode = json['mode'] as String?;
    final databases = (json['databases'] as List? ?? const [])
        .map((db) =>
            _normalizeDatabase(db as Map<String, Object?>, legacyServerMode))
        .toList();
    return {
      'id': json['id'] as String? ?? _uuid.v4(),
      'name': json['name'],
      'engine': json['engine'],
      'databases': databases,
    };
  }

  static Map<String, Object?> _normalizeDatabase(
      Map<String, Object?> json, String? legacyServerMode) {
    final alias = json['alias'] ?? json['name'];
    return {
      'id': json['id'] as String? ?? _uuid.v4(),
      'alias': alias,
      'host': json['host'] ?? '',
      'databaseName': json['databaseName'] ?? alias,
      'mode': json['mode'] ?? legacyServerMode ?? ServerMode.readOnly.name,
    };
  }
}
