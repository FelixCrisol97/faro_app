import 'package:faro/features/consulta/application/sql_statement_resolver.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('resolveStatementsToRun', () {
    test('runs the exact selection when one is present', () {
      final result = resolveStatementsToRun(
        fullText: 'SELECT 1; SELECT 2;',
        selectedText: 'SELECT 1',
      );
      expect(result, ['SELECT 1']);
    });

    test('runs every statement, in order, when there is no selection', () {
      final result = resolveStatementsToRun(fullText: 'SELECT 1; SELECT 2;');
      expect(result, ['SELECT 1', 'SELECT 2']);
    });

    test('runs the only statement when there is just one', () {
      final result = resolveStatementsToRun(fullText: 'SELECT * FROM productos');
      expect(result, ['SELECT * FROM productos']);
    });

    test('returns an empty list when there is nothing runnable', () {
      final result = resolveStatementsToRun(fullText: '   ;  ;  ');
      expect(result, isEmpty);
    });

    test('ignores a blank/whitespace-only selection', () {
      final result = resolveStatementsToRun(fullText: 'SELECT 1; SELECT 2;', selectedText: '   ');
      expect(result, ['SELECT 1', 'SELECT 2']);
    });

    test('splits multiple full statements typed one per line with no semicolons', () {
      const text = 'select 1\nselect 2\nselect 3';
      // No selection: runs every statement, same as the semicolon-delimited
      // case above — but each SELECT must resolve on its own, not get
      // concatenated into one invalid multi-select query.
      final result = resolveStatementsToRun(fullText: text);
      expect(result, ['select 1', 'select 2', 'select 3']);
    });

    test('a selection spanning several semicolon-separated statements runs all of them in order', () {
      final result = resolveStatementsToRun(
        fullText: 'SELECT 1; SELECT 2; SELECT 3;',
        selectedText: 'SELECT 1; SELECT 2; SELECT 3;',
      );
      expect(result, ['SELECT 1', 'SELECT 2', 'SELECT 3']);
    });

    test('a selection spanning several statements typed one per line runs all of them in order', () {
      final result = resolveStatementsToRun(
        fullText: 'update a set x = 1\nupdate b set y = 2',
        selectedText: 'update a set x = 1\nupdate b set y = 2',
      );
      expect(result, ['update a set x = 1', 'update b set y = 2']);
    });
  });

  group('splitSqlStatements', () {
    test('splits on semicolons when present, ignoring keyword-line boundaries', () {
      final spans = splitSqlStatements('SELECT 1; SELECT 2;');
      expect(spans.map((s) => s.text), ['SELECT 1', 'SELECT 2']);
    });

    test('does not split on semicolons inside a dollar-quoted function body', () {
      const sql = '''
CREATE FUNCTION public.f(integer) RETURNS smallint
    LANGUAGE plpgsql
    AS \$_\$
DECLARE
    nZona ALIAS FOR \$1;
    nRet  int2;
BEGIN
    nRet := 0;
    PERFORM 1;
    RETURN nRet;
END;
\$_\$;
''';
      final spans = splitSqlStatements(sql);
      expect(spans, hasLength(1));
      expect(spans.single.text, sql.trim().replaceFirst(RegExp(r';$'), ''));
    });

    test('does not split on a semicolon inside a string literal', () {
      final spans =
          splitSqlStatements("INSERT INTO t (msg) VALUES ('a;b'); SELECT 1;");
      expect(spans.map((s) => s.text),
          ["INSERT INTO t (msg) VALUES ('a;b')", 'SELECT 1']);
    });

    test('does not split on a semicolon inside a line comment', () {
      final spans = splitSqlStatements('SELECT 1; -- a; b\nSELECT 2;');
      expect(spans.map((s) => s.text), ['SELECT 1', '-- a; b\nSELECT 2']);
    });

    test('does not treat a \$1 positional parameter as a dollar-quote start', () {
      final spans = splitSqlStatements('SELECT \$1; SELECT \$2;');
      expect(spans.map((s) => s.text), ['SELECT \$1', 'SELECT \$2']);
    });

    test('splits on statement-starting keywords when there are no semicolons', () {
      final spans = splitSqlStatements('select a from t\nupdate t set x = 1\ndelete from t');
      expect(spans.map((s) => s.text), ['select a from t', 'update t set x = 1', 'delete from t']);
    });

    test('keeps a multi-line single statement (e.g. a wrapped SELECT) as one span', () {
      final spans = splitSqlStatements('select a, b\nfrom t\nwhere x = 1');
      expect(spans, hasLength(1));
      expect(spans.single.text, 'select a, b\nfrom t\nwhere x = 1');
    });

    test(
        'real bug fixed 2026-08-03: does not split a no-semicolon script '
        'through the middle of a multi-line string literal whose '
        'continuation line starts with a watched keyword', () {
      final spans = splitSqlStatements(
          "select 'primera linea\nselect esto sigue siendo el mismo string' as texto");
      expect(spans, hasLength(1));
      expect(
        spans.single.text,
        "select 'primera linea\nselect esto sigue siendo el mismo string' as texto",
      );
    });

    test(
        'real bug fixed 2026-08-03: does not split through a multi-line '
        'block comment either', () {
      final spans = splitSqlStatements(
          'select 1 /* nota\nselect esto es un comentario */\nupdate t set x = 1');
      expect(spans.map((s) => s.text), [
        'select 1 /* nota\nselect esto es un comentario */',
        'update t set x = 1',
      ]);
    });

    group(
        'real bug fixed 2026-08-05 (client-reported): a leading comment '
        'with no `;` in the script used to get split off as its own '
        'fake, comment-only statement, which then failed the read-only '
        'guard and stopped the real statement from ever running', () {
      test('a leading line comment (--) stays attached to the SELECT after it', () {
        final spans = splitSqlStatements('-- nota\nSELECT * FROM productos');
        expect(spans, hasLength(1));
        expect(spans.single.text, '-- nota\nSELECT * FROM productos');
      });

      test('a leading block comment (/* */) stays attached to the SELECT after it', () {
        final spans = splitSqlStatements('/* nota */\nSELECT * FROM productos');
        expect(spans, hasLength(1));
        expect(spans.single.text, '/* nota */\nSELECT * FROM productos');
      });

      test('a leading multi-line block comment stays attached too', () {
        final spans = splitSqlStatements(
            '/* nota\nmultilinea */\nSELECT * FROM productos');
        expect(spans, hasLength(1));
        expect(spans.single.text,
            '/* nota\nmultilinea */\nSELECT * FROM productos');
      });

      test(
          'two genuinely separate statements with only a block comment '
          'between them (no `;`) still split into two — the fix must not '
          'merge real statements together', () {
        final spans = splitSqlStatements('SELECT 1\n/* nota */\nSELECT 2');
        expect(spans.map((s) => s.text),
            ['SELECT 1\n/* nota */', 'SELECT 2']);
      });

      test(
          'two genuinely separate statements with only a line comment '
          'between them (no `;`) still split into two', () {
        final spans = splitSqlStatements('SELECT 1\n-- nota\nSELECT 2');
        expect(spans.map((s) => s.text), ['SELECT 1\n-- nota', 'SELECT 2']);
      });

      test('three statements chained with a comment between each one', () {
        final spans = splitSqlStatements(
            'SELECT 1\n-- primero\nSELECT 2\n/* segundo */\nSELECT 3');
        expect(spans.map((s) => s.text), [
          'SELECT 1\n-- primero',
          'SELECT 2\n/* segundo */',
          'SELECT 3',
        ]);
      });
    });
  });
}
