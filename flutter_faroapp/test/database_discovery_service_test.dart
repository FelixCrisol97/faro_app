import 'package:faro/core/constants/db_engine.dart';
import 'package:faro/data/datasources/cancellation_token.dart';
import 'package:faro/data/datasources/database_connection_config.dart';
import 'package:faro/data/datasources/db_connector.dart';
import 'package:faro/data/repositories/database_discovery_service.dart';
import 'package:flutter_test/flutter_test.dart';

class _FakeConnector implements DbConnector {
  _FakeConnector(this.result);
  final RawQueryResult result;
  DatabaseConnectionConfig? lastConfig;

  @override
  Future<void> testConnection(DatabaseConnectionConfig config) async {}

  @override
  Future<RawQueryResult> runQuery(DatabaseConnectionConfig config, String sql,
      {CancellationToken? cancellationToken}) async {
    lastConfig = config;
    return result;
  }

  @override
  Future<BulkInsertOutcome> insertRows(DatabaseConnectionConfig config,
          String schema, String table, List<String> columns,
          List<Map<String, Object?>> rows, {CancellationToken? cancellationToken}) =>
      throw UnimplementedError('not exercised by this test');
}

void main() {
  group('DatabaseDiscoveryService.discover', () {
    test('returns database names from the first result column', () async {
      final connector = _FakeConnector(const RawQueryResult(
        columns: ['datname'],
        rows: [
          ['bodega1'],
          ['bodega2'],
        ],
      ));
      final service =
          DatabaseDiscoveryService(connectors: {DbEngine.postgres: connector});

      final names = await service.discover(
        engine: DbEngine.postgres,
        host: '192.168.1.10',
        port: 5432,
        username: 'user',
        password: 'pass',
      );

      expect(names, ['bodega1', 'bodega2']);
    });

    test('connects to the engine maintenance database, not a real one',
        () async {
      final connector =
          _FakeConnector(const RawQueryResult(columns: ['name'], rows: []));
      final service = DatabaseDiscoveryService(
          connectors: {DbEngine.sqlServer: connector});

      await service.discover(
        engine: DbEngine.sqlServer,
        host: 'localhost',
        port: 1433,
        username: 'sa',
        password: 'pw',
      );

      expect(connector.lastConfig!.databaseName, 'master');
    });

    test(
        'connects through bootstrapDatabase instead of the maintenance database when given',
        () async {
      final connector =
          _FakeConnector(const RawQueryResult(columns: ['name'], rows: []));
      final service = DatabaseDiscoveryService(
          connectors: {DbEngine.sqlServer: connector});

      await service.discover(
        engine: DbEngine.sqlServer,
        host: 'localhost',
        port: 1433,
        username: 'sa',
        password: 'pw',
        bootstrapDatabase: 'bodega1',
      );

      expect(connector.lastConfig!.databaseName, 'bodega1');
    });

    test('returns an empty list when the query has no columns', () async {
      final connector =
          _FakeConnector(const RawQueryResult(columns: [], rows: []));
      final service =
          DatabaseDiscoveryService(connectors: {DbEngine.postgres: connector});

      final names = await service.discover(
        engine: DbEngine.postgres,
        host: 'localhost',
        port: 5432,
        username: 'u',
        password: 'p',
      );

      expect(names, isEmpty);
    });
  });
}
