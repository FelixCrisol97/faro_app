import '../../../core/constants/db_engine.dart';
import '../../../data/models/table_column.dart';

/// Pure SQL-text generators for the schema tree's context-menu actions —
/// no provider/state involved, just string assembly from already-fetched
/// [TableColumn]s. Generated SQL is inserted into the editor via
/// `sqlEditorProvider.notifier.loadText` (see
/// `shared/navigation/tree/schema_object_row.dart`).

String generateSelectScript(DbEngine engine, String schema, String name) =>
    switch (engine) {
      DbEngine.postgres => 'SELECT * FROM $schema.$name LIMIT 100;',
      DbEngine.sqlServer => 'SELECT TOP 100 * FROM $schema.$name;',
    };

/// Non-PK columns go in `SET`, PK columns go in `WHERE` (AND-joined). With
/// no primary key there's nothing sensible to filter on, so `WHERE` becomes
/// a TODO comment instead of guessing.
String generateUpdateScript(
  DbEngine engine,
  String schema,
  String table,
  List<TableColumn> columns,
) {
  final pk = columns.where((c) => c.isPrimaryKey).toList();
  final setColumns = columns.where((c) => !c.isPrimaryKey).toList();
  final setClause = setColumns.isEmpty
      ? '    -- (sin columnas que actualizar)'
      : setColumns.map((c) => '    ${c.name} = <${c.name}>').join(',\n');
  final whereClause = pk.isEmpty
      ? '    -- TODO: agrega condición WHERE (no se encontró llave primaria)'
      : '    ${pk.map((c) => '${c.name} = <${c.name}>').join(' AND ')}';
  return 'UPDATE $schema.$table\nSET\n$setClause\nWHERE\n$whereClause;';
}

/// Best-effort starting script from column metadata alone — column name,
/// type (with length/precision where applicable), NOT NULL, and a PRIMARY
/// KEY clause if any column is flagged as one. Deliberately does NOT
/// reconstruct foreign keys, indexes, check constraints, or non-trivial
/// defaults: neither engine exposes a simple built-in for full CREATE TABLE
/// DDL the way `pg_get_functiondef`/`OBJECT_DEFINITION` do for routines, so
/// this is meant as a working starting point, not a faithful export.
String generateCreateTableScript(
  DbEngine engine,
  String schema,
  String table,
  List<TableColumn> columns,
) {
  final lines = columns
      .map((c) =>
          '    ${c.name} ${_typeString(c)}${c.nullable ? '' : ' NOT NULL'}')
      .toList();
  final pkColumns = columns.where((c) => c.isPrimaryKey).map((c) => c.name);
  if (pkColumns.isNotEmpty) {
    lines.add('    PRIMARY KEY (${pkColumns.join(', ')})');
  }
  return 'CREATE TABLE $schema.$table (\n${lines.join(',\n')}\n);';
}

String _typeString(TableColumn c) {
  if (c.characterMaxLength != null) return '${c.dataType}(${c.characterMaxLength})';
  if (c.numericPrecision != null && c.numericScale != null) {
    return '${c.dataType}(${c.numericPrecision},${c.numericScale})';
  }
  return c.dataType;
}
