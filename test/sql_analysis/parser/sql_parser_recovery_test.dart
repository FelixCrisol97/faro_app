import 'package:faro/sql_analysis/ast/statements.dart';
import 'package:faro/sql_analysis/ast/table_ref.dart';
import 'package:faro/sql_analysis/lexer/sql_lexer.dart';
import 'package:faro/sql_analysis/parser/sql_parser.dart';
import 'package:flutter_test/flutter_test.dart';

/// The most important test file in this module — see the plan doc's own
/// framing: recovery, not the happy-path grammar, is what makes this
/// parser usable against SQL that's routinely mid-edit. Every case here
/// confirms the same three things: no exception, a reasonable diagnostic
/// (or none, when the input is actually valid-so-far), and the rest of the
/// statement still parses instead of one error cascading into garbage.
void main() {
  group('missing table name after FROM', () {
    test('SELECT a, b FROM — nothing after FROM at all', () {
      expect(() => SqlParser.parse(lexSql('SELECT a, b FROM')), returnsNormally);
      final result = SqlParser.parse(lexSql('SELECT a, b FROM'));
      expect(result.diagnostics, isNotEmpty);
      final stmt = result.statement as SelectStatement;
      expect(stmt.selectList, hasLength(2));
      expect(stmt.fromClause, isNotNull);
      expect(stmt.fromClause!.items.single.ref, isA<NamedTableRef>());
    });

    test('SELECT a, b FROM ped — a table name mid-typing is valid, not an error', () {
      final result = SqlParser.parse(lexSql('SELECT a, b FROM ped'));
      expect(result.diagnostics, isEmpty);
      final stmt = result.statement as SelectStatement;
      expect((stmt.fromClause!.items.single.ref as NamedTableRef).name, 'ped');
    });

    test('SELECT a FROM WHERE x = 1 — WHERE is never consumed as the missing table', () {
      final result = SqlParser.parse(lexSql('SELECT a FROM WHERE x = 1'));
      expect(result.diagnostics, isNotEmpty);
      expect(result.diagnostics.any((d) => d.code == 'expected-table-name'), isTrue);
      final stmt = result.statement as SelectStatement;
      // The critical assertion: WHERE still got parsed as WHERE, not
      // swallowed as the table's name.
      expect(stmt.whereClause, isNotNull);
      expect(stmt.whereClause!.sourceText, contains('x'));
      expect((stmt.fromClause!.items.single.ref as NamedTableRef).name, isEmpty);
    });
  });

  group('missing select list', () {
    test('SELECT FROM t — one diagnostic, FROM still resolves the real table', () {
      final result = SqlParser.parse(lexSql('SELECT FROM t'));
      expect(result.diagnostics, isNotEmpty);
      expect(result.diagnostics.any((d) => d.code == 'expected-select-item'), isTrue);
      final stmt = result.statement as SelectStatement;
      expect(stmt.selectList, isEmpty);
      expect((stmt.fromClause!.items.single.ref as NamedTableRef).name, 't');
    });
  });

  group('dangling comma', () {
    test('SELECT a, FROM t — a diagnostic, FROM is not eaten as a column', () {
      final result = SqlParser.parse(lexSql('SELECT a, FROM t'));
      expect(result.diagnostics, isNotEmpty);
      final stmt = result.statement as SelectStatement;
      expect(stmt.selectList, hasLength(2));
      expect(stmt.selectList[0].expression!.sourceText, 'a');
      expect(stmt.selectList[1].expression, isNull);
      expect(stmt.fromClause, isNotNull);
      expect((stmt.fromClause!.items.single.ref as NamedTableRef).name, 't');
    });
  });

  group('unclosed subquery', () {
    test('FROM (SELECT * FROM t — parses the inner query, flags only the missing )', () {
      final result = SqlParser.parse(lexSql('SELECT * FROM (SELECT * FROM t'));
      expect(result.diagnostics, isNotEmpty);
      expect(
          result.diagnostics.any((d) => d.code == 'expected-close-paren'), isTrue);
      final stmt = result.statement as SelectStatement;
      final ref = stmt.fromClause!.items.single.ref as SubqueryTableRef;
      expect(ref.subquery, isNotNull);
      expect((ref.subquery!.fromClause!.items.single.ref as NamedTableRef).name, 't');
    });

    test('deeply nested unclosed parens never hang or throw', () {
      final sql = '${'(' * 80}SELECT 1';
      expect(() => SqlParser.parse(lexSql(sql)), returnsNormally);
    });
  });

  group('token soup', () {
    test('garbage after a real SELECT still terminates with diagnostics, no exception', () {
      expect(() => SqlParser.parse(lexSql('SELECT , FROM , WHERE')), returnsNormally);
      final result = SqlParser.parse(lexSql('SELECT , FROM , WHERE'));
      expect(result.diagnostics, isNotEmpty);
      expect(result.statement, isA<SelectStatement>());
    });

    test('a long run of pure punctuation never hangs (bounded, not just "eventually returns")', () {
      final sql = 'SELECT ${',' * 3000} FROM t';
      final stopwatch = Stopwatch()..start();
      final result = SqlParser.parse(lexSql(sql));
      stopwatch.stop();
      expect(result.statement, isA<SelectStatement>());
      // Generous bound — this is a regression guard against accidental
      // quadratic behavior, not a tight performance benchmark (that's
      // `perf/sql_lexer_parser_benchmark_test.dart` in a later increment).
      expect(stopwatch.elapsedMilliseconds, lessThan(2000));
    });

    test('a leading keyword that is not SELECT/WITH/INSERT/UPDATE/DELETE is UnknownStatement, never throws', () {
      for (final sql in [')', ', , ,', '1 + 1', '']) {
        expect(() => SqlParser.parse(lexSql(sql)), returnsNormally, reason: sql);
      }
    });
  });

  group('empty and EOF-only input', () {
    test('empty string parses to an empty UnknownStatement, no diagnostics, no exception', () {
      final result = SqlParser.parse(lexSql(''));
      expect(result.statement, isA<UnknownStatement>());
      expect(result.diagnostics, isEmpty);
    });

    test('whitespace/comment-only input never throws', () {
      expect(() => SqlParser.parse(lexSql('   \n-- just a comment\n  ')),
          returnsNormally);
    });
  });

  group('mid-clause truncation — realistic "still typing" snapshots', () {
    test('SELECT a, b FR — trailing partial keyword is not itself an error', () {
      // "FR" isn't the FROM keyword (lexes as a plain identifier), so this
      // is genuinely just "SELECT a, b FR" with no FROM clause at all yet
      // — not a syntax error. Per the unambiguous implicit-alias rule,
      // "b FR" parses as column `b` implicitly aliased `FR` (same shape as
      // the "implicit alias without AS" test above), not as two separate
      // columns — there's no comma between them.
      final result = SqlParser.parse(lexSql('SELECT a, b FR'));
      expect(result.diagnostics, isEmpty);
      final stmt = result.statement as SelectStatement;
      expect(stmt.selectList, hasLength(2));
      expect(stmt.selectList[1].expression!.sourceText, 'b');
      expect(stmt.selectList[1].alias, 'FR');
      expect(stmt.fromClause, isNull);
    });

    test('SELECT a FROM t WHERE — WHERE with nothing after it yet', () {
      final result = SqlParser.parse(lexSql('SELECT a FROM t WHERE'));
      expect(result.diagnostics, isEmpty);
      final stmt = result.statement as SelectStatement;
      expect(stmt.whereClause, isNotNull);
      expect(stmt.whereClause!.tokens, isEmpty);
    });

    test('SELECT a FROM t JOIN — a join with no table yet', () {
      expect(() => SqlParser.parse(lexSql('SELECT a FROM t JOIN')), returnsNormally);
      final result = SqlParser.parse(lexSql('SELECT a FROM t JOIN'));
      expect(result.diagnostics, isNotEmpty);
    });

    test('WITH cte AS ( — an unclosed, empty CTE body', () {
      expect(() => SqlParser.parse(lexSql('WITH cte AS ( SELECT * FROM t')), returnsNormally);
    });
  });

  group('unconsumed trailing tokens', () {
    test(
        'a stray unmatched ")" after an otherwise complete, valid SELECT is '
        'flagged (real bug fixed 2026-08-04, AUDITORIA_CODIGO.md: parse() '
        'never checked whether it reached end of input, so this used to '
        'return cleanly with zero diagnostics while silently dropping the '
        'tail)', () {
      final result = SqlParser.parse(lexSql('SELECT a FROM t)'));
      expect(result.diagnostics, isNotEmpty);
      expect(result.diagnostics.any((d) => d.code == 'unconsumed-tokens'),
          isTrue);
      // The valid part in front still parsed correctly — this is a signal
      // on top of a good-faith parse, not a replacement for it.
      final stmt = result.statement as SelectStatement;
      expect((stmt.fromClause!.items.single.ref as NamedTableRef).name, 't');
    });

    test('a fully valid statement with nothing left over has no such diagnostic', () {
      final result = SqlParser.parse(lexSql('SELECT a FROM t WHERE a = 1'));
      expect(result.diagnostics, isEmpty);
    });
  });
}
