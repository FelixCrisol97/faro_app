import '../../core/constants/db_engine.dart';
import '../datasources/cancellation_token.dart';
import '../datasources/database_connection_config.dart';
import '../datasources/db_connector.dart';
import '../models/database_credentials.dart';
import '../models/database_entry.dart';
import '../models/query_result.dart';
import '../models/server.dart';
import '../providers/servers_providers.dart' show QueryTarget;
import 'sql_guard.dart';

export '../datasources/cancellation_token.dart' show CancellationToken;

/// Resolves the login for one database, given its parent servidor's id
/// (`null` for a "Sin grupo" database) and its own id — backed by
/// `CredentialsRepository.resolve` (server default with an optional
/// per-database override), async because that's backed by the OS secure
/// credential store.
typedef CredentialsResolver = Future<DatabaseCredentials> Function(
    String? serverId, String databaseId);

class _DbTaskResult {
  const _DbTaskResult({required this.outcome, this.raw});
  final DatabaseQueryOutcome outcome;
  final RawQueryResult? raw;
}

/// Orchestrates one Consulta run: enforces read-only mode per-database
/// (`DatabaseEntry.mode` — a servidor is only a grouping, so this can't be
/// checked once up front for the whole run), fans the query out to every
/// selected database in parallel, and merges the results the way
/// README.md's "Main — results card" describes (per-database pill outcomes;
/// `origen_bd` prepended only when >1 database was queried).
class QueryExecutionService {
  QueryExecutionService({required Map<DbEngine, DbConnector> connectors})
      : _connectors = connectors;

  final Map<DbEngine, DbConnector> _connectors;

  /// [targets] can span multiple servers now (a servidor is only ever a
  /// grouping, never an execution boundary — see [Server]'s doc comment),
  /// so unlike before there's no single `server.engine` to resolve a
  /// connector from up front — each target resolves its own, inside
  /// [_runOne]'s fan-out below.
  ///
  /// [statements] — usually just one, but a selection spanning several SQL
  /// statements (`resolveStatementsToRun`) runs all of them, **in order,
  /// per database** (see [_runOne]) — stopping at the first one that
  /// fails on that database, rather than continuing and potentially
  /// leaving things half-done. Each database is still independent of the
  /// others, same as running one statement always was.
  /// [onProgress] (1-based `completed`, so "on statement `completed` of
  /// `total`") fires right before each statement starts — meaningful only
  /// when it's actually threaded through from a single-target run; nothing
  /// here assumes that, it's simply called once per statement per target.
  Future<QueryResult> run({
    required List<QueryTarget> targets,
    required List<String> statements,
    required CredentialsResolver resolveCredentials,
    CancellationToken? cancellationToken,
    void Function(int completed, int total)? onProgress,
  }) async {
    final results = await Future.wait(targets.map((target) {
      final connector = _connectors[target.database.engine];
      if (connector == null) {
        // Every DbEngine value is always registered in practice
        // (dbConnectorsProvider) — this is a defensive per-target failure
        // rather than a whole-run throw, since one target's missing
        // connector shouldn't abort every other target's query.
        return Future.value(_DbTaskResult(
          outcome: DatabaseQueryOutcome(
            databaseId: target.database.id,
            databaseName: target.database.name,
            serverName: target.server?.name ?? 'Sin grupo',
            databaseHost: target.database.host,
            success: false,
            errorMessage: 'No hay conector registrado para '
                '${target.database.engine.label}.',
          ),
        ));
      }
      return _runOne(connector, target.server, target.database, statements,
          resolveCredentials, cancellationToken, onProgress);
    }));

    if (cancellationToken?.isCancelled ?? false) {
      return const QueryResult(
          columns: [], rows: [], perDatabase: [], cancelled: true);
    }

    final successful = results.where((r) => r.raw != null).toList();
    // Only prefix origen_bd with the server name when results actually
    // span more than one server — keeps today's look (bare database name)
    // for the common single-server case, even when querying several of
    // its databases at once.
    final distinctServers =
        successful.map((r) => r.outcome.serverName).toSet();

    List<String> columns = const [];
    List<List<Object?>> rows = const [];
    var perDatabaseOutcomes = results.map((r) => r.outcome).toList();
    if (successful.isNotEmpty) {
      if (targets.length == 1) {
        columns = successful.first.raw!.columns;
        rows = successful.first.raw!.rows;
      } else {
        final referenceColumns = successful.first.raw!.columns;
        // User-requested 2026-08-05: `origen_bd` alone only shows a
        // database's alias — telling *which real IP* it points to meant
        // leaving Consulta and looking it up in Administración. `ip`
        // rides right next to `origen_bd` so a masiva result is
        // self-explanatory on its own.
        columns = ['origen_bd', 'ip', ...referenceColumns];
        final mergedRows = <List<Object?>>[];
        // Real bug fixed 2026-08-03 (AUDITORIA_CODIGO.md): every
        // successful database's rows used to be merged in unconditionally
        // under the *first* database's column list — a servidor can
        // legitimately span hosts with divergent schemas (see [Server]'s
        // doc comment), so a later database returning a different number
        // of columns produced rows shorter/longer than `columns`,
        // crashing the results grid downstream with a `RangeError`
        // (`VirtualizedTable` indexes every row by the reference column
        // count). Databases whose shape doesn't match are excluded from
        // the merge and reported as a mismatch instead of silently
        // corrupting the grid.
        final mismatchedColumnCounts = <String, int>{};
        for (final r in successful) {
          final raw = r.raw!;
          if (raw.columns.length != referenceColumns.length) {
            mismatchedColumnCounts[r.outcome.databaseId] = raw.columns.length;
            continue;
          }
          for (final row in raw.rows) {
            mergedRows.add([
              distinctServers.length > 1
                  ? '${r.outcome.serverName} · ${r.outcome.databaseName}'
                  : r.outcome.databaseName,
              r.outcome.databaseHost,
              ...row,
            ]);
          }
        }
        rows = mergedRows;
        if (mismatchedColumnCounts.isNotEmpty) {
          perDatabaseOutcomes = perDatabaseOutcomes.map((o) {
            final foundColumns = mismatchedColumnCounts[o.databaseId];
            if (foundColumns == null) return o;
            return DatabaseQueryOutcome(
              databaseId: o.databaseId,
              databaseName: o.databaseName,
              serverName: o.serverName,
              databaseHost: o.databaseHost,
              success: false,
              errorMessage: 'Esta base de datos devolvió $foundColumns '
                  'columna(s) de resultado, distintas a las '
                  '${referenceColumns.length} de las demás bases '
                  'seleccionadas — no se pudo combinar en la misma tabla.',
            );
          }).toList();
        }
      }
    }

    return QueryResult(
      columns: columns,
      rows: rows,
      perDatabase: perDatabaseOutcomes,
      cancelled: false,
    );
  }

  /// Runs [statements] against one database, in order, stopping at the
  /// first one that fails — the rest are simply never attempted for this
  /// database (other databases in the same run are unaffected, they each
  /// get their own [_runOne] call). The error message gets an
  /// "Instrucción N de M: " prefix only when there's more than one
  /// statement, so a single-statement run's message is byte-for-byte what
  /// it always was.
  Future<_DbTaskResult> _runOne(
    DbConnector connector,
    Server? server,
    DatabaseEntry db,
    List<String> statements,
    CredentialsResolver resolveCredentials,
    CancellationToken? cancellationToken,
    void Function(int completed, int total)? onProgress,
  ) async {
    final serverName = server?.name ?? 'Sin grupo';
    final credentials = await resolveCredentials(server?.id, db.id);
    final config =
        DatabaseConnectionConfig.forDatabase(database: db, credentials: credentials);

    RawQueryResult? lastRaw;
    for (var i = 0; i < statements.length; i++) {
      onProgress?.call(i + 1, statements.length);
      final sql = statements[i];
      String describe(String message) => statements.length > 1
          ? 'Instrucción ${i + 1} de ${statements.length}: $message'
          : message;

      try {
        if (db.mode == ServerMode.readOnly) {
          SqlGuard.assertReadOnly(sql);
        }
        lastRaw = await connector.runQuery(config, sql,
            cancellationToken: cancellationToken);
      } on ReadOnlyViolationException catch (e) {
        return _DbTaskResult(
          outcome: DatabaseQueryOutcome(
            databaseId: db.id,
            databaseName: db.name,
            serverName: serverName,
            databaseHost: db.host,
            success: false,
            blocked: true,
            errorMessage: describe(e.toString()),
          ),
        );
      } catch (e) {
        return _DbTaskResult(
          outcome: DatabaseQueryOutcome(
            databaseId: db.id,
            databaseName: db.name,
            serverName: serverName,
            databaseHost: db.host,
            success: false,
            errorMessage: describe(e.toString()),
          ),
        );
      }

      // Cancelled mid-sequence — don't attempt the remaining statements
      // for this database. The outer `run()` already turns this into a
      // whole-QueryResult `cancelled: true` once every target resolves;
      // this just stops one target's own loop from doing more work after
      // the cancellation was requested.
      if (cancellationToken?.isCancelled ?? false) break;
    }

    return _DbTaskResult(
      outcome: DatabaseQueryOutcome(
        databaseId: db.id,
        databaseName: db.name,
        serverName: serverName,
        databaseHost: db.host,
        success: true,
        rowCount: lastRaw?.rows.length ?? 0,
        affectedRows: lastRaw?.affectedRows ?? 0,
      ),
      raw: lastRaw,
    );
  }
}
