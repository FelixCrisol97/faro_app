import 'cancellation_token.dart';
import 'database_connection_config.dart';

/// The raw result of one query against one database, before it's merged
/// with other databases' results into a [QueryResult] (see
/// `data/repositories/query_execution_service.dart`).
class RawQueryResult {
  const RawQueryResult({
    required this.columns,
    required this.rows,
    this.affectedRows = 0,
  });

  final List<String> columns;

  /// Each row is positional, in the same order as [columns] — not a
  /// `Map<String,Object?>` per row, deliberately: for a 100k-row result set
  /// a hashmap per row was a real memory cost (see the 2026-08-01 RAM
  /// investigation) for no benefit, since every consumer already knows
  /// column order from [columns]. Callers that genuinely need name-keyed
  /// access to a handful of small (schema-introspection) rows should use
  /// [rowMaps] instead of hand-rolling `columns.indexOf(...)`.
  final List<List<Object?>> rows;

  /// Rows changed by an INSERT/UPDATE/DELETE without `RETURNING` — those
  /// statements come back with an empty [rows] (there's no result set),
  /// so this is the only place the real "how many did it change" count
  /// shows up.
  final int affectedRows;

  /// Reconstructs each row as a name-keyed map — only worth it for small
  /// result sets (schema introspection: table/column/object lists), never
  /// for the big user-facing query path, which is exactly why [rows] stays
  /// positional in the first place.
  List<Map<String, Object?>> get rowMaps => [
        for (final row in rows)
          {for (var i = 0; i < columns.length; i++) columns[i]: row[i]},
      ];
}

/// One row that failed during [DbConnector.insertRows] — [rowIndex] is
/// 0-based into the list of rows that was passed in, so the caller can map
/// it back to a specific CSV line for the user.
class RowInsertError {
  const RowInsertError({required this.rowIndex, required this.message});
  final int rowIndex;
  final String message;
}

/// The outcome of [DbConnector.insertRows] against one database — every row
/// is attempted independently (see that method's doc comment for why), so
/// [inserted] + [failures.length] always equals the number of rows given.
class BulkInsertOutcome {
  const BulkInsertOutcome({required this.inserted, required this.failures});
  final int inserted;
  final List<RowInsertError> failures;
}

/// Engine-specific connection + query execution. One implementation per
/// [DbEngine] — see `postgres_connector.dart` and `sqlserver_connector.dart`.
/// Kept stateless/config-in so the same instance can serve every database
/// of that engine across all servers.
abstract class DbConnector {
  /// Used by Administración's "Probar conexión". Throws on failure with a
  /// message suitable to show the user.
  Future<void> testConnection(DatabaseConnectionConfig config);

  /// Runs [sql] against the database described by [config]. Callers are
  /// responsible for read-only enforcement before calling this — see
  /// `data/repositories/sql_guard.dart` — this method executes whatever
  /// it's given.
  ///
  /// [cancellationToken], if cancelled while this is in flight, should
  /// interrupt the query rather than just being polled after the fact —
  /// see each implementation for how (closing the connection outright for
  /// `PostgresConnector`; killing a dedicated isolate for
  /// `SqlServerConnector`, since its underlying FFI calls can block the
  /// isolate they run on).
  Future<RawQueryResult> runQuery(DatabaseConnectionConfig config, String sql,
      {CancellationToken? cancellationToken});

  /// Inserts [rows] into `"$schema"."$table"` ([columns] gives both the
  /// column order and which keys each row map is expected to have) — backs
  /// `csv_import_service.dart`'s per-table CSV import
  /// (2026-07-24 feature). One connection is opened for every row in
  /// [rows] (not one per row), but **each row is executed as its own
  /// independent statement, deliberately not wrapped in one shared
  /// transaction** — a row that fails (e.g. a duplicate primary key; this
  /// app is insert-only, never upsert) must not stop the rest of [rows]
  /// from being attempted, and on Postgres specifically, one failed
  /// statement inside an explicit transaction poisons every statement
  /// after it until a ROLLBACK — auto-committing each row individually
  /// sidesteps that without needing SAVEPOINTs. Never throws for a
  /// per-row failure; those are collected into
  /// [BulkInsertOutcome.failures] instead. May still throw for a
  /// connection-level failure (bad credentials, unreachable host) — same
  /// as [runQuery].
  Future<BulkInsertOutcome> insertRows(
    DatabaseConnectionConfig config,
    String schema,
    String table,
    List<String> columns,
    List<Map<String, Object?>> rows, {
    CancellationToken? cancellationToken,
  });
}
