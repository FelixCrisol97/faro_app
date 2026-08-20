import 'package:faro/core/constants/db_engine.dart';
import 'package:faro/data/models/database_entry.dart';
import 'package:faro/data/models/query_result.dart';
import 'package:faro/data/models/server.dart';
import 'package:faro/features/consulta/application/consulta_providers.dart';
import 'package:flutter_test/flutter_test.dart';

const _server = Server(id: 's1', name: 'Servidor');
const _database = DatabaseEntry(
    id: 'd1',
    name: 'Bodega',
    host: 'localhost:5432',
    databaseName: 'bodega',
    engine: DbEngine.postgres);
const _target = (server: _server, database: _database);

QueryResult _result(int rowCount) => QueryResult(
      columns: const ['col'],
      rows: List.generate(rowCount, (i) => [i]),
      perDatabase: const [
        DatabaseQueryOutcome(
          databaseId: 'd1',
          databaseName: 'Bodega',
          serverName: 'Servidor',
          databaseHost: '192.168.1.10:5432',
          success: true,
        ),
      ],
      cancelled: false,
    );

void main() {
  group('historyEntryFor — rowCount (real bug fixed 2026-08-03)', () {
    test('reports the full row count when no pagination cap applies', () {
      final entry =
          historyEntryFor([_target], const ['SELECT * FROM t'], _result(42));
      expect(entry.rowCount, 42);
    });

    test(
        'caps the reported row count at rowCountCap when the uncapped result '
        'exceeds it — the exact mismatch that used to show 5001 in Historial '
        'while the grid only ever showed 5000', () {
      final entry = historyEntryFor(
        [_target],
        const ['SELECT * FROM t'],
        _result(5001), // pageSize + 1 probe row, as wrapForPage always fetches
        rowCountCap: 5000,
      );
      expect(entry.rowCount, 5000);
    });

    test('does not cap when the result is already within the cap', () {
      final entry = historyEntryFor(
        [_target],
        const ['SELECT * FROM t'],
        _result(100),
        rowCountCap: 5000,
      );
      expect(entry.rowCount, 100);
    });

    test('a non-paginated multi-database run over the cap is unaffected '
        '(no rowCountCap passed at all)', () {
      final entry =
          historyEntryFor([_target], const ['SELECT * FROM t'], _result(9000));
      expect(entry.rowCount, 9000);
    });
  });
}
