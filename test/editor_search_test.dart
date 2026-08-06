import 'package:faro/features/consulta/application/editor_search.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('findMatches', () {
    test('finds every occurrence in order', () {
      final matches = findMatches('select a from t where a > 1', 'a');
      expect(matches.map((m) => m.start), [7, 22]);
    });

    test('matches case-insensitively', () {
      final matches = findMatches('SELECT * FROM productos', 'select');
      expect(matches, hasLength(1));
      expect(matches.single.start, 0);
      expect(matches.single.end, 6);
    });

    test('returns non-overlapping matches', () {
      // "aa" inside "aaaa" is 2 matches (0-2, 2-4), not 3.
      final matches = findMatches('aaaa', 'aa');
      expect(matches.map((m) => (m.start, m.end)), [(0, 2), (2, 4)]);
    });

    test('empty query finds nothing', () {
      expect(findMatches('select 1', ''), isEmpty);
    });

    test('empty text finds nothing', () {
      expect(findMatches('', 'select'), isEmpty);
    });

    test('no match anywhere returns an empty list', () {
      expect(findMatches('select 1', 'xyz'), isEmpty);
    });

    test('finds matches across a multi-line script', () {
      const text = 'select a\nfrom productos\nwhere a > 1';
      final matches = findMatches(text, 'a');
      expect(matches, hasLength(2));
    });
  });
}
