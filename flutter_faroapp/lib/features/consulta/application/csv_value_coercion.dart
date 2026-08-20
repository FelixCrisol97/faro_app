import '../../../core/constants/db_engine.dart';
import '../../../data/models/table_column.dart';

/// Thrown by [coerceCsvValue] when a raw CSV string can't be turned into a
/// value that fits [TableColumn]'s real type — caught per-row by
/// `csv_import_service.dart` and reported as that row's failure, never
/// allowed to abort the rest of the import.
class CsvValueError implements Exception {
  const CsvValueError(this.message);
  final String message;
  @override
  String toString() => message;
}

const _postgresIntTypes = {
  'integer',
  'bigint',
  'smallint',
  'serial',
  'bigserial',
  'smallserial',
};
const _postgresDecimalTypes = {'numeric', 'real', 'double precision', 'decimal'};
const _postgresBoolTypes = {'boolean'};
const _postgresDateTimeTypes = {
  'timestamp without time zone',
  'timestamp with time zone',
  'date',
};
// Real bug fixed 2026-08-03 (AUDITORIA_CODIGO.md): these used to be
// lumped in with the date/timestamp types above and validated via
// `DateTime.tryParse`, which requires a date component — a plain
// time-of-day value like "14:30:00" (the only kind of value a `time`
// column ever holds) always failed to parse, so CSV import into any
// `time`/`time without time zone`/`time with time zone` column was
// unconditionally broken. Handled separately below via `_parseTimeOfDay`.
const _postgresTimeTypes = {
  'time without time zone',
  'time with time zone',
};

const _sqlServerIntTypes = {'int', 'bigint', 'smallint', 'tinyint'};
const _sqlServerDecimalTypes = {
  'decimal',
  'numeric',
  'float',
  'real',
  'money',
  'smallmoney',
};
const _sqlServerBoolTypes = {'bit'};
const _sqlServerDateTimeTypes = {
  'datetime',
  'datetime2',
  'smalldatetime',
  'date',
  'datetimeoffset',
};
const _sqlServerTimeTypes = {'time'};

/// Converts one raw CSV cell into the Dart value [column] actually expects,
/// based on its real `information_schema`/`INFORMATION_SCHEMA` `data_type`
/// (`table_columns_provider.dart` already fetches this per-database — no
/// guessing from the CSV content itself, since the same logical column can
/// be typed differently, or not at all yet, before it's created).
///
/// Returns `int`/`double`/`bool`/`String`/`null` only — deliberately never
/// a `DateTime` object. Date/timestamp values are validated (a genuinely
/// unparseable date throws [CsvValueError]) but returned as a canonical
/// ISO-8601 [String], not a `DateTime` instance: `SqlServerConnector`'s
/// side of `DbConnector.insertRows` runs on a throwaway [Isolate], and
/// `DateTime` is not guaranteed to be a value class Dart's isolate message
/// passing can transfer, unlike the primitives — keeping every coerced
/// value isolate-safe means both connectors can share the exact same
/// coerced row map without special-casing either one.
///
/// A type name not recognized in either engine's list below (e.g. `uuid`,
/// `json`, `text`) passes through as the trimmed raw string — correct for
/// most such types (a well-formed UUID/JSON string binds fine as text
/// against those columns in both engines) and a safe default otherwise.
Object? coerceCsvValue(String? raw, TableColumn column, DbEngine engine) {
  final trimmed = raw?.trim();
  if (trimmed == null || trimmed.isEmpty) {
    if (column.nullable || column.defaultValue != null) return null;
    throw CsvValueError(
        'La columna "${column.name}" no admite valores vacíos y no tiene un valor por defecto.');
  }

  final type = column.dataType.toLowerCase();
  final intTypes =
      engine == DbEngine.postgres ? _postgresIntTypes : _sqlServerIntTypes;
  final decimalTypes = engine == DbEngine.postgres
      ? _postgresDecimalTypes
      : _sqlServerDecimalTypes;
  final boolTypes =
      engine == DbEngine.postgres ? _postgresBoolTypes : _sqlServerBoolTypes;
  final dateTimeTypes = engine == DbEngine.postgres
      ? _postgresDateTimeTypes
      : _sqlServerDateTimeTypes;
  final timeTypes =
      engine == DbEngine.postgres ? _postgresTimeTypes : _sqlServerTimeTypes;

  if (intTypes.contains(type)) {
    final value = int.tryParse(trimmed);
    if (value == null) {
      throw CsvValueError(
          '"$trimmed" no es un número entero válido para la columna "${column.name}" ($type).');
    }
    return value;
  }
  if (decimalTypes.contains(type)) {
    final value = double.tryParse(trimmed);
    if (value == null) {
      throw CsvValueError(
          '"$trimmed" no es un número válido para la columna "${column.name}" ($type).');
    }
    return value;
  }
  if (boolTypes.contains(type)) {
    final value = _parseBool(trimmed);
    if (value == null) {
      throw CsvValueError(
          '"$trimmed" no es un valor booleano válido para la columna "${column.name}" ($type).');
    }
    return value;
  }
  if (dateTimeTypes.contains(type)) {
    final value = DateTime.tryParse(trimmed);
    if (value == null) {
      throw CsvValueError(
          '"$trimmed" no es una fecha/hora válida para la columna "${column.name}" ($type).');
    }
    return value.toIso8601String();
  }
  if (timeTypes.contains(type)) {
    if (!_timeOfDayPattern.hasMatch(trimmed)) {
      throw CsvValueError(
          '"$trimmed" no es una hora válida para la columna "${column.name}" ($type).');
    }
    return trimmed;
  }
  return trimmed;
}

/// Matches a bare time-of-day value ("14:30", "14:30:00", "9:05:00.123") —
/// deliberately not routed through `DateTime.tryParse`, which requires a
/// date component and would reject every one of these.
final _timeOfDayPattern = RegExp(r'^\d{1,2}:\d{2}(:\d{2}(\.\d+)?)?$');

bool? _parseBool(String value) {
  switch (value.toLowerCase()) {
    case 'true':
    case '1':
    case 't':
    case 'yes':
    case 'y':
      return true;
    case 'false':
    case '0':
    case 'f':
    case 'no':
    case 'n':
      return false;
    default:
      return null;
  }
}
