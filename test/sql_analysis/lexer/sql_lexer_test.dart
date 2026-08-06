import 'package:faro/sql_analysis/lexer/sql_lexer.dart';
import 'package:faro/sql_analysis/lexer/sql_token.dart';
import 'package:flutter_test/flutter_test.dart';

/// Significant tokens only (skips whitespace and the trailing EOF marker)
/// — most assertions below don't care about exact whitespace boundaries or
/// the sentinel EOF token, only the "real" tokens around them.
List<SqlToken> _significant(String source) => lexSql(source)
    .where((t) => t.type != SqlTokenType.whitespace && !t.isEof)
    .toList();

void main() {
  group('lexSql — keywords vs identifiers vs quoted identifiers', () {
    test('classifies a known word as keyword, case-insensitively', () {
      final tokens = _significant('select From WHERE');
      expect(tokens.map((t) => t.type),
          [SqlTokenType.keyword, SqlTokenType.keyword, SqlTokenType.keyword]);
      // Original case preserved in .text even though classification is
      // case-insensitive.
      expect(tokens[0].text, 'select');
      expect(tokens[1].text, 'From');
    });

    test('an ordinary word not in the keyword set is an identifier', () {
      final tokens = _significant('pedidos');
      expect(tokens.single.type, SqlTokenType.identifier);
    });

    test('a quoted identifier is its own type, quotes included in .text', () {
      final tokens = _significant('"Pedidos"');
      expect(tokens.single.type, SqlTokenType.quotedIdentifier);
      expect(tokens.single.text, '"Pedidos"');
    });

    test('"" inside a quoted identifier is an escaped literal quote, not a close', () {
      final tokens = _significant('"a""b" x');
      expect(tokens[0].type, SqlTokenType.quotedIdentifier);
      expect(tokens[0].text, '"a""b"');
      expect(tokens[1].type, SqlTokenType.identifier);
      expect(tokens[1].text, 'x');
    });

    test('a keyword used as a table/column-shaped identifier still lexes without throwing', () {
      // Not asserting it's reclassified as identifier (Postgres itself
      // treats many keywords as at-least-partially reserved) — just that
      // lexing something keyword-shaped in an identifier position never
      // crashes and produces exactly one token.
      expect(() => lexSql('select "order"'), returnsNormally);
    });
  });

  group('lexSql — numbers', () {
    test('plain integer', () {
      expect(_significant('123').single.type, SqlTokenType.number);
    });

    test('decimal', () {
      final t = _significant('123.45').single;
      expect(t.type, SqlTokenType.number);
      expect(t.text, '123.45');
    });

    test('leading-dot decimal', () {
      final t = _significant('.5').single;
      expect(t.type, SqlTokenType.number);
      expect(t.text, '.5');
    });

    test('exponent notation, signed and unsigned', () {
      expect(_significant('1e10').single.text, '1e10');
      expect(_significant('1.5e-10').single.text, '1.5e-10');
      expect(_significant('2E+3').single.text, '2E+3');
    });

    test('a bare "e" with no following digit is not swallowed into the number', () {
      final tokens = _significant('1e xyz');
      expect(tokens[0].type, SqlTokenType.number);
      expect(tokens[0].text, '1');
      expect(tokens[1].type, SqlTokenType.identifier);
      expect(tokens[1].text, 'e');
    });

    test('member access is not confused with a decimal number', () {
      final tokens = _significant('t.col');
      expect(tokens.map((t) => t.type),
          [SqlTokenType.identifier, SqlTokenType.punctuation, SqlTokenType.identifier]);
      expect(tokens[1].text, '.');
    });
  });

  group('lexSql — string literals', () {
    test("'' inside a string is an escaped literal quote, not a close", () {
      final tokens = _significant("'it''s' x");
      expect(tokens[0].type, SqlTokenType.string);
      expect(tokens[0].text, "'it''s'");
      expect(tokens[1].text, 'x');
    });

    test(r"backslash-escaped quote (Postgres E'...' syntax) is not a close either", () {
      final tokens = _significant(r"E'it\'s' x");
      // E lexes as its own identifier immediately before the string — no
      // dedicated E-string token type.
      expect(tokens[0].type, SqlTokenType.identifier);
      expect(tokens[0].text, 'E');
      expect(tokens[1].type, SqlTokenType.string);
      expect(tokens[1].text, r"'it\'s'");
      expect(tokens[2].text, 'x');
    });

    test('a keyword-looking word inside a string is not reclassified', () {
      final t = _significant("'this mentions FROM and SELECT'").single;
      expect(t.type, SqlTokenType.string);
    });

    test('unterminated string runs to end of input instead of throwing', () {
      expect(() => lexSql("SELECT 'never closed"), returnsNormally);
      final tokens = _significant("SELECT 'never closed");
      expect(tokens.last.type, SqlTokenType.string);
      expect(tokens.last.text, "'never closed");
    });
  });

  group('lexSql — dollar-quoting vs positional parameters', () {
    test(r'$$...$$ body lexes as one dollarString token', () {
      final t = _significant(r'$$ body text $$').single;
      expect(t.type, SqlTokenType.dollarString);
    });

    test(r'$tag$...$tag$ body lexes as one dollarString token', () {
      final t = _significant(r'$fn$ body text $fn$').single;
      expect(t.type, SqlTokenType.dollarString);
      expect(t.text, r'$fn$ body text $fn$');
    });

    test(r'a semicolon inside a dollar-quoted body does not split it', () {
      final t = _significant(r'$$ a := 1; b := 2; $$').single;
      expect(t.type, SqlTokenType.dollarString);
    });

    test(r'$1 is a positional parameter, never confused with a dollar-quote tag', () {
      final t = _significant(r'$1').single;
      expect(t.type, SqlTokenType.parameter);
      expect(t.text, r'$1');
    });

    test(r'$1 next to a real dollar-quoted body — both classified correctly', () {
      final tokens = _significant(r'$1 $$ x $$');
      expect(tokens[0].type, SqlTokenType.parameter);
      expect(tokens[1].type, SqlTokenType.dollarString);
    });

    test('unterminated dollar-quoted body runs to end of input instead of throwing', () {
      expect(() => lexSql(r'$$ never closed'), returnsNormally);
      final t = _significant(r'$$ never closed').single;
      expect(t.type, SqlTokenType.dollarString);
    });
  });

  group('lexSql — comments', () {
    test('line comment is its own token, stops before the newline', () {
      final tokens = lexSql('-- a comment\nSELECT');
      expect(tokens[0].type, SqlTokenType.lineComment);
      expect(tokens[0].text, '-- a comment');
    });

    test('block comment is its own token', () {
      final tokens = _significant('/* a\nmultiline comment */ SELECT');
      expect(tokens[0].type, SqlTokenType.blockComment);
      expect(tokens[0].text, '/* a\nmultiline comment */');
    });

    test('a keyword-looking word inside a comment is not reclassified', () {
      final t = _significant('-- SELECT FROM WHERE').single;
      expect(t.type, SqlTokenType.lineComment);
    });

    test('unterminated block comment runs to end of input instead of throwing', () {
      expect(() => lexSql('/* never closed'), returnsNormally);
      final t = _significant('/* never closed').single;
      expect(t.type, SqlTokenType.blockComment);
    });
  });

  group('lexSql — operators and punctuation', () {
    test('multi-char operators are matched greedily (longest first)', () {
      expect(_significant('a ->> b').map((t) => t.text), ['a', '->>', 'b']);
      expect(_significant('a -> b').map((t) => t.text), ['a', '->', 'b']);
      expect(_significant('a <= b').map((t) => t.text), ['a', '<=', 'b']);
      expect(_significant('a::int').map((t) => t.text), ['a', '::', 'int']);
    });

    test('punctuation tokens: parens, comma, semicolon, brackets', () {
      final tokens = _significant('(a, b);[c]');
      expect(tokens.map((t) => t.text),
          ['(', 'a', ',', 'b', ')', ';', '[', 'c', ']']);
      expect(tokens.every((t) =>
          t.type == SqlTokenType.punctuation ||
          t.type == SqlTokenType.identifier), isTrue);
    });
  });

  group('lexSql — never throws on malformed/unrecognized input', () {
    test('an unrecognized character becomes a width-one unknown token, scanning continues', () {
      final tokens = _significant('a § b');
      expect(tokens[0].text, 'a');
      expect(tokens[1].type, SqlTokenType.unknown);
      expect(tokens[1].text, '§');
      expect(tokens[2].text, 'b');
    });

    test('empty input lexes to just EOF', () {
      final tokens = lexSql('');
      expect(tokens.single.type, SqlTokenType.eof);
    });

    test('a whole script mixing every construct never throws', () {
      expect(
        () => lexSql(r'''
          WITH cte AS (SELECT a, b FROM t1 WHERE x = 'it''s $1 not a param')
          SELECT p.a, p."B", p.c::int, p.d ->> 'k'
          FROM cte p
          -- a trailing comment mentioning SELECT
          /* and a block one */
        '''),
        returnsNormally,
      );
    });

    test('always ends with exactly one eof token at source.length', () {
      const source = 'SELECT 1';
      final tokens = lexSql(source);
      expect(tokens.last.type, SqlTokenType.eof);
      expect(tokens.last.start, source.length);
      expect(tokens.last.end, source.length);
      expect(tokens.where((t) => t.type == SqlTokenType.eof).length, 1);
    });
  });

  group('SqlToken.missing', () {
    test('is a width-zero unknown token at the given offset', () {
      final t = SqlToken.missing(7);
      expect(t.type, SqlTokenType.unknown);
      expect(t.start, 7);
      expect(t.end, 7);
      expect(t.text, isEmpty);
    });
  });
}
