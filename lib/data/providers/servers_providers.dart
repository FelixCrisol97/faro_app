import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants/db_engine.dart';
import '../datasources/database_connection_config.dart';
import '../datasources/db_connector.dart';
import '../models/database_entry.dart';
import '../models/server.dart';
import 'core_providers.dart';

/// The Administración-configured servers + their databases. Used by
/// Consulta (sidebar picker) and Administración (CRUD) alike — infra state,
/// not screen-local, so it lives in `data/providers` rather than under a
/// single feature.
class ServersNotifier extends Notifier<List<Server>> {
  @override
  List<Server> build() => ref.watch(serversRepositoryProvider).load();

  void _persist() => ref.read(serversRepositoryProvider).saveAll(state);

  void addServer(Server server) {
    state = [...state, server];
    _persist();
  }

  void removeServer(String serverId) {
    state = state.where((s) => s.id != serverId).toList();
    _persist();
  }

  /// Wholesale replace, used by Administración's "Importar configuración" —
  /// the imported JSON becomes the entire server list.
  void replaceAll(List<Server> servers) {
    state = servers;
    _persist();
  }

  void _updateServer(String serverId, Server Function(Server) update) {
    state = [
      for (final s in state)
        if (s.id == serverId) update(s) else s
    ];
    _persist();
  }

  void renameServer(String serverId, String name) {
    _updateServer(serverId, (s) => s.copyWith(name: name));
  }

  void setDatabaseMode(String serverId, String databaseId, ServerMode mode) {
    _updateDatabase(serverId, databaseId, (db) => db.copyWith(mode: mode));
  }

  void addDatabase(String serverId, DatabaseEntry database) {
    _updateServer(
        serverId, (s) => s.copyWith(databases: [...s.databases, database]));
  }

  /// Same as [addDatabase] but for several at once (Administración's
  /// "Descubrir bases de datos") — one state update and one `_persist()`
  /// for the whole batch instead of one per database.
  void addDatabases(String serverId, List<DatabaseEntry> databases) {
    if (databases.isEmpty) return;
    _updateServer(serverId,
        (s) => s.copyWith(databases: [...s.databases, ...databases]));
  }

  void removeDatabase(String serverId, String databaseId) {
    _updateServer(
        serverId,
        (s) => s.copyWith(
            databases:
                s.databases.where((db) => db.id != databaseId).toList()));
  }

  void renameDatabase(String serverId, String databaseId, String name) {
    _updateDatabase(serverId, databaseId, (db) => db.copyWith(name: name));
  }

  void setDatabaseHost(String serverId, String databaseId, String host) {
    _updateDatabase(serverId, databaseId, (db) => db.copyWith(host: host));
  }

  void setDatabaseName(
      String serverId, String databaseId, String databaseName) {
    _updateDatabase(
        serverId, databaseId, (db) => db.copyWith(databaseName: databaseName));
  }

  void toggleDatabaseSelected(
      String serverId, String databaseId, bool selected) {
    _updateDatabase(
        serverId, databaseId, (db) => db.copyWith(selected: selected));
  }

  /// "Todas / Ninguna" toggle in the sidebar — only shown/used in "Consulta
  /// masiva" mode (see [massQueryModeProvider]), since it's meaningless
  /// under exclusive single-database selection.
  void setAllDatabasesSelected(String serverId, bool selected) {
    _updateServer(
      serverId,
      (s) => s.copyWith(databases: [
        for (final db in s.databases) db.copyWith(selected: selected)
      ]),
    );
  }

  /// Exclusive selection for "Consulta masiva" **off** (the default) —
  /// clears every database's `selected` flag across *every* server first,
  /// then selects only this one. Unlike [toggleDatabaseSelected] (which
  /// only ever touches the one database it's given), this is what makes
  /// "only one database selected at a time, app-wide" actually hold.
  void selectOnlyDatabase(String serverId, String databaseId) {
    state = [
      for (final s in state)
        s.copyWith(databases: [
          for (final db in s.databases)
            db.copyWith(selected: s.id == serverId && db.id == databaseId),
        ]),
    ];
    _persist();
  }

  /// Called when the "Consulta masiva" switch turns off — the user
  /// explicitly asked for a clean slate here (not "keep the last one"), so
  /// every database's `selected` flag is cleared, across every server.
  void clearAllSelections() {
    state = [
      for (final s in state)
        s.copyWith(databases: [
          for (final db in s.databases) db.copyWith(selected: false),
        ]),
    ];
    _persist();
  }

  // Server order is just this list's own element order — no separate
  // "position" field on Server. Consulta's sidebar and Administración
  // both already render servers in whatever order this list is in (see
  // server_sidebar.dart/administracion_screen.dart's `for (final server in
  // servers)` loops), so reordering it here — and persisting, same as
  // every other mutator — is the whole mechanism. Nothing else needs to
  // change for a new order to show up in both places, and it survives
  // restarts for free via the same `_persist()` every mutator already
  // uses.

  /// "Mover al inicio" — used from both screens' context menus.
  void moveServerToStart(String serverId) {
    final index = state.indexWhere((s) => s.id == serverId);
    if (index <= 0) return;
    final servers = [...state];
    final server = servers.removeAt(index);
    servers.insert(0, server);
    state = servers;
    _persist();
  }

  void moveServerUp(String serverId) => _moveServerBy(serverId, -1);

  void moveServerDown(String serverId) => _moveServerBy(serverId, 1);

  void _moveServerBy(String serverId, int offset) {
    final index = state.indexWhere((s) => s.id == serverId);
    final target = index + offset;
    if (index == -1 || target < 0 || target >= state.length) return;
    final servers = [...state];
    final server = servers.removeAt(index);
    servers.insert(target, server);
    state = servers;
    _persist();
  }

  void setConnectionTestStatus(
      String serverId, String databaseId, ConnectionTestStatus status,
      {String? errorMessage}) {
    // Transient UI state (resets to idle on load, excluded from
    // DatabaseEntry.toJson) — skip the disk write. errorMessage is only
    // meaningful for ConnectionTestStatus.failed, but always passed through
    // (clearing it back to null on any other status) so a stale message
    // from a previous failure can't linger past a later successful test.
    _updateDatabase(
        serverId,
        databaseId,
        (db) => db.copyWith(
            testStatus: status, testError: () => errorMessage),
        persist: false);
  }

  void _updateDatabase(
    String serverId,
    String databaseId,
    DatabaseEntry Function(DatabaseEntry) update, {
    bool persist = true,
  }) {
    state = [
      for (final s in state)
        if (s.id == serverId)
          s.copyWith(databases: [
            for (final db in s.databases)
              if (db.id == databaseId) update(db) else db
          ])
        else
          s,
    ];
    if (persist) _persist();
  }
}

final serversProvider =
    NotifierProvider<ServersNotifier, List<Server>>(ServersNotifier.new);

/// Which sidebar tree row is in focus — purely cosmetic (drives the tinted
/// "active" row highlight and which server's header is expanded by
/// default). Used to scope query execution to "the active server" until
/// 2026-07-18, when that was replaced by [selectedQueryTargetsProvider] (a
/// servidor is only ever a grouping, never an execution boundary — see
/// [Server]'s doc comment) — nothing about what gets queried reads this
/// anymore.
final selectedServerIdProvider = StateProvider<String?>((ref) => null);

final selectedServerProvider = Provider<Server?>((ref) {
  final servers = ref.watch(serversProvider);
  final selectedId = ref.watch(selectedServerIdProvider);
  if (servers.isEmpty) return null;
  return servers.where((s) => s.id == selectedId).firstOrNull ?? servers.first;
});

/// One database, paired with the server it belongs to — everything a query
/// run needs to resolve a connection for it (`DatabaseConnectionConfig`
/// needs both `server.engine`/credentials-by-serverId and the database
/// itself).
typedef QueryTarget = ({Server server, DatabaseEntry database});

/// Resolves everything needed to open a connection to one database —
/// find the server, find the database within it, resolve its connector,
/// resolve its credentials, build the config. Real duplication fixed
/// 2026-08-03 (AUDITORIA_CODIGO.md): `schemaTypeExplorerProvider`/
/// `schemaSearchProvider` (`schema_explorer_provider.dart`),
/// `tableColumnsProvider` (`table_columns_provider.dart`), and
/// `objectDefinitionProvider` (`object_definition_provider.dart`) each
/// repeated this exact ~10-line prologue verbatim before doing their own
/// actual query. Returns `null` at the first missing piece (unknown
/// server/database, or a database with no host configured yet) — matches
/// what every one of those call sites already did on their own, just
/// spelled out once instead of four times.
Future<({DbConnector connector, DatabaseConnectionConfig config})?>
    resolveConnectorAndConfig(
  Ref ref, {
  required String serverId,
  required String databaseId,
}) async {
  final servers = ref.read(serversProvider);
  final server = servers.where((s) => s.id == serverId).firstOrNull;
  if (server == null) return null;
  final database =
      server.databases.where((db) => db.id == databaseId).firstOrNull;
  if (database == null || database.host.isEmpty) return null;

  final connector = ref.read(dbConnectorsProvider)[server.engine];
  if (connector == null) return null;

  final credentials = await ref
      .read(credentialsRepositoryProvider)
      .resolve(server.id, database.id);
  final config = DatabaseConnectionConfig.forDatabase(
      server: server, database: database, credentials: credentials);
  return (connector: connector, config: config);
}

/// Every database, across *every* server, currently marked selected — the
/// actual scope of "what gets queried" on Ejecutar. Not scoped to
/// [selectedServerProvider]'s one active server: a servidor is only ever a
/// free-form grouping (see [Server]'s doc comment), so there's no reason a
/// run couldn't span several of them at once (e.g. bodegas the user split
/// across servers that still share one schema). Shared by
/// `consulta_providers.dart`'s `run()` and `toolbar_card.dart`'s header so
/// both derive "what's selected" from one place instead of duplicating the
/// gather logic.
final selectedQueryTargetsProvider = Provider<List<QueryTarget>>((ref) {
  final servers = ref.watch(serversProvider);
  return [
    for (final server in servers)
      for (final db in server.databases)
        if (db.selected) (server: server, database: db),
  ];
});

/// "Consulta masiva" — off (the default) restricts selection to exactly one
/// database app-wide (`ServersNotifier.selectOnlyDatabase`), clicking it
/// also auto-expands and auto-loads its schema (`server_sidebar.dart`); on
/// restores today's independent multi-database, cross-server selection
/// (`toggleDatabaseSelected`) with manual "Cargar estructura". Session-only,
/// resets to off on restart — deliberately not persisted, same as the
/// sidebar's expansion state.
final massQueryModeProvider = StateProvider<bool>((ref) => false);
