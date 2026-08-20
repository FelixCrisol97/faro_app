/// One column of a table/view, fetched lazily by "Ver estructura de campos"
/// (see `features/consulta/application/table_columns_provider.dart`) —
/// transient, never persisted.
class TableColumn {
  const TableColumn({
    required this.name,
    required this.dataType,
    required this.nullable,
    required this.isPrimaryKey,
    this.defaultValue,
    this.characterMaxLength,
    this.numericPrecision,
    this.numericScale,
    this.isIdentity = false,
  });

  final String name;
  final String dataType;
  final bool nullable;
  final bool isPrimaryKey;
  final String? defaultValue;
  final int? characterMaxLength;
  final int? numericPrecision;
  final int? numericScale;

  /// True for an auto-generated identity column (SQL Server `IDENTITY`,
  /// Postgres `GENERATED ... AS IDENTITY`) — unlike a Postgres `SERIAL`
  /// column (whose default is a visible `nextval(...)` expression), both
  /// of these report a `NULL` [defaultValue] despite the engine still
  /// filling the value in automatically. Needed so a caller that treats
  /// "not nullable and no default" as "must be provided" (CSV import)
  /// doesn't wrongly demand a value for a column no one is meant to set by
  /// hand.
  final bool isIdentity;

  /// Row shape produced by `table_columns_provider.dart`'s catalog query:
  /// `column_name, data_type, is_nullable, column_default, char_len,
  /// num_precision, num_scale, is_primary_key, is_identity`.
  ///
  /// `is_primary_key`/`is_identity` normalize `true`/`1`/`'YES'` — Postgres's
  /// boolean/`is_identity` expressions come back as a Dart `bool`/`'YES'`
  /// string, SQL Server's `CASE WHEN...THEN 1 ELSE 0 END`/`COLUMNPROPERTY`
  /// (T-SQL has no boolean column type) come back as an `int`.
  factory TableColumn.fromRow(Map<String, Object?> row) {
    final pk = row['is_primary_key'];
    final identity = row['is_identity'];
    return TableColumn(
      name: row['column_name'].toString(),
      dataType: row['data_type'].toString(),
      nullable: row['is_nullable'].toString().toUpperCase() == 'YES',
      isPrimaryKey: pk == true || pk == 1,
      defaultValue: row['column_default']?.toString(),
      characterMaxLength: (row['char_len'] as num?)?.toInt(),
      numericPrecision: (row['num_precision'] as num?)?.toInt(),
      numericScale: (row['num_scale'] as num?)?.toInt(),
      isIdentity:
          identity == true || identity == 1 || identity?.toString().toUpperCase() == 'YES',
    );
  }
}
