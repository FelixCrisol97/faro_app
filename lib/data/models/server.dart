import '../../core/constants/db_engine.dart';
import 'database_entry.dart';

/// A named, freely-defined grouping of databases that share one engine —
/// e.g. a chain of bodega branches (each with its own IP), a central host
/// with several company databases, or any other grouping criteria (by
/// function, by schema, etc.) the user finds useful. Databases are
/// added/removed dynamically; nothing about a servidor assumes a fixed
/// shape beyond "same engine". There is deliberately no server-level host
/// (see [DatabaseEntry.host]) or read-only mode (see [DatabaseEntry.mode])
/// — a servidor is only a grouping, so it shouldn't force every database
/// inside it to share either.
class Server {
  const Server({
    required this.id,
    required this.name,
    required this.engine,
    this.databases = const [],
  });

  final String id;
  final String name;
  final DbEngine engine;
  final List<DatabaseEntry> databases;

  int get selectedCount => databases.where((db) => db.selected).length;

  Server copyWith({
    String? id,
    String? name,
    DbEngine? engine,
    List<DatabaseEntry>? databases,
  }) {
    return Server(
      id: id ?? this.id,
      name: name ?? this.name,
      engine: engine ?? this.engine,
      databases: databases ?? this.databases,
    );
  }

  Map<String, Object?> toJson() => {
        'id': id,
        'name': name,
        'engine': engine.name,
        'databases': databases.map((db) => db.toJson()).toList(),
      };

  factory Server.fromJson(Map<String, Object?> json) => Server(
        id: json['id'] as String,
        name: json['name'] as String,
        engine: DbEngine.values.byName(json['engine'] as String),
        databases: (json['databases'] as List)
            .map((db) => DatabaseEntry.fromJson(db as Map<String, Object?>))
            .toList(),
      );
}
