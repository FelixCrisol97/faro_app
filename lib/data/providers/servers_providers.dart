import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:uuid/uuid.dart';

import '../../core/constants/db_engine.dart';
import '../datasources/database_connection_config.dart';
import '../datasources/db_connector.dart';
import '../models/database_entry.dart';
import '../models/server.dart';
import '../models/servers_state.dart';
import 'core_providers.dart';

const _uuid = Uuid();

/// The Administración-configured servers + their databases (plus databases
/// not assigned to a server yet — "Sin grupo", see [ServersState]). Used by
/// Consulta (sidebar picker) and Administración (CRUD) alike — infra state,
/// not screen-local, so it lives in `data/providers` rather than under a
/// single feature.
class ServersNotifier extends Notifier<ServersState> {
  @override
  ServersState build() => ref.watch(serversRepositoryProvider).load();

  void _persist() => ref.read(serversRepositoryProvider).saveAll(state);

  void addServer(Server server) {
    state = state.copyWith(servers: [...state.servers, server]);
    _persist();
  }

  void removeServer(String serverId) {
    state = state.copyWith(
        servers: state.servers.where((s) => s.id != serverId).toList());
    _persist();
  }

  /// Wholesale replace, used by Administración's "Importar configuración" —
  /// the imported JSON becomes the entire server list. Deliberately never
  /// touches `ungroupedDatabases` — "Sin grupo" is a transient staging
  /// area, not configuration a hand-authored import file is expected to
  /// carry (see `ServerConfigCodec`'s doc comment).
  void replaceAll(List<Server> servers) {
    state = state.copyWith(servers: servers);
    _persist();
  }

  void _updateServer(String serverId, Server Function(Server) update) {
    state = state.copyWith(servers: [
      for (final s in state.servers)
        if (s.id == serverId) update(s) else s
    ]);
    _persist();
  }

  void renameServer(String serverId, String name) {
    _updateServer(serverId, (s) => s.copyWith(name: name));
  }

  /// Every single-database in-place mutator below takes a nullable
  /// [String? serverId] (2026-08-13, "Sin grupo" databases are fully
  /// queryable/editable on their own now — see [DatabaseEntry.engine]'s
  /// doc comment) so `database_check_row.dart` can be reused as-is for an
  /// ungrouped row (`serverId: null`) instead of needing a parallel
  /// "ungrouped" copy of every one of these — see [_updateAnyDatabase].
  void setDatabaseMode(String? serverId, String databaseId, ServerMode mode) {
    _updateAnyDatabase(serverId, databaseId, (db) => db.copyWith(mode: mode));
  }

  void setDatabaseEngine(String? serverId, String databaseId, DbEngine engine) {
    _updateAnyDatabase(
        serverId, databaseId, (db) => db.copyWith(engine: engine));
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

  void removeDatabase(String? serverId, String databaseId) {
    if (serverId == null) {
      state = state.copyWith(
          ungroupedDatabases: state.ungroupedDatabases
              .where((db) => db.id != databaseId)
              .toList());
      _persist();
      return;
    }
    _updateServer(
        serverId,
        (s) => s.copyWith(
            databases:
                s.databases.where((db) => db.id != databaseId).toList()));
  }

  void renameDatabase(String? serverId, String databaseId, String name) {
    _updateAnyDatabase(serverId, databaseId, (db) => db.copyWith(name: name));
  }

  void setDatabaseHost(String? serverId, String databaseId, String host) {
    _updateAnyDatabase(serverId, databaseId, (db) => db.copyWith(host: host));
  }

  void setDatabaseName(
      String? serverId, String databaseId, String databaseName) {
    _updateAnyDatabase(
        serverId, databaseId, (db) => db.copyWith(databaseName: databaseName));
  }

  void toggleDatabaseSelected(
      String? serverId, String databaseId, bool selected) {
    _updateAnyDatabase(
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
  /// clears every database's `selected` flag across *every* server (and
  /// "Sin grupo") first, then selects only this one. Unlike
  /// [toggleDatabaseSelected] (which only ever touches the one database
  /// it's given), this is what makes "only one database selected at a
  /// time, app-wide" actually hold. [serverId] `null` selects an
  /// ungrouped database instead of one inside a server.
  void selectOnlyDatabase(String? serverId, String databaseId) {
    state = ServersState(
      servers: [
        for (final s in state.servers)
          s.copyWith(databases: [
            for (final db in s.databases)
              db.copyWith(selected: s.id == serverId && db.id == databaseId),
          ]),
      ],
      ungroupedDatabases: [
        for (final db in state.ungroupedDatabases)
          db.copyWith(selected: serverId == null && db.id == databaseId),
      ],
    );
    _persist();
  }

  /// Called when the "Consulta masiva" switch turns off — the user
  /// explicitly asked for a clean slate here (not "keep the last one"), so
  /// every database's `selected` flag is cleared, across every server and
  /// "Sin grupo".
  void clearAllSelections() {
    state = ServersState(
      servers: [
        for (final s in state.servers)
          s.copyWith(databases: [
            for (final db in s.databases) db.copyWith(selected: false),
          ]),
      ],
      ungroupedDatabases: [
        for (final db in state.ungroupedDatabases) db.copyWith(selected: false),
      ],
    );
    _persist();
  }

  // Server order is just this list's own element order — no separate
  // "position" field on Server. The tree already renders servers in
  // whatever order this list is in (see `app_tree.dart`'s `for (final
  // server in servers)` loop), so reordering it here — and persisting,
  // same as every other mutator — is the whole mechanism. Nothing else needs to
  // change for a new order to show up in both places, and it survives
  // restarts for free via the same `_persist()` every mutator already
  // uses.

  /// "Mover al inicio" — used from both screens' context menus.
  void moveServerToStart(String serverId) {
    final index = state.servers.indexWhere((s) => s.id == serverId);
    if (index <= 0) return;
    final servers = [...state.servers];
    final server = servers.removeAt(index);
    servers.insert(0, server);
    state = state.copyWith(servers: servers);
    _persist();
  }

  void moveServerUp(String serverId) => _moveServerBy(serverId, -1);

  void moveServerDown(String serverId) => _moveServerBy(serverId, 1);

  void _moveServerBy(String serverId, int offset) {
    final index = state.servers.indexWhere((s) => s.id == serverId);
    final target = index + offset;
    if (index == -1 || target < 0 || target >= state.servers.length) return;
    final servers = [...state.servers];
    final server = servers.removeAt(index);
    servers.insert(target, server);
    state = state.copyWith(servers: servers);
    _persist();
  }

  /// Drag-and-drop reorder — same underlying mechanism as
  /// [moveServerToStart]/[_moveServerBy], generalized to any target index
  /// instead of only "start" or "±1", since a dropped row can land anywhere
  /// in the list. `targetIndex` is clamped, so a drop computed against a
  /// slightly stale list (e.g. another mutation raced it) can't throw.
  void reorderServer(String serverId, int targetIndex) {
    final servers = [...state.servers];
    final index = servers.indexWhere((s) => s.id == serverId);
    if (index == -1) return;
    final server = servers.removeAt(index);
    final clamped = targetIndex.clamp(0, servers.length);
    servers.insert(clamped, server);
    state = state.copyWith(servers: servers);
    _persist();
  }

  void setConnectionTestStatus(
      String? serverId, String databaseId, ConnectionTestStatus status,
      {String? errorMessage}) {
    // Transient UI state (resets to idle on load, excluded from
    // DatabaseEntry.toJson) — skip the disk write. errorMessage is only
    // meaningful for ConnectionTestStatus.failed, but always passed through
    // (clearing it back to null on any other status) so a stale message
    // from a previous failure can't linger past a later successful test.
    _updateAnyDatabase(
        serverId,
        databaseId,
        (db) => db.copyWith(
            testStatus: status, testError: () => errorMessage),
        persist: false);
  }

  /// [serverId] `null` updates a "Sin grupo" database instead of one
  /// inside a server — the single shared implementation behind every
  /// one-database setter above (mode, engine, rename, host, real name,
  /// selection, test status).
  void _updateAnyDatabase(
    String? serverId,
    String databaseId,
    DatabaseEntry Function(DatabaseEntry) update, {
    bool persist = true,
  }) {
    if (serverId == null) {
      state = state.copyWith(ungroupedDatabases: [
        for (final db in state.ungroupedDatabases)
          if (db.id == databaseId) update(db) else db
      ]);
    } else {
      state = state.copyWith(servers: [
        for (final s in state.servers)
          if (s.id == serverId)
            s.copyWith(databases: [
              for (final db in s.databases)
                if (db.id == databaseId) update(db) else db
            ])
          else
            s,
      ]);
    }
    if (persist) _persist();
  }

  // --- "Sin grupo" — databases not assigned to a server yet (2026-08-12).
  // Fully queryable/editable on their own (2026-08-13) via the nullable
  // [serverId] on every setter above and [removeDatabase] — only adding
  // one and reordering the list stay separate, since neither has a
  // meaningful "which server" branch to share with the grouped case.

  void addUngroupedDatabase(DatabaseEntry database) {
    state = state
        .copyWith(ungroupedDatabases: [...state.ungroupedDatabases, database]);
    _persist();
  }

  void reorderUngroupedDatabase(String databaseId, int targetIndex) {
    final databases = [...state.ungroupedDatabases];
    final index = databases.indexWhere((db) => db.id == databaseId);
    if (index == -1) return;
    final database = databases.removeAt(index);
    final clamped = targetIndex.clamp(0, databases.length);
    databases.insert(clamped, database);
    state = state.copyWith(ungroupedDatabases: databases);
    _persist();
  }

  // --- Cross-list moves — the drag-and-drop mechanics' core.

  void reorderDatabaseWithinServer(
      String serverId, String databaseId, int targetIndex) {
    _updateServer(serverId, (s) {
      final databases = [...s.databases];
      final index = databases.indexWhere((db) => db.id == databaseId);
      if (index == -1) return s;
      final database = databases.removeAt(index);
      final clamped = targetIndex.clamp(0, databases.length);
      databases.insert(clamped, database);
      return s.copyWith(databases: databases);
    });
  }

  /// Moves a database into [toServerId] — from another server
  /// ([fromServerId] non-null) or from "Sin grupo" ([fromServerId] null).
  /// A no-op (not two separate mutations) so a failure partway through
  /// can't ever leave the database in neither list nor both.
  void moveDatabaseToServer(
    String? fromServerId,
    String databaseId,
    String toServerId, {
    int? targetIndex,
  }) {
    if (fromServerId == toServerId) return;

    DatabaseEntry? moved;
    var servers = [...state.servers];
    var ungrouped = state.ungroupedDatabases;

    if (fromServerId == null) {
      final index = ungrouped.indexWhere((db) => db.id == databaseId);
      if (index == -1) return;
      moved = ungrouped[index];
      ungrouped = [...ungrouped]..removeAt(index);
    } else {
      final fromIndex = servers.indexWhere((s) => s.id == fromServerId);
      if (fromIndex == -1) return;
      final from = servers[fromIndex];
      final dbIndex = from.databases.indexWhere((db) => db.id == databaseId);
      if (dbIndex == -1) return;
      moved = from.databases[dbIndex];
      final remaining = [...from.databases]..removeAt(dbIndex);
      servers[fromIndex] = from.copyWith(databases: remaining);
    }

    final toIndex = servers.indexWhere((s) => s.id == toServerId);
    if (toIndex == -1) return;
    final to = servers[toIndex];
    final destination = [...to.databases];
    final clamped =
        (targetIndex ?? destination.length).clamp(0, destination.length);
    destination.insert(clamped, moved);
    servers[toIndex] = to.copyWith(databases: destination);

    state = ServersState(servers: servers, ungroupedDatabases: ungrouped);
    _persist();
  }

  /// Returns a database to "Sin grupo".
  void moveDatabaseToUngrouped(String fromServerId, String databaseId,
      {int? targetIndex}) {
    final servers = [...state.servers];
    final fromIndex = servers.indexWhere((s) => s.id == fromServerId);
    if (fromIndex == -1) return;
    final from = servers[fromIndex];
    final dbIndex = from.databases.indexWhere((db) => db.id == databaseId);
    if (dbIndex == -1) return;
    final moved = from.databases[dbIndex];
    final remaining = [...from.databases]..removeAt(dbIndex);
    servers[fromIndex] = from.copyWith(databases: remaining);

    final ungrouped = [...state.ungroupedDatabases];
    final clamped =
        (targetIndex ?? ungrouped.length).clamp(0, ungrouped.length);
    ungrouped.insert(clamped, moved);

    state = ServersState(servers: servers, ungroupedDatabases: ungrouped);
    _persist();
  }

  /// Dragging one ungrouped database onto another — creates a brand new
  /// `Server` containing both, removes both from "Sin grupo". Returns the
  /// new server's id (for [pendingRenameFocusIdProvider]/auto-expand), or
  /// `null` on a no-op (e.g. dragging a database onto itself, or either id
  /// no longer being in "Sin grupo" — a drop computed against a stale list).
  /// No `engine` param (removed 2026-08-13, along with `Server.engine`
  /// itself) — each dragged database already carries its own, nothing to
  /// guess/merge here.
  String? createServerFromTwoUngroupedDatabases(
    String draggedDatabaseId,
    String droppedOnDatabaseId, {
    String name = 'Nuevo servidor',
  }) {
    if (draggedDatabaseId == droppedOnDatabaseId) return null;
    final ungrouped = state.ungroupedDatabases;
    final dragged =
        ungrouped.where((db) => db.id == draggedDatabaseId).firstOrNull;
    final droppedOn =
        ungrouped.where((db) => db.id == droppedOnDatabaseId).firstOrNull;
    if (dragged == null || droppedOn == null) return null;

    final newServer = Server(
      id: _uuid.v4(),
      name: name,
      databases: [droppedOn, dragged],
    );
    state = ServersState(
      servers: [...state.servers, newServer],
      ungroupedDatabases: ungrouped
          .where((db) =>
              db.id != draggedDatabaseId && db.id != droppedOnDatabaseId)
          .toList(),
    );
    _persist();
    return newServer.id;
  }
}

final serversProvider =
    NotifierProvider<ServersNotifier, ServersState>(ServersNotifier.new);

/// Read-only view for the many call sites that never cared about "Sin
/// grupo" (query execution, schema explorer, CSV import, credentials
/// export, multi-window launch) — lets them keep working with a plain
/// `List<Server>` instead of unpacking [ServersState] themselves.
final serverListProvider =
    Provider<List<Server>>((ref) => ref.watch(serversProvider).servers);

/// Databases not assigned to any server yet — fully connectable/queryable
/// on their own (2026-08-13: each carries its own [DatabaseEntry.engine]),
/// just not organized into a group.
final ungroupedDatabasesProvider = Provider<List<DatabaseEntry>>(
    (ref) => ref.watch(serversProvider).ungroupedDatabases);

/// One-shot cue: the id of a server/database just created by a gesture
/// (currently only [ServersNotifier.createServerFromTwoUngroupedDatabases])
/// that should grab rename focus on its next build, then clear itself. Not
/// persisted — same session-only lifecycle as [massQueryModeProvider].
final pendingRenameFocusIdProvider = StateProvider<String?>((ref) => null);

/// Which sidebar tree row is in focus — purely cosmetic (drives the tinted
/// "active" row highlight and which server's header is expanded by
/// default). Used to scope query execution to "the active server" until
/// 2026-07-18, when that was replaced by [selectedQueryTargetsProvider] (a
/// servidor is only ever a grouping, never an execution boundary — see
/// [Server]'s doc comment) — nothing about what gets queried reads this
/// anymore.
final selectedServerIdProvider = StateProvider<String?>((ref) => null);

final selectedServerProvider = Provider<Server?>((ref) {
  final servers = ref.watch(serverListProvider);
  final selectedId = ref.watch(selectedServerIdProvider);
  if (servers.isEmpty) return null;
  return servers.where((s) => s.id == selectedId).firstOrNull ?? servers.first;
});

/// One database, paired with the server it belongs to — or `null` for a
/// "Sin grupo" database (2026-08-13: grouping became optional — see
/// [DatabaseEntry.engine]'s doc comment). `server` is only ever used for
/// its `id` (credentials-by-serverId, tab/window identity) and `name`
/// (display) now — connection resolution reads
/// [DatabaseEntry.engine] directly, no longer needs a `Server` at all.
typedef QueryTarget = ({Server? server, DatabaseEntry database});

/// Resolves everything needed to open a connection to one database —
/// find it (inside [serverId]'s server, or in "Sin grupo" when [serverId]
/// is `null`), resolve its connector, resolve its credentials, build the
/// config. Real duplication fixed 2026-08-03 (AUDITORIA_CODIGO.md):
/// `schemaTypeExplorerProvider`/`schemaSearchProvider`
/// (`schema_explorer_provider.dart`), `tableColumnsProvider`
/// (`table_columns_provider.dart`), and `objectDefinitionProvider`
/// (`object_definition_provider.dart`) each repeated this exact ~10-line
/// prologue verbatim before doing their own actual query. Returns `null`
/// at the first missing piece (unknown server/database, or a database
/// with no host configured yet) — matches what every one of those call
/// sites already did on their own, just spelled out once instead of four
/// times.
Future<({DbConnector connector, DatabaseConnectionConfig config})?>
    resolveConnectorAndConfig(
  Ref ref, {
  required String? serverId,
  required String databaseId,
}) async {
  final database = serverId == null
      ? ref
          .read(ungroupedDatabasesProvider)
          .where((db) => db.id == databaseId)
          .firstOrNull
      : ref
          .read(serverListProvider)
          .where((s) => s.id == serverId)
          .firstOrNull
          ?.databases
          .where((db) => db.id == databaseId)
          .firstOrNull;
  if (database == null || database.host.isEmpty) return null;

  final connector = ref.read(dbConnectorsProvider)[database.engine];
  if (connector == null) return null;

  final credentials = await ref
      .read(credentialsRepositoryProvider)
      .resolve(serverId, database.id);
  final config = DatabaseConnectionConfig.forDatabase(
      database: database, credentials: credentials);
  return (connector: connector, config: config);
}

/// Every database, across *every* server and "Sin grupo", currently marked
/// selected — the actual scope of "what gets queried" on Ejecutar. Not
/// scoped to [selectedServerProvider]'s one active server: a servidor is
/// only ever a free-form grouping (see [Server]'s doc comment), so there's
/// no reason a run couldn't span several of them at once (e.g. bodegas the
/// user split across servers that still share one schema). Shared by
/// `consulta_providers.dart`'s `run()` and `toolbar_card.dart`'s header so
/// both derive "what's selected" from one place instead of duplicating the
/// gather logic.
final selectedQueryTargetsProvider = Provider<List<QueryTarget>>((ref) {
  final servers = ref.watch(serverListProvider);
  final ungrouped = ref.watch(ungroupedDatabasesProvider);
  return [
    for (final server in servers)
      for (final db in server.databases)
        if (db.selected) (server: server, database: db),
    for (final db in ungrouped)
      if (db.selected) (server: null, database: db),
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
