import 'package:faro/features/consulta/application/sql_tokenizer.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('tokenizeSql', () {
    test('splits code around a single-quoted string literal', () {
      final tokens = tokenizeSql("select 'hello' from t");
      expect(tokens.map((t) => t.kind), [
        SqlTokenKind.code,
        SqlTokenKind.string,
        SqlTokenKind.code,
      ]);
      expect(tokens[1].text, "'hello'");
    });

    test('handles a doubled single-quote escape inside a string literal', () {
      final tokens = tokenizeSql("select 'l''oreal'");
      final string = tokens.firstWhere((t) => t.kind == SqlTokenKind.string);
      expect(string.text, "'l''oreal'");
    });

    test(
        'real bug fixed 2026-08-03: recognizes a double-quoted identifier '
        'as its own protected token kind, not plain code', () {
      final tokens = tokenizeSql('select "from" from t');
      expect(
        tokens.map((t) => t.kind),
        [
          SqlTokenKind.code,
          SqlTokenKind.quotedIdentifier,
          SqlTokenKind.code,
        ],
      );
      expect(tokens[1].text, '"from"');
    });

    test('handles an escaped "" inside a double-quoted identifier', () {
      final tokens = tokenizeSql('select "a""b" from t');
      final ident =
          tokens.firstWhere((t) => t.kind == SqlTokenKind.quotedIdentifier);
      expect(ident.text, '"a""b"');
    });

    test('an unterminated double-quoted identifier runs to end of input '
        'instead of throwing', () {
      expect(() => tokenizeSql('select "unterminated'), returnsNormally);
      final tokens = tokenizeSql('select "unterminated');
      expect(tokens.last.kind, SqlTokenKind.quotedIdentifier);
      expect(tokens.last.text, '"unterminated');
    });

    test('a line comment is its own token, code resumes after the newline',
        () {
      final tokens = tokenizeSql('select 1 -- comment\nfrom t');
      expect(tokens.map((t) => t.kind), [
        SqlTokenKind.code,
        SqlTokenKind.lineComment,
        SqlTokenKind.code,
      ]);
    });

    test('a block comment is its own token', () {
      final tokens = tokenizeSql('select /* comment */ 1');
      expect(tokens.map((t) => t.kind), [
        SqlTokenKind.code,
        SqlTokenKind.blockComment,
        SqlTokenKind.code,
      ]);
    });
  });
}
