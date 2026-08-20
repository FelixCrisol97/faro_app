import 'package:faro/data/repositories/sql_guard.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('SqlGuard.assertReadOnly', () {
    test('allows a plain SELECT', () {
      expect(() => SqlGuard.assertReadOnly('SELECT * FROM productos'), returnsNormally);
    });

    test('allows a WITH (CTE) statement', () {
      expect(
        () => SqlGuard.assertReadOnly('WITH t AS (SELECT 1) SELECT * FROM t'),
        returnsNormally,
      );
    });

    test('blocks UPDATE', () {
      expect(
        () => SqlGuard.assertReadOnly("UPDATE productos SET nombre = 'x'"),
        throwsA(isA<ReadOnlyViolationException>()),
      );
    });

    test('blocks DELETE', () {
      expect(
        () => SqlGuard.assertReadOnly('DELETE FROM productos'),
        throwsA(isA<ReadOnlyViolationException>()),
      );
    });

    test('blocks a mutating statement disguised behind leading whitespace', () {
      expect(
        () => SqlGuard.assertReadOnly('   DROP TABLE productos'),
        throwsA(isA<ReadOnlyViolationException>()),
      );
    });

    test('blocks SELECT ... INTO (creates a table, not a pure read)', () {
      expect(
        () => SqlGuard.assertReadOnly(
            'SELECT col1, col2 INTO nueva_tabla FROM tabla_existente'),
        throwsA(isA<ReadOnlyViolationException>()),
      );
    });

    test('allows a SELECT preceded by a line comment', () {
      expect(
        () => SqlGuard.assertReadOnly('-- nota\nSELECT * FROM productos'),
        returnsNormally,
      );
    });

    test('allows a SELECT preceded by a block comment', () {
      expect(
        () =>
            SqlGuard.assertReadOnly('/* nota */\nSELECT * FROM productos'),
        returnsNormally,
      );
    });

    test('allows a mutating keyword inside a string literal', () {
      expect(
        () => SqlGuard.assertReadOnly(
            "SELECT * FROM audit_log WHERE accion = 'update'"),
        returnsNormally,
      );
    });

    test('allows a mutating keyword inside a double-quoted identifier', () {
      expect(
        () => SqlGuard.assertReadOnly('SELECT "delete" FROM productos'),
        returnsNormally,
      );
    });

    test('allows a mutating keyword inside a SQL Server bracketed identifier',
        () {
      expect(
        () => SqlGuard.assertReadOnly('SELECT [Delete] FROM productos'),
        returnsNormally,
      );
    });

    test('still blocks a mutating statement hidden behind a comment', () {
      expect(
        () => SqlGuard.assertReadOnly('-- nota\nDROP TABLE productos'),
        throwsA(isA<ReadOnlyViolationException>()),
      );
    });
  });
}
