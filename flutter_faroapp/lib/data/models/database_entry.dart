import '../../core/constants/db_engine.dart';

/// Status of a "Probar conexión" check in Administración (transient UI
/// state — not persisted).
enum ConnectionTestStatus { idle, testing, connected, failed }

/// One database — optionally grouped under a `Server`, but never required
/// to be (see [Server]'s doc comment).
///
/// [name] is a free-form alias only Faro shows (list, results pills,
/// `origen_bd`) — kept separate from [databaseName] (the real name the
/// engine knows it by) because it's routine for every bodega to use the
/// identical real database name (e.g. all called "bodega") while only
/// differing by [host]; without a distinct alias there'd be no way to tell
/// them apart in merged results.
///
/// [host] lives here, per database, rather than on the parent `Server` —
/// PROYECTO_DEFINICION.md describes a servidor as sometimes representing
/// "una cadena de sucursales ... con diferente IP", so a shared
/// server-level default would be wrong for exactly the case (distributed
/// bodegas) this app exists to serve. When a servidor represents one
/// central host with several databases, every entry in it just repeats the
/// same host:puerto string.
///
/// [engine] lives here too, not on `Server` — **moved from `Server` to
/// here 2026-08-13**, user-requested, same reasoning already applied to
/// [mode] on 2026-07-18 ("el servidor o grupo solo es para agrupar"): a
/// servidor being only a free-form grouping means it shouldn't force
/// every database inside it to share an engine either. A database is
/// fully creatable/connectable with no `Server` at all — grouping into
/// one, and which databases end up in the same group, is entirely the
/// user's later, optional choice (drag-and-drop — see
/// `ServersNotifier.createServerFromTwoUngroupedDatabases`), never a
/// prerequisite.
class DatabaseEntry {
  const DatabaseEntry({
    required this.id,
    required this.name,
    required this.host,
    required this.databaseName,
    required this.engine,
    this.mode = ServerMode.readOnly,
    this.selected = false,
    this.testStatus = ConnectionTestStatus.idle,
    this.testError,
  });

  final String id;

  /// Display alias — shown in Administración, results pills, and
  /// `origen_bd`. Free-form, does not need to match [databaseName].
  final String name;

  /// `host:puerto` (port optional, falls back to the engine's default).
  final String host;

  /// The real database name passed to the engine when connecting — unlike
  /// [name], this typically repeats across bodegas that share a schema.
  final String databaseName;

  /// PostgreSQL / SQL Server — decides which driver connects to this
  /// database. Set once when the database is created and editable
  /// afterward (see `edit_database_dialog.dart`).
  final DbEngine engine;

  /// Read protection, per database — NOT per servidor. A "servidor" is only
  /// a free-form grouping (see [Server]'s doc comment), so two databases in
  /// the same group can have different modes; this used to live on `Server`
  /// itself, which forced every database in a group to share one mode even
  /// though grouping was never meant to imply that.
  final ServerMode mode;
  final bool selected;
  final ConnectionTestStatus testStatus;

  /// The real exception message from the last failed "Probar conexión"
  /// (only meaningful when [testStatus] is [ConnectionTestStatus.failed]) —
  /// transient UI state, same as [testStatus] itself. Previously this never
  /// reached the UI at all (only `debugPrint`), so a failed test just showed
  /// a bare "Error" tag with no way to tell why — mirrors the tooltip +
  /// copy-to-clipboard treatment `results_card.dart` already gives failed
  /// per-database query outcomes.
  final String? testError;

  DatabaseEntry copyWith({
    String? id,
    String? name,
    String? host,
    String? databaseName,
    DbEngine? engine,
    ServerMode? mode,
    bool? selected,
    ConnectionTestStatus? testStatus,
    String? Function()? testError,
  }) {
    return DatabaseEntry(
      id: id ?? this.id,
      name: name ?? this.name,
      host: host ?? this.host,
      databaseName: databaseName ?? this.databaseName,
      engine: engine ?? this.engine,
      mode: mode ?? this.mode,
      selected: selected ?? this.selected,
      testStatus: testStatus ?? this.testStatus,
      testError: testError != null ? testError() : this.testError,
    );
  }

  /// [testStatus] is transient UI state and intentionally excluded — it
  /// always starts back at [ConnectionTestStatus.idle] on load. The JSON key
  /// is `alias` (not `name`) so a hand-written config file doesn't have to
  /// guess which of `alias`/`databaseName` is the free-form one — the Dart
  /// field itself stays `name` since that's used throughout the codebase.
  Map<String, Object?> toJson() => {
        'id': id,
        'alias': name,
        'host': host,
        'databaseName': databaseName,
        'engine': engine.name,
        'mode': mode.name,
      };

  /// `host` defaults to `''` for entries persisted before that field
  /// existed. `databaseName` defaults to the alias for entries persisted
  /// before the alias/real-name split existed — preserves the old
  /// (name-doubles-as-real-name) behavior instead of crashing or silently
  /// breaking existing connections. Also accepts the old `name` JSON key
  /// (pre-rename exports) as a fallback for `alias`. `mode` defaults to
  /// [ServerMode.readOnly] (the safe default) for entries persisted before
  /// mode moved here from `Server`.
  ///
  /// [fallbackEngine] — **migration, 2026-08-13**: files persisted before
  /// engine moved here had it on the parent `Server` instead, once per
  /// group, not once per database. `Server.fromJson` passes its own
  /// (now-removed-from-the-class, but still present in an old file's raw
  /// JSON) `engine` value through here for any database entry that has
  /// none of its own yet, so an old config doesn't silently revert every
  /// database in it to some arbitrary default engine on first load after
  /// upgrading. A database with genuinely no engine anywhere in the file
  /// (only possible for a hand-edited or very old export) falls back to
  /// PostgreSQL — a guess, correctable via `edit_database_dialog.dart`,
  /// same "best-effort default" precedent already used when merging two
  /// "Sin grupo" databases creates a brand-new group.
  factory DatabaseEntry.fromJson(Map<String, Object?> json,
      {DbEngine? fallbackEngine}) {
    final alias = json['alias'] as String? ?? json['name'] as String;
    return DatabaseEntry(
      id: json['id'] as String,
      name: alias,
      host: json['host'] as String? ?? '',
      databaseName: json['databaseName'] as String? ?? alias,
      engine: json['engine'] != null
          ? DbEngine.values.byName(json['engine'] as String)
          : (fallbackEngine ?? DbEngine.postgres),
      mode: json['mode'] != null
          ? ServerMode.values.byName(json['mode'] as String)
          : ServerMode.readOnly,
    );
  }
}
