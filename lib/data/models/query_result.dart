/// Per-database outcome of a multi-bodega run — the pill row in the
/// results card (README.md "Main — results card").
class DatabaseQueryOutcome {
  const DatabaseQueryOutcome({
    required this.databaseId,
    required this.databaseName,
    required this.serverName,
    required this.databaseHost,
    required this.success,
    this.rowCount,
    this.affectedRows = 0,
    this.errorMessage,
    this.blocked = false,
  });

  final String databaseId;
  final String databaseName;

  /// The parent servidor's name — needed once a run can span multiple
  /// servers (`QueryExecutionService`'s `origen_bd` disambiguation uses it
  /// to tell same-named databases from different servers apart).
  final String serverName;

  /// [DatabaseEntry.host] as configured (may include a port, e.g.
  /// `"192.168.1.10:5432"`) — user-requested 2026-08-05: when a "Consulta
  /// masiva" run merges several databases' rows together under one
  /// `origen_bd` name, there was no way to tell *which* IP that name
  /// actually pointed to without going back to Administración. Also used
  /// to embed the database's real host in the default CSV export filename
  /// (`results_pane.dart`), so an exported file is identifiable without
  /// reopening Faro.
  final String databaseHost;
  final bool success;

  /// Present when [success] is true. Rows returned by the result set — 0
  /// for a successful INSERT/UPDATE/DELETE without `RETURNING`, since those
  /// have no result set at all (see [affectedRows] for what actually
  /// happened in that case).
  final int? rowCount;

  /// Rows changed by an INSERT/UPDATE/DELETE without `RETURNING`. Only
  /// meaningful when [rowCount] is 0 — a real SELECT's row count is never
  /// worth second-guessing against this.
  final int affectedRows;

  /// Present when [success] is false.
  final String? errorMessage;

  /// True when [success] is false specifically because this database's
  /// [DatabaseEntry.mode] is Solo lectura and the statement wasn't a SELECT
  /// (`ReadOnlyViolationException`), as opposed to a real connection/SQL
  /// error — lets the UI show a distinct "Solo lectura" pill instead of a
  /// generic error one.
  final bool blocked;
}

/// The combined outcome of running one query against N selected databases.
class QueryResult {
  const QueryResult({
    required this.columns,
    required this.rows,
    required this.perDatabase,
    required this.cancelled,
  });

  /// When more than one database was queried, `origen_bd` is prepended by
  /// the caller before this is built — see README.md "Main — results card".
  final List<String> columns;

  /// Positional, same order as [columns] — see [RawQueryResult.rows]'s doc
  /// comment for why this isn't a `Map<String,Object?>` per row.
  final List<List<Object?>> rows;
  final List<DatabaseQueryOutcome> perDatabase;
  final bool cancelled;

  int get databasesQueried => perDatabase.length;
  bool get isMultiDatabase => perDatabase.length > 1;
}
