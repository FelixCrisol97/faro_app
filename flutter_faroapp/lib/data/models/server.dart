import '../../core/constants/db_engine.dart';
import 'database_entry.dart';

/// A named, freely-defined, **optional** grouping of databases — e.g. a
/// chain of bodega branches (each with its own IP), a central host with
/// several company databases, or any other grouping criteria (by function,
/// by schema, etc.) the user finds useful. Databases are added/removed
/// dynamically; nothing about a servidor assumes a fixed shape, including
/// engine (see [DatabaseEntry.engine] — **moved off this class 2026-08-13**,
/// user-requested: "cada grupo/servidor puede tener diferentes motores").
/// There is deliberately no server-level host (see [DatabaseEntry.host]) or
/// read-only mode (see [DatabaseEntry.mode]) either — a servidor is only a
/// grouping, so it shouldn't force every database inside it to share any of
/// those.
///
/// A database never *needs* a `Server` at all — see `ServersState`'s
/// `ungroupedDatabases` ("Sin grupo"). Grouping is entirely the user's own,
/// later, optional choice via drag-and-drop
/// (`ServersNotifier.createServerFromTwoUngroupedDatabases`/
/// `moveDatabaseToServer`), never a prerequisite for creating or querying
/// a database.
class Server {
  const Server({
    required this.id,
    required this.name,
    this.databases = const [],
  });

  final String id;
  final String name;
  final List<DatabaseEntry> databases;

  int get selectedCount => databases.where((db) => db.selected).length;

  Server copyWith({
    String? id,
    String? name,
    List<DatabaseEntry>? databases,
  }) {
    return Server(
      id: id ?? this.id,
      name: name ?? this.name,
      databases: databases ?? this.databases,
    );
  }

  Map<String, Object?> toJson() => {
        'id': id,
        'name': name,
        'databases': databases.map((db) => db.toJson()).toList(),
      };

  /// [json]'s own `engine` key (present only in files persisted before
  /// 2026-08-13, when engine lived on the server instead of per-database)
  /// is read here and passed down as every one of this server's
  /// databases' fallback — see [DatabaseEntry.fromJson]'s `fallbackEngine`
  /// doc comment for the full migration story. The server itself no
  /// longer has an `engine` field to store that value on even if present.
  factory Server.fromJson(Map<String, Object?> json) {
    final legacyEngineName = json['engine'] as String?;
    final legacyEngine = legacyEngineName == null
        ? null
        : DbEngine.values.byName(legacyEngineName);
    return Server(
      id: json['id'] as String,
      name: json['name'] as String,
      databases: (json['databases'] as List)
          .map((db) => DatabaseEntry.fromJson(db as Map<String, Object?>,
              fallbackEngine: legacyEngine))
          .toList(),
    );
  }
}
