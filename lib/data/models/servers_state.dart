import 'database_entry.dart';
import 'server.dart';

/// Everything `ServersNotifier` holds — the configured servers plus
/// databases that don't belong to any of them yet ("Sin grupo", 2026-08-12:
/// user asked to be able to create a database before deciding which group
/// it goes in, then drag it into place later). Kept as two flat lists
/// rather than giving `DatabaseEntry` a nullable `serverId`: every existing
/// mutator already works by rebuilding whichever list an entry lives in
/// (see `ServersNotifier._updateServer`/`_updateDatabase`), and a database's
/// only "membership" record staying "which list contains it" needed no
/// change to either model, just a second list to belong to.
class ServersState {
  const ServersState({
    this.servers = const [],
    this.ungroupedDatabases = const [],
  });

  final List<Server> servers;

  /// Not connectable — a database here has no `Server.engine` to tell Faro
  /// which driver to use, so nothing that needs a connection (query, test,
  /// discover, schema explorer, open in tab/window) is ever offered for one
  /// until it's dragged into a real server.
  final List<DatabaseEntry> ungroupedDatabases;

  ServersState copyWith({
    List<Server>? servers,
    List<DatabaseEntry>? ungroupedDatabases,
  }) {
    return ServersState(
      servers: servers ?? this.servers,
      ungroupedDatabases: ungroupedDatabases ?? this.ungroupedDatabases,
    );
  }

  Map<String, Object?> toJson() => {
        'servers': servers.map((s) => s.toJson()).toList(),
        'ungroupedDatabases':
            ungroupedDatabases.map((d) => d.toJson()).toList(),
      };

  factory ServersState.fromJson(Map<String, Object?> json) => ServersState(
        servers: (json['servers'] as List)
            .map((e) => Server.fromJson(e as Map<String, Object?>))
            .toList(),
        ungroupedDatabases: (json['ungroupedDatabases'] as List? ?? const [])
            .map((e) => DatabaseEntry.fromJson(e as Map<String, Object?>))
            .toList(),
      );
}
