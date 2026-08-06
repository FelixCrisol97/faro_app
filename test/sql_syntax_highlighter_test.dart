import 'package:faro/features/consulta/application/sql_syntax_highlighter.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  const colors = SqlHighlightColors(
    keyword: Color(0xFF111111),
    string: Color(0xFF222222),
    comment: Color(0xFF333333),
    number: Color(0xFF444444),
  );
  const base = TextStyle(fontSize: 14);

  List<TextSpan> spansOf(String text) {
    final span = highlightSql(text, base, colors);
    return span.children!.cast<TextSpan>();
  }

  group('highlightSql', () {
    test('colors a keyword without changing its case', () {
      final spans = spansOf('select 1');
      final keywordSpan = spans.firstWhere((s) => s.text == 'select');
      expect(keywordSpan.style!.color, colors.keyword);
    });

    test('colors a string literal, not the keyword-looking word inside it', () {
      final spans = spansOf("where nombre = 'from the archive'");
      final stringSpan = spans.firstWhere((s) => s.text == "'from the archive'");
      expect(stringSpan.style!.color, colors.string);
      // The word "from" only appears once, inside the string — none of the
      // other spans should carry the keyword color for it.
      final keywordColored = spans.where((s) => s.style!.color == colors.keyword).map((s) => s.text);
      expect(keywordColored, isNot(contains('from')));
    });

    test('colors a line comment and keeps its text verbatim', () {
      final spans = spansOf('select 1 -- from where\n');
      final commentSpan = spans.firstWhere((s) => s.text == '-- from where');
      expect(commentSpan.style!.color, colors.comment);
      expect(commentSpan.style!.fontStyle, FontStyle.italic);
    });

    test('colors a numeric literal', () {
      final spans = spansOf('where cantidad = 1234');
      final numberSpan = spans.firstWhere((s) => s.text == '1234');
      expect(numberSpan.style!.color, colors.number);
    });

    test('colors a decimal numeric literal as one token', () {
      final spans = spansOf('where precio = 19.99');
      final numberSpan = spans.firstWhere((s) => s.text == '19.99');
      expect(numberSpan.style!.color, colors.number);
    });

    test('leaves plain text uncolored', () {
      final spans = spansOf('select sku');
      final plain = spans.firstWhere((s) => s.text == ' sku');
      expect(plain.style!.color, isNull);
    });

    test('colors a two-word keyword as a single span', () {
      final spans = spansOf('select * from t group by t.sku');
      final span = spans.firstWhere((s) => s.text == 'group by');
      expect(span.style!.color, colors.keyword);
    });

    test('two-word keyword matching is case-insensitive', () {
      final spans = spansOf('SELECT * FROM t ORDER BY t.sku');
      final span = spans.firstWhere((s) => s.text == 'ORDER BY');
      expect(span.style!.color, colors.keyword);
    });

    test(
        'does not join a two-word keyword when the gap is not whitespace-only',
        () {
      // Nonsense SQL on purpose — the point is only that "group"/"by"
      // separated by a paren must never be colored as one "group by" span.
      final spans = spansOf('select group(by) from t');
      expect(spans.any((s) => s.text == 'group(by'), isFalse);
      expect(spans.any((s) => s.text?.toLowerCase() == 'group by'), isFalse);
    });
  });
}
