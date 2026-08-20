import 'package:faro/sql_analysis/ast/statements.dart';
import 'package:faro/sql_analysis/ast/table_ref.dart';
import 'package:faro/sql_analysis/ast/clauses.dart';
import 'package:faro/sql_analysis/lexer/sql_lexer.dart';
import 'package:faro/sql_analysis/parser/parse_result.dart';
import 'package:faro/sql_analysis/parser/sql_parser.dart';
import 'package:flutter_test/flutter_test.dart';

ParseResult _parse(String sql) => SqlParser.parse(lexSql(sql));

SelectStatement _parseSelect(String sql) {
  final result = _parse(sql);
  expect(result.diagnostics, isEmpty,
      reason: 'unexpected diagnostics: ${result.diagnostics}');
  return result.statement as SelectStatement;
}

void main() {
  group('SELECT — basics', () {
    test('plain select list and FROM', () {
      final stmt = _parseSelect('SELECT a, b FROM pedidos');
      expect(stmt.selectList.map((i) => i.expression!.sourceText), ['a', 'b']);
      expect(stmt.fromClause!.items, hasLength(1));
      final ref = stmt.fromClause!.items.single.ref as NamedTableRef;
      expect(ref.name, 'pedidos');
      expect(ref.alias, isNull);
    });

    test('SELECT * — isStar, no qualifier', () {
      final stmt = _parseSelect('SELECT * FROM t');
      expect(stmt.selectList.single.isStar, isTrue);
      expect(stmt.selectList.single.starQualifier, isNull);
    });

    test('alias.* — qualified star', () {
      final stmt = _parseSelect('SELECT p.* FROM pedidos p');
      expect(stmt.selectList.single.isStar, isTrue);
      expect(stmt.selectList.single.starQualifier, 'p');
    });

    test('explicit alias with AS', () {
      final stmt = _parseSelect('SELECT a AS x FROM t');
      expect(stmt.selectList.single.alias, 'x');
    });

    test('implicit alias without AS (unambiguous single-name case)', () {
      final stmt = _parseSelect('SELECT a x FROM t');
      expect(stmt.selectList.single.expression!.sourceText, 'a');
      expect(stmt.selectList.single.alias, 'x');
    });

    test('DISTINCT is recognized', () {
      final stmt = _parseSelect('SELECT DISTINCT a FROM t');
      expect(stmt.distinct, isTrue);
    });
  });

  group('SELECT — FROM/JOIN/alias', () {
    test('table alias with AS', () {
      final stmt = _parseSelect('SELECT * FROM pedidos AS p');
      final ref = stmt.fromClause!.items.single.ref as NamedTableRef;
      expect(ref.alias, 'p');
    });

    test('table alias without AS', () {
      final stmt = _parseSelect('SELECT * FROM pedidos p');
      final ref = stmt.fromClause!.items.single.ref as NamedTableRef;
      expect(ref.name, 'pedidos');
      expect(ref.alias, 'p');
    });

    test('schema-qualified table name', () {
      final stmt = _parseSelect('SELECT * FROM public.pedidos p');
      final ref = stmt.fromClause!.items.single.ref as NamedTableRef;
      expect(ref.schema, 'public');
      expect(ref.name, 'pedidos');
    });

    test('JOIN...ON resolves both sides and the condition', () {
      final stmt = _parseSelect(
          'SELECT * FROM pedidos p JOIN detalle d ON p.id = d.pedido_id');
      expect(stmt.fromClause!.items, hasLength(2));
      final first = stmt.fromClause!.items[0];
      expect(first.joinType, JoinType.none);
      expect((first.ref as NamedTableRef).alias, 'p');
      final second = stmt.fromClause!.items[1];
      expect(second.joinType, JoinType.inner);
      expect((second.ref as NamedTableRef).name, 'detalle');
      expect(second.onCondition, isNotNull);
      expect(second.onCondition!.sourceText, contains('pedido_id'));
    });

    test('comma join', () {
      final stmt = _parseSelect('SELECT * FROM a, b');
      expect(stmt.fromClause!.items.map((i) => i.joinType),
          [JoinType.none, JoinType.comma]);
    });

    test('LEFT JOIN and INNER JOIN chained', () {
      final stmt = _parseSelect(
          'SELECT * FROM a LEFT JOIN b ON a.id = b.a_id INNER JOIN c ON b.id = c.b_id');
      expect(stmt.fromClause!.items.map((i) => i.joinType),
          [JoinType.none, JoinType.left, JoinType.inner]);
    });

    test('CROSS JOIN and FULL OUTER JOIN are recognized', () {
      expect(_parse('SELECT * FROM a CROSS JOIN b').diagnostics, isEmpty);
      final stmt = _parseSelect('SELECT * FROM a FULL OUTER JOIN b ON a.id = b.id');
      expect(stmt.fromClause!.items[1].joinType, JoinType.full);
    });

    test('JOIN...USING', () {
      final stmt = _parseSelect('SELECT * FROM a JOIN b USING (id, tenant_id)');
      expect(stmt.fromClause!.items[1].usingColumns, ['id', 'tenant_id']);
    });
  });

  group('SELECT — subqueries in FROM', () {
    test('subquery with alias', () {
      final stmt = _parseSelect('SELECT * FROM (SELECT 1 AS x) AS sub');
      final ref = stmt.fromClause!.items.single.ref as SubqueryTableRef;
      expect(ref.alias, 'sub');
      expect(ref.subquery, isNotNull);
      expect(ref.subquery!.selectList.single.alias, 'x');
    });

    test('subquery without alias', () {
      final stmt = _parseSelect('SELECT * FROM (SELECT 1)');
      final ref = stmt.fromClause!.items.single.ref as SubqueryTableRef;
      expect(ref.alias, isNull);
      expect(ref.subquery, isNotNull);
    });

    test('nested subquery inside a CTE inside a subquery', () {
      final stmt = _parseSelect(
          'SELECT * FROM (WITH cte AS (SELECT 1 AS y) SELECT * FROM cte) AS outer_q');
      final ref = stmt.fromClause!.items.single.ref as SubqueryTableRef;
      final inner = ref.subquery!;
      expect(inner.withClause, isNotNull);
      expect(inner.withClause!.ctes.single.name, 'cte');
      expect(inner.withClause!.ctes.single.query!.selectList.single.alias, 'y');
    });
  });

  group('SELECT — CTEs', () {
    test('single CTE', () {
      final stmt = _parseSelect('WITH cte AS (SELECT 1) SELECT * FROM cte');
      expect(stmt.withClause, isNotNull);
      expect(stmt.withClause!.recursive, isFalse);
      expect(stmt.withClause!.ctes.single.name, 'cte');
      expect(stmt.withClause!.ctes.single.query, isNotNull);
    });

    test('multiple CTEs', () {
      final stmt = _parseSelect(
          'WITH a AS (SELECT 1), b AS (SELECT 2) SELECT * FROM a, b');
      expect(stmt.withClause!.ctes.map((c) => c.name), ['a', 'b']);
    });

    test('RECURSIVE flag', () {
      final stmt = _parseSelect(
          'WITH RECURSIVE cte AS (SELECT 1) SELECT * FROM cte');
      expect(stmt.withClause!.recursive, isTrue);
    });

    test('CTE with explicit column aliases', () {
      final stmt =
          _parseSelect('WITH cte(a, b) AS (SELECT 1, 2) SELECT * FROM cte');
      expect(stmt.withClause!.ctes.single.columnAliases, ['a', 'b']);
    });
  });

  group('SELECT — set operations', () {
    test('UNION chains the right side as its own SelectStatement', () {
      final stmt = _parseSelect('SELECT a FROM t1 UNION SELECT a FROM t2');
      expect(stmt.setOperation, isNotNull);
      expect(stmt.setOperation!.type, SetOperationType.union);
      expect((stmt.setOperation!.right.fromClause!.items.single.ref as NamedTableRef).name,
          't2');
    });

    test('UNION ALL is distinguished from UNION', () {
      final stmt = _parseSelect('SELECT a FROM t1 UNION ALL SELECT a FROM t2');
      expect(stmt.setOperation!.type, SetOperationType.unionAll);
    });

    test('INTERSECT/EXCEPT are recognized', () {
      expect(_parseSelect('SELECT a FROM t1 INTERSECT SELECT a FROM t2')
              .setOperation!
              .type,
          SetOperationType.intersect);
      expect(_parseSelect('SELECT a FROM t1 EXCEPT SELECT a FROM t2')
              .setOperation!
              .type,
          SetOperationType.except);
    });

    test(
        'a very long chain of UNION ALL parses without a StackOverflowError '
        '(real bug fixed 2026-08-04, AUDITORIA_CODIGO.md: this used to '
        'recurse once per chain element with no depth cap)', () {
      const chainLength = 20000;
      final sql = List.generate(chainLength, (i) => 'SELECT $i AS n FROM t')
          .join(' UNION ALL ');
      final stmt = _parseSelect(sql);

      // Walk the right-nested chain and count elements + confirm the last
      // one (with no further setOperation) really is the final SELECT.
      var count = 1;
      var current = stmt;
      while (current.setOperation != null) {
        expect(current.setOperation!.type, SetOperationType.unionAll);
        count++;
        current = current.setOperation!.right;
      }
      expect(count, chainLength);
      expect(
          (current.selectList.single.expression!.sourceText),
          '${chainLength - 1}');
    });
  });

  group('SELECT — strings/comments never misread as clause keywords', () {
    test('a FROM-looking word inside a string literal does not add a phantom table', () {
      final stmt = _parseSelect(
          "SELECT * FROM t WHERE nombre = 'proviene de un FROM antiguo'");
      expect(stmt.fromClause!.items, hasLength(1));
      expect((stmt.fromClause!.items.single.ref as NamedTableRef).name, 't');
      expect(stmt.whereClause!.sourceText, contains('FROM antiguo'));
    });

    test('a clause keyword inside a comment does not split the statement', () {
      final stmt = _parseSelect(
          '-- mentions WHERE and GROUP BY\nSELECT * FROM t');
      expect(stmt.fromClause!.items, hasLength(1));
      expect(stmt.whereClause, isNull);
    });
  });

  group('SELECT — WHERE/GROUP BY/HAVING/ORDER BY/LIMIT/OFFSET are recognized as clause boundaries', () {
    test('a full clause chain parses with no diagnostics', () {
      final stmt = _parseSelect(
          'SELECT a, COUNT(*) FROM t WHERE a > 1 GROUP BY a HAVING COUNT(*) > 1 '
          'ORDER BY a DESC LIMIT 10 OFFSET 5');
      expect(stmt.whereClause, isNotNull);
      expect(stmt.groupBy, hasLength(1));
      expect(stmt.having, isNotNull);
      expect(stmt.orderBy, hasLength(1));
      expect(stmt.orderBy.single.descending, isTrue);
      expect(stmt.limitClause, isNotNull);
      expect(stmt.offsetClause, isNotNull);
    });

    test('multiple ORDER BY items', () {
      final stmt = _parseSelect('SELECT * FROM t ORDER BY a ASC, b DESC');
      expect(stmt.orderBy, hasLength(2));
      expect(stmt.orderBy[0].descending, isFalse);
      expect(stmt.orderBy[1].descending, isTrue);
    });
  });
}
