import 'package:faro/shared/utils/file_paths.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('ensureExtension', () {
    test('appends the extension when the path has none', () {
      expect(ensureExtension('consulta', 'sql'), 'consulta.sql');
    });

    test('leaves the path unchanged when it already has the extension', () {
      expect(ensureExtension('consulta.sql', 'sql'), 'consulta.sql');
    });

    test('matches the extension case-insensitively', () {
      expect(ensureExtension('consulta.SQL', 'sql'), 'consulta.SQL');
    });

    test('appends when the path has a different extension', () {
      expect(ensureExtension('consulta.txt', 'sql'), 'consulta.txt.sql');
    });

    test('works with a full Windows path, not just a bare filename', () {
      expect(ensureExtension(r'C:\Users\felix\Documents\consulta', 'sql'),
          r'C:\Users\felix\Documents\consulta.sql');
    });
  });
}
