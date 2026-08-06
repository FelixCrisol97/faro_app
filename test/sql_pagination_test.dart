import 'package:faro/core/constants/db_engine.dart';
import 'package:faro/features/consulta/application/sql_pagination.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('isPaginableSelect', () {
    test('true for a plain SELECT', () {
      expect(isPaginableSelect('SELECT * FROM productos'), isTrue);
    });

    test('true for a WITH (CTE) statement', () {
      expect(
          isPaginableSelect('WITH t AS (SELECT 1) SELECT * FROM t'), isTrue);
    });

    test('false for a mutating statement', () {
      expect(isPaginableSelect("UPDATE productos SET nombre = 'x'"), isFalse);
    });

    test('false when the statement already has its own LIMIT', () {
      expect(
          isPaginableSelect('SELECT * FROM productos LIMIT 10'), isFalse);
    });

    test('false when the statement already has its own OFFSET', () {
      expect(
          isPaginableSelect('SELECT * FROM productos OFFSET 10 ROWS'),
          isFalse);
    });

    test('false when the statement already has its own TOP', () {
      expect(
          isPaginableSelect('SELECT TOP 10 * FROM productos'), isFalse);
    });

    test('a "limit" appearing only inside a trailing comment does not false-positive', () {
      expect(
          isPaginableSelect(
              'SELECT * FROM productos -- sin limit real'),
          isTrue);
    });

    test('a "top" appearing only inside a string literal does not false-positive', () {
      expect(
          isPaginableSelect("SELECT * FROM productos WHERE nombre = 'top seller'"),
          isTrue);
    });

    test(
        'real bug fixed 2026-08-03: true for a SELECT preceded by a header '
        'line comment (used to always return false)', () {
      expect(
          isPaginableSelect(
              '-- Reporte de ventas\nSELECT * FROM productos'),
          isTrue);
    });

    test('true for a SELECT preceded by a header block comment', () {
      expect(
          isPaginableSelect(
              '/* Reporte de ventas */\nSELECT * FROM productos'),
          isTrue);
    });

    test('still false for a mutating statement preceded by a header comment',
        () {
      expect(
          isPaginableSelect("-- nota\nUPDATE productos SET nombre = 'x'"),
          isFalse);
    });
  });

  group('wrapForPage', () {
    test('Postgres uses LIMIT/OFFSET', () {
      final sql = wrapForPage('SELECT * FROM productos', DbEngine.postgres,
          offset: 5000, fetchCount: 5001);
      expect(sql, contains('SELECT * FROM (\nSELECT * FROM productos\n) AS _faro_page'));
      expect(sql, contains('LIMIT 5001 OFFSET 5000'));
    });

    test('SQL Server uses ORDER BY + OFFSET/FETCH', () {
      final sql = wrapForPage('SELECT * FROM productos', DbEngine.sqlServer,
          offset: 0, fetchCount: 5001);
      expect(sql, contains('ORDER BY (SELECT NULL)'));
      expect(sql, contains('OFFSET 0 ROWS FETCH NEXT 5001 ROWS ONLY'));
    });
  });
}
