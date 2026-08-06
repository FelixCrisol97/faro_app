import 'package:faro/sql_analysis/ast/statements.dart';
import 'package:faro/sql_analysis/lexer/sql_lexer.dart';
import 'package:faro/sql_analysis/parser/parse_result.dart';
import 'package:faro/sql_analysis/parser/sql_parser.dart';
import 'package:flutter_test/flutter_test.dart';

ParseResult _parse(String sql) => SqlParser.parse(lexSql(sql));

T _parseAs<T extends Statement>(String sql) {
  final result = _parse(sql);
  expect(result.diagnostics, isEmpty,
      reason: 'unexpected diagnostics: ${result.diagnostics}');
  return result.statement as T;
}

void main() {
  group('INSERT', () {
    test('INSERT INTO t (cols) VALUES (...)', () {
      final stmt = _parseAs<InsertStatement>(
          "INSERT INTO pedidos (cliente, total) VALUES ('acme', 100)");
      expect(stmt.target!.name, 'pedidos');
      expect(stmt.columns.map((c) => c.name), ['cliente', 'total']);
      expect(stmt.sourceQuery, isNull);
      expect(stmt.opaqueBodyStart, isNotNull);
    });

    test('INSERT INTO t SELECT ... — sourceQuery is a real SelectStatement', () {
      final stmt = _parseAs<InsertStatement>(
          'INSERT INTO pedidos (cliente) SELECT nombre FROM clientes');
      expect(stmt.sourceQuery, isNotNull);
      expect(stmt.sourceQuery!.selectList.single.expression!.sourceText, 'nombre');
    });

    test('INSERT INTO t without an explicit column list', () {
      final stmt =
          _parseAs<InsertStatement>("INSERT INTO pedidos VALUES ('acme', 100)");
      expect(stmt.columns, isEmpty);
      expect(stmt.opaqueBodyStart, isNotNull);
    });

    test('INSERT INTO t DEFAULT VALUES', () {
      final result = _parse('INSERT INTO pedidos DEFAULT VALUES');
      expect(result.diagnostics, isEmpty);
    });

    test('schema-qualified target', () {
      final stmt =
          _parseAs<InsertStatement>('INSERT INTO public.pedidos (a) VALUES (1)');
      expect(stmt.target!.schema, 'public');
      expect(stmt.target!.name, 'pedidos');
    });
  });

  group('UPDATE', () {
    test('UPDATE t SET ... WHERE ...', () {
      final stmt = _parseAs<UpdateStatement>(
          "UPDATE pedidos SET estado = 'enviado', total = 100 WHERE id = 1");
      expect(stmt.target!.name, 'pedidos');
      expect(stmt.setItems.map((s) => s.column), ['estado', 'total']);
      expect(stmt.whereClause, isNotNull);
      expect(stmt.whereClause!.sourceText, contains('id'));
    });

    test('UPDATE t AS alias SET ... FROM other WHERE ...', () {
      final stmt = _parseAs<UpdateStatement>(
          'UPDATE pedidos AS p SET total = o.total FROM otra AS o WHERE p.id = o.pedido_id');
      expect(stmt.target!.alias, 'p');
      expect(stmt.fromClause, isNotNull);
      expect(stmt.fromClause!.items.single.ref.alias, 'o');
    });

    test('UPDATE with no WHERE still parses (dangerous but syntactically valid)', () {
      final result = _parse('UPDATE pedidos SET estado = 1');
      expect(result.diagnostics, isEmpty);
    });

    test(
        'implicit alias without AS (real bug fixed 2026-08-04, '
        'AUDITORIA_CODIGO.md: SET used to be swallowed as the alias)', () {
      final stmt = _parseAs<UpdateStatement>(
          "UPDATE pedidos p SET estado = 'enviado' WHERE p.id = 1");
      expect(stmt.target!.alias, 'p');
      expect(stmt.setItems.single.column, 'estado');
      expect(stmt.whereClause, isNotNull);
      expect(stmt.whereClause!.sourceText, contains('p . id'));
    });
  });

  group('DELETE', () {
    test('DELETE FROM t WHERE ...', () {
      final stmt = _parseAs<DeleteStatement>('DELETE FROM pedidos WHERE id = 1');
      expect(stmt.target!.name, 'pedidos');
      expect(stmt.whereClause, isNotNull);
    });

    test('DELETE FROM t AS alias USING other WHERE ...', () {
      final stmt = _parseAs<DeleteStatement>(
          'DELETE FROM pedidos AS p USING otra AS o WHERE p.id = o.pedido_id');
      expect(stmt.target!.alias, 'p');
      expect(stmt.usingClause, isNotNull);
      expect(stmt.usingClause!.items.single.ref.alias, 'o');
    });

    test('DELETE FROM t with no WHERE still parses', () {
      final result = _parse('DELETE FROM pedidos');
      expect(result.diagnostics, isEmpty);
    });

    test(
        'implicit alias without AS (real bug fixed 2026-08-04, '
        'AUDITORIA_CODIGO.md: WHERE used to be swallowed as the alias, '
        'leaving no WHERE for the actual condition)', () {
      final stmt =
          _parseAs<DeleteStatement>('DELETE FROM pedidos p WHERE p.id = 1');
      expect(stmt.target!.alias, 'p');
      expect(stmt.whereClause, isNotNull);
      expect(stmt.whereClause!.sourceText, contains('p . id'));
    });
  });

  group('leading keyword classifies the statement', () {
    test('CREATE/ALTER/DROP/etc. are UnknownStatement, not an error', () {
      for (final sql in [
        'CREATE TABLE t (id int)',
        'ALTER TABLE t ADD COLUMN x int',
        'DROP TABLE t',
        'BEGIN',
        'EXPLAIN SELECT 1',
      ]) {
        final result = _parse(sql);
        expect(result.statement, isA<UnknownStatement>(), reason: sql);
        // Unrecognized-as-DML is not itself an error — v1 deliberately
        // doesn't parse DDL structurally, that's not a syntax problem.
        expect(result.diagnostics, isEmpty, reason: sql);
      }
    });
  });
}
