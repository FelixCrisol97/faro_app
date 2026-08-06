import 'package:faro/features/consulta/application/sql_formatter.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('formatSql', () {
    test('puts major clauses on their own line, uppercased', () {
      final result = formatSql('select id, nombre from productos where categoria = 1 order by nombre');
      expect(
        result,
        'SELECT id, nombre\n'
        'FROM productos\n'
        'WHERE categoria = 1\n'
        'ORDER BY nombre',
      );
    });

    test('does not mangle a string literal containing clause keywords', () {
      final result = formatSql("select * from productos where nombre = 'from the archive'");
      expect(result, contains("'from the archive'"));
      expect(result, isNot(contains('FROM the archive')));
    });

    test('preserves an escaped quote inside a string literal', () {
      final result = formatSql("select * from productos where nombre = 'l''oreal'");
      expect(result, contains("'l''oreal'"));
    });

    test(
        'real bug fixed 2026-08-03: does not mangle a double-quoted '
        '(case-sensitive Postgres) identifier that looks like a keyword',
        () {
      final result = formatSql('select "from" from tabla');
      expect(result, contains('"from"'));
      // Before the fix, the quoted identifier's contents got reflowed onto
      // their own uppercased line, corrupting it into `"\nFROM"`.
      expect(result, isNot(contains('"\nFROM"')));
      expect(result, isNot(contains('FROM"')));
    });

    test('preserves an escaped double quote inside a quoted identifier', () {
      final result = formatSql('select "a""b" from tabla');
      expect(result, contains('"a""b"'));
    });

    test('does not mangle a line comment containing clause keywords', () {
      final result = formatSql('select * from productos -- select from where\nwhere id = 1');
      expect(result, contains('-- select from where'));
    });

    test('does not mangle a block comment containing clause keywords', () {
      final result = formatSql('select * /* from where */ from productos');
      expect(result, contains('/* from where */'));
    });

    test('indents a subquery one level deeper than its enclosing query', () {
      final result = formatSql('select * from (select sku from productos where activo = true) t');
      final lines = result.split('\n');
      expect(lines.first, 'SELECT *');
      expect(lines, contains('  SELECT sku'));
      expect(lines.any((l) => l.startsWith('  WHERE activo = true')), isTrue);
    });

    test('joins get their own line, longest variant matched first', () {
      final result = formatSql('select * from a left join b on a.id = b.id');
      expect(result, contains('\nLEFT JOIN b on a.id = b.id'));
    });
  });
}
