import '../../core/constants/db_engine.dart';
import '../../features/consulta/application/csv_value_coercion.dart';
import '../datasources/database_connection_config.dart';
import '../datasources/db_connector.dart';
import '../models/database_credentials.dart';
import '../models/database_entry.dart';
import '../models/server.dart';
import '../models/table_column.dart';
import 'sql_guard.dart';

/// Fetches the real column list (name, type, nullability, default,
/// primary-key membership) for one table in one database — backed by
/// `table_columns_provider.dart`'s `tableColumnsProvider` at the call site,
/// injected here rather than duplicated so [CsvImportService] stays plain
/// Dart (no Riverpod dependency), same reasoning as
/// `QueryExecutionService`'s injected `CredentialsResolver`.
typedef ColumnsFetcher = Future<List<TableColumn>> Function(
    String? serverId, String databaseId, String schema, String table);

/// The outcome of importing a CSV into one database — same shape/spirit as
/// `DatabaseQueryOutcome` (`data/models/query_result.dart`): [success]
/// means the database was actually reachable and the import ran (even if
/// some individual rows failed — see [failures]); a whole-database
/// rejection (read-only mode, table not found, required columns missing
/// from the CSV, connection failure) is `success: false` with
/// [errorMessage] set instead.
class DatabaseImportOutcome {
  const DatabaseImportOutcome({
    required this.databaseId,
    required this.databaseName,
    required this.serverName,
    required this.success,
    this.inserted = 0,
    this.failures = const [],
    this.errorMessage,
    this.blocked = false,
  });

  final String databaseId;
  final String databaseName;
  final String serverName;
  final bool success;

  /// Rows actually inserted — only meaningful when [success] is true.
  final int inserted;

  /// Per-row failures, `rowIndex` always relative to the original CSV rows
  /// passed to [CsvImportService.importInto] (0-based, header row not
  /// counted) — a coercion failure (bad value for a column's type) and an
  /// insert failure (e.g. duplicate primary key) both land here, so the
  /// caller doesn't need to tell them apart to show the user what went
  /// wrong on which line.
  final List<RowInsertError> failures;

  final String? errorMessage;

  /// True when [success] is false specifically because [DatabaseEntry.mode]
  /// is Solo lectura — same distinction `DatabaseQueryOutcome.blocked`
  /// already makes, so the UI can show the same "protected" treatment
  /// instead of a generic error.
  final bool blocked;
}

/// Imports CSV rows into one table across however many databases the
/// caller asks for, one database at a time (`import_csv_dialog.dart` fans
/// this out with `Future.wait`, same pattern `QueryExecutionService.run`
/// already uses for multi-database queries). Column mapping is automatic
/// by CSV header name (case-insensitive) — no manual mapping UI in this
/// v1 (2026-07-24 decision). Insert-only: a row whose primary key already
/// exists fails that row, never upserts.
class CsvImportService {
  CsvImportService({required Map<DbEngine, DbConnector> connectors})
      : _connectors = connectors;

  final Map<DbEngine, DbConnector> _connectors;

  Future<DatabaseImportOutcome> importInto({
    required Server? server,
    required DatabaseEntry database,
    required String schema,
    required String table,
    required List<String> csvHeaders,
    required List<List<Object?>> csvRows,
    required DatabaseCredentials credentials,
    required ColumnsFetcher fetchColumns,
  }) async {
    DatabaseImportOutcome fail(String message, {bool blocked = false}) =>
        DatabaseImportOutcome(
          databaseId: database.id,
          databaseName: database.name,
          serverName: server?.name ?? 'Sin grupo',
          success: false,
          blocked: blocked,
          errorMessage: message,
        );

    // Importing is always a mutation — enforced the same way
    // QueryExecutionService enforces it for a real INSERT statement, just
    // without needing to parse SQL text to know that's what this is.
    if (database.mode == ServerMode.readOnly) {
      return fail(
        const ReadOnlyViolationException('INSERT', recognizedMutation: true)
            .toString(),
        blocked: true,
      );
    }

    final connector = _connectors[database.engine];
    if (connector == null) {
      return fail('No hay conector registrado para ${database.engine.label}.');
    }

    final List<TableColumn> columns;
    try {
      columns = await fetchColumns(server?.id, database.id, schema, table);
    } catch (e) {
      return fail('No se pudo leer la estructura de la tabla: $e');
    }
    if (columns.isEmpty) {
      return fail('No se encontró la tabla "$schema.$table" en esta base de datos.');
    }

    // CSV header -> real column, case-insensitive; unmatched CSV columns
    // are silently ignored (covers the common case of re-importing a CSV
    // Faro itself exported, which carries an extra `origen_bd` column).
    final byLowerName = {for (final c in columns) c.name.toLowerCase(): c};
    final headerIndexToColumn = <int, TableColumn>{};
    for (var i = 0; i < csvHeaders.length; i++) {
      final match = byLowerName[csvHeaders[i].trim().toLowerCase()];
      if (match != null) headerIndexToColumn[i] = match;
    }
    if (headerIndexToColumn.isEmpty) {
      return fail(
          'Ninguna columna del CSV coincide con las columnas reales de "$table".');
    }

    // Real bug fixed 2026-08-03: two CSV headers that both map to the same
    // real column (duplicate or case-variant headers, e.g. "sku,SKU")
    // used to silently overwrite each other when building each row's map
    // below — whichever header came last in the CSV won, with no
    // indication anything was lost. Reject up front instead of guessing.
    final headersByColumnName = <String, List<String>>{};
    for (var i = 0; i < csvHeaders.length; i++) {
      final match = headerIndexToColumn[i];
      if (match != null) {
        headersByColumnName.putIfAbsent(match.name, () => []).add(csvHeaders[i]);
      }
    }
    final duplicated =
        headersByColumnName.entries.where((e) => e.value.length > 1);
    if (duplicated.isNotEmpty) {
      final detail = duplicated
          .map((e) => '${e.key} (${e.value.join(", ")})')
          .join('; ');
      return fail(
          'El CSV tiene encabezados repetidos que apuntan a la misma columna: $detail.');
    }

    final matchedColumnNames =
        headerIndexToColumn.values.map((c) => c.name).toSet();
    // `isIdentity` matters here: an identity column (SQL Server IDENTITY,
    // Postgres GENERATED ... AS IDENTITY) reports NOT NULL with no visible
    // default, but the engine fills it in automatically — without this
    // check, omitting an autoincrement primary key from the CSV (the
    // normal case) wrongly blocked the whole import.
    final missingRequired = columns.where((c) =>
        !c.nullable &&
        c.defaultValue == null &&
        !c.isIdentity &&
        !matchedColumnNames.contains(c.name));
    if (missingRequired.isNotEmpty) {
      return fail(
          'Faltan columnas obligatorias en el CSV: ${missingRequired.map((c) => c.name).join(', ')}.');
    }

    final orderedColumnNames =
        headerIndexToColumn.values.map((c) => c.name).toList();

    // Coerce every row up front — a row that fails coercion (bad value for
    // its column's type) never reaches the connector at all, but still
    // needs to end up in `failures` keyed by its *original* CSV row
    // index, not its position in the filtered list actually sent to
    // `insertRows` — `originalIndexOfSentRow` is what makes that
    // translation possible once `insertRows` reports back its own
    // (filtered-list-relative) row indices.
    final rowsToInsert = <Map<String, Object?>>[];
    final originalIndexOfSentRow = <int>[];
    final failures = <RowInsertError>[];
    for (var r = 0; r < csvRows.length; r++) {
      final csvRow = csvRows[r];
      try {
        final rowMap = <String, Object?>{};
        for (final entry in headerIndexToColumn.entries) {
          final rawValue =
              entry.key < csvRow.length ? csvRow[entry.key]?.toString() : null;
          rowMap[entry.value.name] =
              coerceCsvValue(rawValue, entry.value, database.engine);
        }
        rowsToInsert.add(rowMap);
        originalIndexOfSentRow.add(r);
      } on CsvValueError catch (e) {
        failures.add(RowInsertError(rowIndex: r, message: e.message));
      }
    }

    var inserted = 0;
    if (rowsToInsert.isNotEmpty) {
      final config = DatabaseConnectionConfig.forDatabase(
          database: database, credentials: credentials);
      // Real bug fixed 2026-08-03 (AUDITORIA_CODIGO.md): an uncaught
      // exception here (e.g. a dropped connection mid-import) used to
      // propagate straight out of `importInto` — since the caller
      // (`import_csv_dialog.dart`) runs one `importInto` per database via
      // `Future.wait`, that would reject the *whole* batch and lose every
      // other database's already-computed outcome too, not just this
      // one's. Every other failure path in this function already returns
      // a `fail(...)` outcome instead of throwing; this is the one spot
      // that didn't.
      final BulkInsertOutcome outcome;
      try {
        outcome = await connector.insertRows(
            config, schema, table, orderedColumnNames, rowsToInsert);
      } catch (e) {
        return fail('No se pudieron insertar los datos: $e');
      }
      inserted = outcome.inserted;
      for (final f in outcome.failures) {
        failures.add(RowInsertError(
            rowIndex: originalIndexOfSentRow[f.rowIndex],
            message: f.message));
      }
    }

    return DatabaseImportOutcome(
      databaseId: database.id,
      databaseName: database.name,
      serverName: server?.name ?? 'Sin grupo',
      success: true,
      inserted: inserted,
      failures: failures,
    );
  }
}
