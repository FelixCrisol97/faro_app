import 'package:postgres/postgres.dart';

import 'cancellation_token.dart';
import 'database_connection_config.dart';
import 'db_connector.dart';

/// [DbConnector] implementation for PostgreSQL, backed by the `postgres`
/// pub package (v3 API — verify against the installed version once
/// `flutter pub get` has run, this was written without a working SDK to
/// compile against).
class PostgresConnector implements DbConnector {
  Endpoint _endpointOf(DatabaseConnectionConfig config) => Endpoint(
        host: config.host,
        port: config.port,
        database: config.databaseName,
        username: config.username,
        password: config.password,
      );

  @override
  Future<void> testConnection(DatabaseConnectionConfig config) async {
    final conn = await Connection.open(
      _endpointOf(config),
      settings: const ConnectionSettings(sslMode: SslMode.disable),
    );
    await conn.close();
  }

  @override
  Future<RawQueryResult> runQuery(
    DatabaseConnectionConfig config,
    String sql, {
    CancellationToken? cancellationToken,
  }) async {
    final conn = await Connection.open(
      _endpointOf(config),
      settings: const ConnectionSettings(sslMode: SslMode.disable),
    );
    // Real cancellation: closing the connection while `execute` is awaiting
    // a response interrupts it (the pending call throws), instead of just
    // discarding whatever eventually comes back. Each query already opens
    // its own connection, so this only ever affects this one query.
    cancellationToken?.onCancel(() => conn.close());
    try {
      final result = await conn.execute(sql);
      final columns =
          result.schema.columns.map((c) => c.columnName ?? '').toList();
      // Each ResultRow already IS a positional List<Object?> (it extends
      // UnmodifiableListView<Object?>) — this only rebuilds the outer list,
      // no per-row copy or Map, unlike the `.toColumnMap()` this replaced.
      final rows = List<List<Object?>>.from(result);
      return RawQueryResult(
          columns: columns, rows: rows, affectedRows: result.affectedRows);
    } finally {
      await conn.close();
    }
  }

  @override
  Future<BulkInsertOutcome> insertRows(
    DatabaseConnectionConfig config,
    String schema,
    String table,
    List<String> columns,
    List<Map<String, Object?>> rows, {
    CancellationToken? cancellationToken,
  }) async {
    final conn = await Connection.open(
      _endpointOf(config),
      settings: const ConnectionSettings(sslMode: SslMode.disable),
    );
    cancellationToken?.onCancel(() => conn.close());
    try {
      final columnList = columns.map((c) => '"$c"').join(', ');
      final placeholderList = columns.map((c) => '@$c').join(', ');
      final sql = Sql.named(
          'INSERT INTO "$schema"."$table" ($columnList) VALUES ($placeholderList)');

      var inserted = 0;
      final failures = <RowInsertError>[];
      for (var i = 0; i < rows.length; i++) {
        if (cancellationToken?.isCancelled ?? false) break;
        try {
          await conn.execute(sql, parameters: rows[i], ignoreRows: true);
          inserted++;
        } catch (e) {
          failures.add(RowInsertError(rowIndex: i, message: e.toString()));
        }
      }
      return BulkInsertOutcome(inserted: inserted, failures: failures);
    } finally {
      await conn.close();
    }
  }
}
