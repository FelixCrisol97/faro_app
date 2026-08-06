import 'package:faro/sql_analysis/analysis/scope_resolver.dart';
import 'package:faro/sql_analysis/ast/statements.dart';
import 'package:faro/sql_analysis/lexer/sql_lexer.dart';
import 'package:faro/sql_analysis/parser/sql_parser.dart';
import 'package:flutter_test/flutter_test.dart';

Statement _statementOf(String sql) => SqlParser.parse(lexSql(sql)).statement;

/// Resolves scope at the offset of `marker` within [sql] — a small
/// convenience so tests can write `_scopeAt(sql, 'p.')` instead of manually
/// computing offsets.
ScopeInfo _scopeAt(String sql, String marker) {
  final offset = sql.indexOf(marker);
  expect(offset, greaterThanOrEqualTo(0), reason: 'marker not found: $marker');
  return resolveScopeAt(_statementOf(sql), offset);
}

void main() {
  group('resolveScopeAt — SELECT with JOIN and aliases', () {
    test('both aliases are visible with their real table names', () {
      const sql =
          'SELECT p. FROM pedidos p JOIN detalle d ON p.id = d.pedido_id';
      final scope = _scopeAt(sql, 'p. FROM');
      final byAlias = {for (final b in scope.visibleTables) b.alias: b};
      expect(byAlias.keys, containsAll(['p', 'd']));
      expect(byAlias['p']!.tableName, 'pedidos');
      expect(byAlias['d']!.tableName, 'detalle');
      expect(byAlias['p']!.effectiveName, 'p');
    });

    test('a table with no alias is referenced by its own name', () {
      const sql = 'SELECT x FROM pedidos';
      final scope = _scopeAt(sql, 'x');
      expect(scope.visibleTables.single.alias, isNull);
      expect(scope.visibleTables.single.effectiveName, 'pedidos');
      expect(scope.visibleTables.single.tableName, 'pedidos');
    });
  });

  group('resolveScopeAt — CTE visibility', () {
    test('the main query sees every CTE name declared above it', () {
      const sql =
          'WITH a AS (SELECT 1), b AS (SELECT 2) SELECT x FROM a JOIN b ON true';
      final scope = _scopeAt(sql, 'FROM a');
      final names = scope.visibleTables.map((b) => b.effectiveName).toSet();
      expect(names, containsAll(['a', 'b']));
      expect(scope.visibleTables.every((b) => b.isCte), isTrue);
    });

    test('an earlier CTE body does NOT see a later CTE (declared after it)', () {
      const sql = 'WITH a AS (SELECT x FROM MARKER), b AS (SELECT 2) '
          'SELECT * FROM a, b';
      final scope = _scopeAt(sql, 'MARKER');
      // Resolving *inside* CTE `a`'s own body: `b` isn't declared yet at
      // this lexical point, so it must not appear — a real table named
      // "MARKER" is the only binding here (a itself isn't self-visible
      // either, since this WITH isn't RECURSIVE).
      expect(scope.visibleTables.any((b) => b.effectiveName == 'b'), isFalse);
    });

    test('RECURSIVE lets a CTE see itself inside its own body', () {
      const sql = 'WITH RECURSIVE cte AS (SELECT * FROM cte) SELECT * FROM cte';
      final scope = _scopeAt(sql, 'FROM cte)');
      expect(scope.visibleTables.any((b) => b.effectiveName == 'cte' && b.isCte),
          isTrue);
    });

    test('a non-RECURSIVE CTE does not see itself inside its own body', () {
      const sql = 'WITH cte AS (SELECT * FROM cte) SELECT * FROM cte';
      final scope = _scopeAt(sql, 'FROM cte)');
      // Not treated as a self-referencing CTE binding — same as real
      // Postgres, a non-RECURSIVE `WITH cte AS (SELECT * FROM cte)` would
      // resolve the inner `cte` against an actual same-named table, not
      // the CTE being defined. A same-named *real table* binding is
      // exactly the correct fallback (isCte stays false), not an error.
      expect(scope.visibleTables.any((b) => b.effectiveName == 'cte' && b.isCte),
          isFalse);
    });

    test(
        'a CTE referenced with different casing still resolves as the CTE '
        '(real bug fixed 2026-08-04, AUDITORIA_CODIGO.md: this used to '
        'compare case-sensitively and fall through to "unknown table")',
        () {
      const sql = 'WITH Totales AS (SELECT 1) SELECT x FROM totales';
      final scope = _scopeAt(sql, 'x');
      final binding = scope.visibleTables.single;
      expect(binding.isCte, isTrue);
      expect(binding.tableName, isNull);
    });
  });

  group('resolveScopeAt — subqueries in FROM', () {
    test('the outer query does not see the inner FROM of a subquery', () {
      const sql = 'SELECT x FROM (SELECT * FROM detalle) AS sub';
      final scope = _scopeAt(sql, 'SELECT x');
      expect(scope.visibleTables, hasLength(1));
      expect(scope.visibleTables.single.alias, 'sub');
      expect(scope.visibleTables.single.isSubquery, isTrue);
    });

    test('inside the subquery, only its own FROM is visible (no correlation)', () {
      const sql =
          'SELECT x FROM pedidos p, (SELECT y FROM MARKER) AS sub';
      final scope = _scopeAt(sql, 'MARKER');
      expect(scope.visibleTables, hasLength(1));
      expect(scope.visibleTables.single.tableName, 'MARKER');
      // The outer `p` alias must not leak in.
      expect(scope.visibleTables.any((b) => b.alias == 'p'), isFalse);
    });

    test('CTEs remain visible inside a nested subquery (unlike table aliases)', () {
      // The subquery's own FROM references `cte` by name — declared in the
      // top-level WITH, several nesting levels up. Unlike the outer
      // query's table *aliases* (which don't leak in, per the test
      // above), CTEs propagate down through every nesting level because
      // they're visible to the whole WITH-statement, not lexically scoped
      // to one FROM the way an alias is.
      const sql = 'WITH cte AS (SELECT 1) '
          'SELECT x FROM (SELECT y FROM cte) AS sub';
      final scope = _scopeAt(sql, 'FROM cte');
      expect(scope.visibleTables.any((b) => b.effectiveName == 'cte' && b.isCte),
          isTrue);
    });
  });

  group('resolveScopeAt — known column names', () {
    test('a CTE with an explicit column list reports those columns', () {
      const sql = 'WITH cte(a, b) AS (SELECT 1, 2) SELECT x FROM cte';
      final scope = _scopeAt(sql, 'FROM cte');
      expect(scope.visibleTables.single.knownColumns, ['a', 'b']);
    });

    test('a subquery whose every item is a simple/aliased name reports those columns', () {
      const sql = 'SELECT x FROM (SELECT a, b AS bb FROM t) AS sub';
      final scope = _scopeAt(sql, 'SELECT x');
      expect(scope.visibleTables.single.knownColumns, ['a', 'bb']);
    });

    test('a subquery with a bare * reports unknown columns (null)', () {
      const sql = 'SELECT x FROM (SELECT * FROM t) AS sub';
      final scope = _scopeAt(sql, 'SELECT x');
      expect(scope.visibleTables.single.knownColumns, isNull);
    });

    test('a real table (not a CTE/subquery) always reports null — ask the catalog instead', () {
      const sql = 'SELECT x FROM pedidos';
      final scope = _scopeAt(sql, 'SELECT x');
      expect(scope.visibleTables.single.knownColumns, isNull);
    });
  });

  group('resolveScopeAt — UPDATE/DELETE', () {
    test('UPDATE...FROM includes both the target and the FROM table', () {
      const sql = 'UPDATE pedidos AS p SET total = o.total '
          'FROM otra AS o WHERE p.id = o.pedido_id';
      final scope = _scopeAt(sql, 'p.id');
      final aliases = scope.visibleTables.map((b) => b.alias).toSet();
      expect(aliases, {'p', 'o'});
    });

    test('DELETE...USING includes both the target and the USING table', () {
      const sql = 'DELETE FROM pedidos AS p USING otra AS o '
          'WHERE p.id = o.pedido_id';
      final scope = _scopeAt(sql, 'p.id');
      final aliases = scope.visibleTables.map((b) => b.alias).toSet();
      expect(aliases, {'p', 'o'});
    });

    test('a plain UPDATE with no FROM only has its own target in scope', () {
      const sql = "UPDATE pedidos SET estado = 'x' WHERE id = 1";
      final scope = _scopeAt(sql, 'id = 1');
      expect(scope.visibleTables, hasLength(1));
      expect(scope.visibleTables.single.tableName, 'pedidos');
    });
  });

  group('resolveScopeAt — INSERT ... SELECT', () {
    test('resolves scope inside the SELECT source, not the target', () {
      const sql =
          'INSERT INTO pedidos (a) SELECT x FROM clientes c WHERE c.activo';
      final scope = _scopeAt(sql, 'c.activo');
      expect(scope.visibleTables.single.alias, 'c');
      expect(scope.visibleTables.single.tableName, 'clientes');
    });
  });

  group('resolveScopeAt — UnknownStatement', () {
    test('never throws, returns an empty scope', () {
      final scope = resolveScopeAt(_statementOf('CREATE TABLE t (id int)'), 5);
      expect(scope.visibleTables, isEmpty);
    });
  });
}
