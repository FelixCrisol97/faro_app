import 'package:faro/core/constants/db_engine.dart';
import 'package:faro/data/models/table_column.dart';
import 'package:faro/features/consulta/application/csv_value_coercion.dart';
import 'package:flutter_test/flutter_test.dart';

TableColumn _column(
  String dataType, {
  bool nullable = true,
  String? defaultValue,
}) =>
    TableColumn(
      name: 'col',
      dataType: dataType,
      nullable: nullable,
      isPrimaryKey: false,
      defaultValue: defaultValue,
    );

void main() {
  group('coerceCsvValue — integers', () {
    test('parses a Postgres integer column', () {
      expect(coerceCsvValue('42', _column('integer'), DbEngine.postgres), 42);
    });

    test('parses a SQL Server int column', () {
      expect(coerceCsvValue('42', _column('int'), DbEngine.sqlServer), 42);
    });

    test('throws CsvValueError for a non-numeric value', () {
      expect(
        () => coerceCsvValue('abc', _column('integer'), DbEngine.postgres),
        throwsA(isA<CsvValueError>()),
      );
    });
  });

  group('coerceCsvValue — decimals', () {
    test('parses a Postgres numeric column', () {
      expect(
          coerceCsvValue('3.14', _column('numeric'), DbEngine.postgres), 3.14);
    });

    test('parses a SQL Server decimal column', () {
      expect(
          coerceCsvValue('3.14', _column('decimal'), DbEngine.sqlServer), 3.14);
    });
  });

  group('coerceCsvValue — booleans', () {
    test('accepts true/false for Postgres boolean', () {
      expect(coerceCsvValue('true', _column('boolean'), DbEngine.postgres),
          true);
      expect(coerceCsvValue('false', _column('boolean'), DbEngine.postgres),
          false);
    });

    test('accepts 1/0 for SQL Server bit', () {
      expect(coerceCsvValue('1', _column('bit'), DbEngine.sqlServer), true);
      expect(coerceCsvValue('0', _column('bit'), DbEngine.sqlServer), false);
    });

    test('throws CsvValueError for an unrecognized boolean token', () {
      expect(
        () => coerceCsvValue('maybe', _column('boolean'), DbEngine.postgres),
        throwsA(isA<CsvValueError>()),
      );
    });
  });

  group('coerceCsvValue — dates', () {
    test('returns an ISO-8601 String, never a DateTime, for a valid date', () {
      final value = coerceCsvValue(
          '2026-07-24T10:00:00', _column('timestamp without time zone'), DbEngine.postgres);
      expect(value, isA<String>());
      expect(DateTime.parse(value as String), DateTime.parse('2026-07-24T10:00:00'));
    });

    test('throws CsvValueError for an unparseable date', () {
      expect(
        () => coerceCsvValue('not-a-date', _column('date'), DbEngine.sqlServer),
        throwsA(isA<CsvValueError>()),
      );
    });
  });

  group('coerceCsvValue — bare time-of-day (real bug, 2026-08-03)', () {
    test('accepts "HH:mm:ss" for Postgres time without time zone', () {
      expect(
        coerceCsvValue(
            '14:30:00', _column('time without time zone'), DbEngine.postgres),
        '14:30:00',
      );
    });

    test('accepts "HH:mm:ss" for SQL Server time', () {
      expect(
        coerceCsvValue('14:30:00', _column('time'), DbEngine.sqlServer),
        '14:30:00',
      );
    });

    test('accepts "HH:mm" without seconds', () {
      expect(
        coerceCsvValue('9:05', _column('time'), DbEngine.sqlServer),
        '9:05',
      );
    });

    test('accepts fractional seconds', () {
      expect(
        coerceCsvValue(
            '14:30:00.123', _column('time with time zone'), DbEngine.postgres),
        '14:30:00.123',
      );
    });

    test('throws CsvValueError for an unparseable time', () {
      expect(
        () => coerceCsvValue('not-a-time', _column('time'), DbEngine.sqlServer),
        throwsA(isA<CsvValueError>()),
      );
    });

    test('a full date column is unaffected and still requires a date', () {
      expect(
        () => coerceCsvValue('14:30:00', _column('date'), DbEngine.sqlServer),
        throwsA(isA<CsvValueError>()),
      );
    });
  });

  group('coerceCsvValue — text passthrough', () {
    test('returns the trimmed string for an unrecognized/text type', () {
      expect(coerceCsvValue('  hello  ', _column('text'), DbEngine.postgres),
          'hello');
      expect(
          coerceCsvValue('some-uuid', _column('uuid'), DbEngine.postgres),
          'some-uuid');
    });
  });

  group('coerceCsvValue — empty values', () {
    test('returns null for an empty value in a nullable column', () {
      expect(
          coerceCsvValue('', _column('integer', nullable: true), DbEngine.postgres),
          null);
      expect(
          coerceCsvValue(null, _column('integer', nullable: true), DbEngine.postgres),
          null);
    });

    test('returns null for an empty value when a default exists', () {
      expect(
        coerceCsvValue(
            '', _column('integer', nullable: false, defaultValue: '0'), DbEngine.postgres),
        null,
      );
    });

    test(
        'throws CsvValueError for an empty value in a non-nullable column with no default',
        () {
      expect(
        () => coerceCsvValue(
            '', _column('integer', nullable: false), DbEngine.postgres),
        throwsA(isA<CsvValueError>()),
      );
    });
  });
}
