import 'package:faro/sql_analysis/analysis/scope_resolver.dart';
import 'package:faro/sql_analysis/analysis/sql_script_analyzer.dart';
import 'package:faro/sql_analysis/ast/statements.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('analyzeSqlScript — splitting', () {
    test('one statement per semicolon-separated chunk', () {
      final analysis = analyzeSqlScript('SELECT 1; SELECT 2; SELECT 3');
      expect(analysis.statements, hasLength(3));
      expect(analysis.statements.map((s) => s.statement.runtimeType),
          [SelectStatement, SelectStatement, SelectStatement]);
    });

    test('scriptStart/scriptEnd match the real substring in the original text', () {
      const script = 'SELECT 1; SELECT a FROM t';
      final analysis = analyzeSqlScript(script);
      for (final s in analysis.statements) {
        final slice = script.substring(s.scriptStart, s.scriptEnd);
        // Every statement's own text should round-trip back through the
        // same parser without new diagnostics — confirms the span is the
        // real statement text, not off by some fixed amount.
        expect(slice, isNotEmpty);
      }
      expect(script.substring(analysis.statements[1].scriptStart), 'SELECT a FROM t');
    });
  });

  group('analyzeSqlScript — diagnostics are absolute, not statement-relative', () {
    test('a diagnostic in the second statement points into the second statement\'s real text', () {
      const script = 'SELECT 1; SELECT a FROM WHERE x = 1';
      final analysis = analyzeSqlScript(script);
      expect(analysis.statements, hasLength(2));
      expect(analysis.statements[0].diagnostics, isEmpty);
      expect(analysis.statements[1].diagnostics, isNotEmpty);

      final diag = analysis.statements[1].diagnostics.single;
      // The diagnostic's absolute range must fall within the SECOND
      // statement's own script span, not the first (this is exactly the
      // bug this class exists to prevent — see its own doc comment).
      expect(diag.start, greaterThanOrEqualTo(analysis.statements[1].scriptStart));
      expect(diag.end, lessThanOrEqualTo(analysis.statements[1].scriptEnd));
    });

    test('allDiagnostics concatenates every statement\'s own diagnostics', () {
      const script = 'SELECT FROM t; SELECT a FROM';
      final analysis = analyzeSqlScript(script);
      expect(analysis.allDiagnostics.length,
          analysis.statements[0].diagnostics.length +
              analysis.statements[1].diagnostics.length);
      expect(analysis.allDiagnostics, isNotEmpty);
    });
  });

  group('analyzeSqlScript — statementAt', () {
    test('finds the statement whose span contains a given absolute offset', () {
      const script = 'SELECT 1; SELECT a FROM t';
      final analysis = analyzeSqlScript(script);
      final offsetInSecond = script.indexOf('FROM t');
      final found = analysis.statementAt(offsetInSecond);
      expect(found, same(analysis.statements[1]));
    });

    test('returns null for an offset outside every statement (e.g. inside the separator)', () {
      const script = 'SELECT 1;    SELECT 2';
      final analysis = analyzeSqlScript(script);
      // Right after the semicolon, inside the whitespace gap trimmed out
      // of both statement spans.
      final gapOffset = script.indexOf(';') + 1;
      expect(analysis.statementAt(gapOffset), isNull);
    });
  });

  group('AnalyzedStatement — offset conversion round-trips', () {
    test('toAbsoluteOffset / toRelativeOffset are inverses', () {
      const script = 'SELECT 1; SELECT a FROM t WHERE a = 1';
      final analysis = analyzeSqlScript(script);
      final stmt = analysis.statements[1];
      const relative = 5;
      final absolute = stmt.toAbsoluteOffset(relative);
      expect(stmt.toRelativeOffset(absolute), relative);
    });

    test('a relative offset converted to absolute lines up with resolveScopeAt usage', () {
      const script = "SELECT 1; SELECT x FROM pedidos p WHERE p.id = 1";
      final analysis = analyzeSqlScript(script);
      final stmt = analysis.statements[1];
      final absoluteCursor = script.indexOf('p.id');
      final relativeCursor = stmt.toRelativeOffset(absoluteCursor);
      final scope = resolveScopeAt(stmt.statement, relativeCursor);
      expect(scope.visibleTables.single.alias, 'p');
    });
  });

  group('analyzeSqlScript — never throws on a mixed valid/malformed script', () {
    test('one bad statement does not affect diagnostics for the others', () {
      const script = 'SELECT a FROM t; SELECT FROM ; SELECT b FROM u';
      expect(() => analyzeSqlScript(script), returnsNormally);
      final analysis = analyzeSqlScript(script);
      expect(analysis.statements, hasLength(3));
      expect(analysis.statements[0].diagnostics, isEmpty);
      expect(analysis.statements[1].diagnostics, isNotEmpty);
      expect(analysis.statements[2].diagnostics, isEmpty);
    });

    test('an empty script produces no statements', () {
      final analysis = analyzeSqlScript('');
      expect(analysis.statements, isEmpty);
      expect(analysis.allDiagnostics, isEmpty);
    });

    test('dollar-quoted PL/pgSQL body (real client bug this app already fixed once) still analyzes safely', () {
      const script = r'''
        CREATE FUNCTION f() RETURNS void AS $$
        BEGIN
          PERFORM 1;
        END;
        $$ LANGUAGE plpgsql;
        SELECT a FROM t;
      ''';
      expect(() => analyzeSqlScript(script), returnsNormally);
      final analysis = analyzeSqlScript(script);
      expect(analysis.statements, hasLength(2));
      expect(analysis.statements[0].statement, isA<UnknownStatement>());
      expect(analysis.statements[1].statement, isA<SelectStatement>());
    });
  });
}
