import 'package:faro/core/constants/db_engine.dart';
import 'package:faro/data/datasources/database_connection_config.dart';
import 'package:faro/data/datasources/db_connector.dart';
import 'package:faro/data/models/database_credentials.dart';
import 'package:faro/data/models/database_entry.dart';
import 'package:faro/data/models/server.dart';
import 'package:faro/data/providers/servers_providers.dart';
import 'package:faro/data/repositories/query_execution_service.dart';
import 'package:flutter_test/flutter_test.dart';

/// Records every SQL string it's asked to run, in order; optionally throws
/// once execution reaches a configured statement text, simulating one
/// statement in a sequence failing.
class _FakeConnector implements DbConnector {
  _FakeConnector({this.failOn, List<String>? columns})
      : columns = columns ?? const ['n'];
  final String? failOn;
  final List<String> columns;
  final List<String> executed = [];

  @override
  Future<void> testConnection(DatabaseConnectionConfig config) async {}

  @override
  Future<RawQueryResult> runQuery(DatabaseConnectionConfig config, String sql,
      {CancellationToken? cancellationToken}) async {
    executed.add(sql);
    if (sql == failOn) {
      throw Exception('boom: $sql');
    }
    return RawQueryResult(columns: columns, rows: [
      [for (var i = 0; i < columns.length; i++) executed.length]
    ]);
  }

  @override
  Future<BulkInsertOutcome> insertRows(
          DatabaseConnectionConfig config,
          String schema,
          String table,
          List<String> columns,
          List<Map<String, Object?>> rows,
          {CancellationToken? cancellationToken}) =>
      throw UnimplementedError('not exercised by this test');
}

Future<DatabaseCredentials> _noCredentials(
        String? serverId, String databaseId) async =>
    emptyCredentials;

QueryTarget _targetFor(Server server) =>
    (server: server, database: server.databases.single);

void main() {
  group('QueryExecutionService.run — multiple statements', () {
    test('runs every statement in order against one database', () async {
      final connector = _FakeConnector();
      final service =
          QueryExecutionService(connectors: {DbEngine.postgres: connector});
      const server = Server(
        id: 's1',
        name: 'Server',
        databases: [
          DatabaseEntry(
              id: 'db1',
              name: 'db',
              host: 'localhost',
              databaseName: 'db',
              engine: DbEngine.postgres,
              mode: ServerMode.development)
        ],
      );

      final result = await service.run(
        targets: [_targetFor(server)],
        statements: const [
          'UPDATE a SET x = 1',
          'UPDATE b SET y = 2',
          'SELECT 1'
        ],
        resolveCredentials: _noCredentials,
      );

      expect(connector.executed,
          ['UPDATE a SET x = 1', 'UPDATE b SET y = 2', 'SELECT 1']);
      expect(result.perDatabase.single.success, isTrue);
    });

    test('onProgress fires once per statement, in order, before each one runs',
        () async {
      final connector = _FakeConnector();
      final service =
          QueryExecutionService(connectors: {DbEngine.postgres: connector});
      const server = Server(
        id: 's1',
        name: 'Server',
        databases: [
          DatabaseEntry(
              id: 'db1',
              name: 'db',
              host: 'localhost',
              databaseName: 'db',
              engine: DbEngine.postgres,
              mode: ServerMode.development)
        ],
      );
      final progress = <(int, int)>[];

      await service.run(
        targets: [_targetFor(server)],
        statements: const [
          'UPDATE a SET x = 1',
          'UPDATE b SET y = 2',
          'SELECT 1'
        ],
        resolveCredentials: _noCredentials,
        onProgress: (completed, total) => progress.add((completed, total)),
      );

      expect(progress, [(1, 3), (2, 3), (3, 3)]);
    });

    test('stops at the first statement that fails, never attempting the rest',
        () async {
      final connector = _FakeConnector(failOn: 'UPDATE b SET y = 2');
      final service =
          QueryExecutionService(connectors: {DbEngine.postgres: connector});
      const server = Server(
        id: 's1',
        name: 'Server',
        databases: [
          DatabaseEntry(
              id: 'db1',
              name: 'db',
              host: 'localhost',
              databaseName: 'db',
              engine: DbEngine.postgres,
              mode: ServerMode.development)
        ],
      );

      final result = await service.run(
        targets: [_targetFor(server)],
        statements: const [
          'UPDATE a SET x = 1',
          'UPDATE b SET y = 2',
          'SELECT 1'
        ],
        resolveCredentials: _noCredentials,
      );

      // Only the first two were attempted — the third never ran.
      expect(connector.executed, ['UPDATE a SET x = 1', 'UPDATE b SET y = 2']);
      final outcome = result.perDatabase.single;
      expect(outcome.success, isFalse);
      expect(outcome.errorMessage, contains('Instrucción 2 de 3'));
      expect(outcome.errorMessage, contains('boom: UPDATE b SET y = 2'));
    });

    test(
        'one database failing does not affect another database in the same run',
        () async {
      final failingConnector = _FakeConnector(failOn: 'UPDATE b SET y = 2');
      final okConnector = _FakeConnector();
      final service = QueryExecutionService(connectors: {
        DbEngine.postgres: failingConnector,
        DbEngine.sqlServer: okConnector,
      });
      const failingServer = Server(
        id: 's1',
        name: 'Postgres server',
        databases: [
          DatabaseEntry(
              id: 'db1',
              name: 'db1',
              host: 'localhost',
              databaseName: 'db1',
              engine: DbEngine.postgres,
              mode: ServerMode.development)
        ],
      );
      const okServer = Server(
        id: 's2',
        name: 'SQL Server',
        databases: [
          DatabaseEntry(
              id: 'db2',
              name: 'db2',
              host: 'localhost',
              databaseName: 'db2',
              engine: DbEngine.sqlServer,
              mode: ServerMode.development)
        ],
      );

      final result = await service.run(
        targets: [_targetFor(failingServer), _targetFor(okServer)],
        statements: const [
          'UPDATE a SET x = 1',
          'UPDATE b SET y = 2',
          'SELECT 1'
        ],
        resolveCredentials: _noCredentials,
      );

      expect(okConnector.executed,
          ['UPDATE a SET x = 1', 'UPDATE b SET y = 2', 'SELECT 1']);
      final outcomes = {for (final o in result.perDatabase) o.databaseId: o};
      expect(outcomes['db1']!.success, isFalse);
      expect(outcomes['db2']!.success, isTrue);
    });
  });

  group(
      'QueryExecutionService.run — multi-database column-shape mismatch '
      '(real bug fixed 2026-08-03)', () {
    test(
        'excludes a database whose result has a different column count '
        'from the merge instead of producing rows shorter/longer than '
        '`columns` (the actual RangeError downstream in the results grid)',
        () async {
      final threeColConnector = _FakeConnector(columns: const ['a', 'b', 'c']);
      final twoColConnector = _FakeConnector(columns: const ['a', 'b']);
      final service = QueryExecutionService(connectors: {
        DbEngine.postgres: threeColConnector,
        DbEngine.sqlServer: twoColConnector,
      });
      const serverA = Server(
        id: 's1',
        name: 'A',
        databases: [
          DatabaseEntry(
              id: 'db1',
              name: 'db1',
              host: '192.168.1.10:5432',
              databaseName: 'db1',
              engine: DbEngine.postgres,
              mode: ServerMode.development)
        ],
      );
      const serverB = Server(
        id: 's2',
        name: 'B',
        databases: [
          DatabaseEntry(
              id: 'db2',
              name: 'db2',
              host: 'localhost',
              databaseName: 'db2',
              engine: DbEngine.sqlServer,
              mode: ServerMode.development)
        ],
      );

      final result = await service.run(
        targets: [_targetFor(serverA), _targetFor(serverB)],
        statements: const ['SELECT 1'],
        resolveCredentials: _noCredentials,
      );

      // Merged shape follows the first successful database (db1, 3 cols).
      expect(result.columns, ['origen_bd', 'ip', 'a', 'b', 'c']);
      // The property that actually prevents the RangeError: every merged
      // row has exactly as many entries as `columns`.
      for (final row in result.rows) {
        expect(row.length, result.columns.length);
      }
      // User-requested 2026-08-05: the merged `ip` column carries db1's
      // real host, not just its alias — this is the whole point of adding
      // it (telling apart same-named databases on different servers/IPs
      // without leaving Consulta).
      expect(result.rows.single[1], '192.168.1.10:5432');

      final outcomes = {for (final o in result.perDatabase) o.databaseId: o};
      expect(outcomes['db1']!.success, isTrue);
      expect(outcomes['db2']!.success, isFalse);
      expect(outcomes['db2']!.errorMessage, contains('2'));
      expect(outcomes['db2']!.errorMessage, contains('3'));
    });
  });
}
