import 'package:faro/core/constants/db_engine.dart';
import 'package:faro/data/datasources/cancellation_token.dart';
import 'package:faro/data/datasources/database_connection_config.dart';
import 'package:faro/data/datasources/db_connector.dart';
import 'package:faro/data/models/database_credentials.dart';
import 'package:faro/data/models/database_entry.dart';
import 'package:faro/data/models/server.dart';
import 'package:faro/data/models/table_column.dart';
import 'package:faro/data/repositories/csv_import_service.dart';
import 'package:flutter_test/flutter_test.dart';

/// Records every call to [insertRows] and returns a configurable outcome —
/// [rowIndexToFail] lets a test simulate a specific row of *what was
/// actually sent* (post-coercion-filtering) failing, e.g. a duplicate key.
class _FakeConnector implements DbConnector {
  _FakeConnector({this.rowIndexToFail});
  final int? rowIndexToFail;

  String? lastSchema;
  String? lastTable;
  List<String>? lastColumns;
  List<Map<String, Object?>>? lastRows;

  @override
  Future<void> testConnection(DatabaseConnectionConfig config) async {}

  @override
  Future<RawQueryResult> runQuery(DatabaseConnectionConfig config, String sql,
          {CancellationToken? cancellationToken}) =>
      throw UnimplementedError('not exercised by this test');

  @override
  Future<BulkInsertOutcome> insertRows(
    DatabaseConnectionConfig config,
    String schema,
    String table,
    List<String> columns,
    List<Map<String, Object?>> rows, {
    CancellationToken? cancellationToken,
  }) async {
    lastSchema = schema;
    lastTable = table;
    lastColumns = columns;
    lastRows = rows;
    if (rowIndexToFail != null && rowIndexToFail! < rows.length) {
      return BulkInsertOutcome(inserted: rows.length - 1, failures: [
        RowInsertError(rowIndex: rowIndexToFail!, message: 'llave duplicada'),
      ]);
    }
    return BulkInsertOutcome(inserted: rows.length, failures: const []);
  }
}

const _server = Server(id: 's1', name: 'Servidor', engine: DbEngine.postgres);
const _database = DatabaseEntry(
    id: 'd1',
    name: 'Bodega',
    host: 'localhost:5432',
    databaseName: 'bodega',
    // Real gotcha (see project memory): DatabaseEntry.mode defaults to
    // readOnly — a fixture that omits it silently blocks every mutation
    // (import is always a mutation), masking whatever this test is
    // actually meant to exercise.
    mode: ServerMode.development);
const _readOnlyDatabase = DatabaseEntry(
    id: 'd2',
    name: 'Bodega RO',
    host: 'localhost:5432',
    databaseName: 'bodega',
    mode: ServerMode.readOnly);

final _columns = [
  const TableColumn(
      name: 'sku', dataType: 'text', nullable: false, isPrimaryKey: true),
  const TableColumn(
      name: 'cantidad', dataType: 'integer', nullable: false, isPrimaryKey: false),
  const TableColumn(
      name: 'notas', dataType: 'text', nullable: true, isPrimaryKey: false),
];

Future<List<TableColumn>> _fetchColumns(
        String serverId, String databaseId, String schema, String table) async =>
    _columns;

void main() {
  group('CsvImportService.importInto', () {
    test('rejects a database in Solo lectura mode without calling insertRows',
        () async {
      final connector = _FakeConnector();
      final service =
          CsvImportService(connectors: {DbEngine.postgres: connector});

      final outcome = await service.importInto(
        server: _server,
        database: _readOnlyDatabase,
        schema: 'public',
        table: 'productos',
        csvHeaders: const ['sku', 'cantidad'],
        csvRows: const [
          ['P-001', '10']
        ],
        credentials: emptyCredentials,
        fetchColumns: _fetchColumns,
      );

      expect(outcome.success, false);
      expect(outcome.blocked, true);
      expect(connector.lastRows, null);
    });

    test('rejects when a required column is missing from the CSV', () async {
      final connector = _FakeConnector();
      final service =
          CsvImportService(connectors: {DbEngine.postgres: connector});

      final outcome = await service.importInto(
        server: _server,
        database: _database,
        schema: 'public',
        table: 'productos',
        // 'cantidad' is required (not nullable, no default) and missing.
        csvHeaders: const ['sku', 'notas'],
        csvRows: const [
          ['P-001', 'algo']
        ],
        credentials: emptyCredentials,
        fetchColumns: _fetchColumns,
      );

      expect(outcome.success, false);
      expect(outcome.errorMessage, contains('cantidad'));
      expect(connector.lastRows, null);
    });

    test(
        'does not treat an identity column (NOT NULL, no visible default) '
        'as missing when absent from the CSV', () async {
      final connector = _FakeConnector();
      final service =
          CsvImportService(connectors: {DbEngine.postgres: connector});
      final columnsWithIdentity = [
        const TableColumn(
            name: 'id',
            dataType: 'integer',
            nullable: false,
            isPrimaryKey: true,
            isIdentity: true),
        ..._columns,
      ];

      final outcome = await service.importInto(
        server: _server,
        database: _database,
        schema: 'public',
        table: 'productos',
        // 'id' is NOT NULL with no default, but it's an identity column —
        // omitting it from the CSV (the normal case for an autoincrement
        // PK) must not block the import.
        csvHeaders: const ['sku', 'cantidad'],
        csvRows: const [
          ['P-001', '10']
        ],
        credentials: emptyCredentials,
        fetchColumns: (serverId, databaseId, schema, table) async =>
            columnsWithIdentity,
      );

      expect(outcome.success, true);
      expect(connector.lastRows, isNotNull);
    });

    test('rejects when no CSV column matches any real column', () async {
      final connector = _FakeConnector();
      final service =
          CsvImportService(connectors: {DbEngine.postgres: connector});

      final outcome = await service.importInto(
        server: _server,
        database: _database,
        schema: 'public',
        table: 'productos',
        csvHeaders: const ['columna_inexistente'],
        csvRows: const [
          ['x']
        ],
        credentials: emptyCredentials,
        fetchColumns: _fetchColumns,
      );

      expect(outcome.success, false);
      expect(connector.lastRows, null);
    });

    test(
        'rejects when two CSV headers (case-variant) map to the same real '
        'column, instead of silently letting one overwrite the other',
        () async {
      final connector = _FakeConnector();
      final service =
          CsvImportService(connectors: {DbEngine.postgres: connector});

      final outcome = await service.importInto(
        server: _server,
        database: _database,
        schema: 'public',
        table: 'productos',
        // 'sku' and 'SKU' both match the same real column.
        csvHeaders: const ['sku', 'SKU', 'cantidad'],
        csvRows: const [
          ['P-001', 'P-002', '10']
        ],
        credentials: emptyCredentials,
        fetchColumns: _fetchColumns,
      );

      expect(outcome.success, false);
      expect(outcome.errorMessage, contains('sku'));
      expect(connector.lastRows, null);
    });

    test('ignores an extra CSV column not present on the real table '
        '(e.g. origen_bd from a previous Faro export)', () async {
      final connector = _FakeConnector();
      final service =
          CsvImportService(connectors: {DbEngine.postgres: connector});

      final outcome = await service.importInto(
        server: _server,
        database: _database,
        schema: 'public',
        table: 'productos',
        csvHeaders: const ['origen_bd', 'sku', 'cantidad'],
        csvRows: const [
          ['Bodega Norte', 'P-001', '10']
        ],
        credentials: emptyCredentials,
        fetchColumns: _fetchColumns,
      );

      expect(outcome.success, true);
      expect(outcome.inserted, 1);
      expect(connector.lastColumns, ['sku', 'cantidad']);
      expect(connector.lastRows, [
        {'sku': 'P-001', 'cantidad': 10}
      ]);
    });

    test('coerces values by real column type before inserting', () async {
      final connector = _FakeConnector();
      final service =
          CsvImportService(connectors: {DbEngine.postgres: connector});

      final outcome = await service.importInto(
        server: _server,
        database: _database,
        schema: 'public',
        table: 'productos',
        csvHeaders: const ['sku', 'cantidad', 'notas'],
        csvRows: const [
          ['P-001', '10', '']
        ],
        credentials: emptyCredentials,
        fetchColumns: _fetchColumns,
      );

      expect(outcome.success, true);
      expect(outcome.inserted, 1);
      expect(outcome.failures, isEmpty);
      expect(connector.lastRows, [
        {'sku': 'P-001', 'cantidad': 10, 'notas': null}
      ]);
    });

    test(
        'a row that fails coercion is reported with its original CSV row '
        'index and never reaches insertRows', () async {
      final connector = _FakeConnector();
      final service =
          CsvImportService(connectors: {DbEngine.postgres: connector});

      final outcome = await service.importInto(
        server: _server,
        database: _database,
        schema: 'public',
        table: 'productos',
        csvHeaders: const ['sku', 'cantidad'],
        csvRows: const [
          ['P-001', 'no-es-numero'], // row 0: bad value
          ['P-002', '5'], // row 1: good
        ],
        credentials: emptyCredentials,
        fetchColumns: _fetchColumns,
      );

      expect(outcome.success, true);
      expect(outcome.inserted, 1);
      expect(outcome.failures, hasLength(1));
      expect(outcome.failures.single.rowIndex, 0);
      // Only the valid row was actually sent to the connector.
      expect(connector.lastRows, [
        {'sku': 'P-002', 'cantidad': 5}
      ]);
    });

    test(
        'remaps an insertRows-reported failure index back to the original '
        'CSV row index when an earlier row already failed coercion',
        () async {
      // Sent-to-connector list is [row1, row2] (row0 dropped by coercion);
      // the fake connector fails index 1 of *that* list (row2) — the
      // outcome must report it as original CSV row index 2, not 1.
      final connector = _FakeConnector(rowIndexToFail: 1);
      final service =
          CsvImportService(connectors: {DbEngine.postgres: connector});

      final outcome = await service.importInto(
        server: _server,
        database: _database,
        schema: 'public',
        table: 'productos',
        csvHeaders: const ['sku', 'cantidad'],
        csvRows: const [
          ['P-000', 'bad'], // row 0: fails coercion, dropped
          ['P-001', '10'], // row 1: sent as index 0
          ['P-002', '20'], // row 2: sent as index 1, fake connector fails it
        ],
        credentials: emptyCredentials,
        fetchColumns: _fetchColumns,
      );

      expect(outcome.inserted, 1);
      expect(outcome.failures, hasLength(2));
      final failedIndexes = outcome.failures.map((f) => f.rowIndex).toSet();
      expect(failedIndexes, {0, 2});
    });

    test('reports a clear error when the table has no columns (not found)',
        () async {
      final connector = _FakeConnector();
      final service =
          CsvImportService(connectors: {DbEngine.postgres: connector});

      final outcome = await service.importInto(
        server: _server,
        database: _database,
        schema: 'public',
        table: 'no_existe',
        csvHeaders: const ['sku'],
        csvRows: const [
          ['P-001']
        ],
        credentials: emptyCredentials,
        fetchColumns: (serverId, databaseId, schema, table) async => [],
      );

      expect(outcome.success, false);
      expect(connector.lastRows, null);
    });
  });
}
