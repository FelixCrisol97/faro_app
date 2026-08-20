/// One catalog object (table/view/function/procedure/trigger) discovered by
/// the sidebar's per-database "Cargar estructura" (see
/// `features/consulta/application/schema_explorer_provider.dart`). Entirely
/// transient — fetched from the engine's catalog on demand, never persisted
/// alongside `DatabaseEntry`.
enum SchemaObjectType {
  table,
  view,
  function,
  procedure,
  trigger;

  String get label => switch (this) {
        SchemaObjectType.table => 'Tablas',
        SchemaObjectType.view => 'Vistas',
        SchemaObjectType.function => 'Funciones',
        SchemaObjectType.procedure => 'Procedimientos',
        SchemaObjectType.trigger => 'Triggers',
      };
}

class SchemaObject {
  const SchemaObject({
    required this.type,
    required this.schema,
    required this.name,
    this.parentTable,
  });

  final SchemaObjectType type;

  /// e.g. `public` (PostgreSQL) or `dbo` (SQL Server).
  final String schema;
  final String name;

  /// Only populated for [SchemaObjectType.trigger] — a Postgres trigger
  /// name is unique per table, not globally, so `pg_get_triggerdef` (used
  /// by `object_definition_provider.dart`'s "Generar script CREATE") needs
  /// the parent table to look it up unambiguously.
  final String? parentTable;

  /// Row shape produced by [schemaTypeExplorerProvider]'s per-type catalog
  /// query: `schema_name, object_name, parent_object`. [type] comes from the
  /// query's own key rather than a row column — each query now fetches
  /// exactly one object type (see that provider's doc comment for why).
  factory SchemaObject.fromRow(Map<String, Object?> row,
      {required SchemaObjectType type}) {
    return SchemaObject(
      type: type,
      schema: row['schema_name'].toString(),
      name: row['object_name'].toString(),
      parentTable: row['parent_object']?.toString(),
    );
  }
}
