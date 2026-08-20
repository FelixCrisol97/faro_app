import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/constants/db_engine.dart';
import '../../../data/models/table_column.dart';
import '../../../data/providers/servers_providers.dart';

/// Identifies which table/view's columns to fetch — a record (not a class)
/// so `.family` gets structural equality for free, same reasoning as
/// `SchemaExplorerKey` in `schema_explorer_provider.dart`.
typedef TableColumnsKey = ({
  String? serverId,
  String databaseId,
  String schema,
  String objectName,
});

/// Backs the sidebar's per-object "Ver estructura de campos" and the
/// "Generar UPDATE"/"Generar script CREATE" (tables) context-menu actions —
/// all three need the same column list. Same session-cache shape as
/// `schemaExplorerProvider`: a plain `.family` (not `.autoDispose`), fetched
/// only when first read, errors surface as `AsyncValue.error` rather than
/// being swallowed (this is always a deliberate user action).
///
/// `ref.read`, not `ref.watch`, on `serversProvider` — see
/// `schema_explorer_provider.dart`'s doc comment for why watching it here
/// would re-trigger a real fetch for every cached key whenever ANY database
/// changed anywhere, not just this one.
final tableColumnsProvider =
    FutureProvider.family<List<TableColumn>, TableColumnsKey>(
        (ref, key) async {
  final resolved = await resolveConnectorAndConfig(ref,
      serverId: key.serverId, databaseId: key.databaseId);
  if (resolved == null) return [];

  final sql = _columnsQueryFor(
      resolved.config.engine, key.schema, key.objectName);
  final result = await resolved.connector.runQuery(resolved.config, sql);
  return result.rowMaps.map(TableColumn.fromRow).toList();
});

/// Columns + a `LEFT JOIN` flagging primary-key membership, in one
/// round-trip per engine — `schema`/`objectName` come from the engine's own
/// catalog (`schemaExplorerProvider`), not raw user input, but are still
/// quote-escaped via [_sqlLiteral] out of caution.
String _columnsQueryFor(DbEngine engine, String schema, String objectName) {
  final s = _sqlLiteral(schema);
  final t = _sqlLiteral(objectName);
  return switch (engine) {
    DbEngine.postgres => '''
SELECT c.column_name AS column_name, c.data_type AS data_type, c.is_nullable AS is_nullable,
       c.column_default AS column_default, c.character_maximum_length AS char_len,
       c.numeric_precision AS num_precision, c.numeric_scale AS num_scale,
       (pk.column_name IS NOT NULL) AS is_primary_key, c.is_identity AS is_identity
FROM information_schema.columns c
LEFT JOIN (
  SELECT kcu.column_name
  FROM information_schema.table_constraints tc
  JOIN information_schema.key_column_usage kcu
    ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema
  WHERE tc.constraint_type = 'PRIMARY KEY' AND tc.table_schema = $s AND tc.table_name = $t
) pk ON pk.column_name = c.column_name
WHERE c.table_schema = $s AND c.table_name = $t
ORDER BY c.ordinal_position
''',
    DbEngine.sqlServer => '''
SELECT c.COLUMN_NAME AS column_name, c.DATA_TYPE AS data_type, c.IS_NULLABLE AS is_nullable,
       c.COLUMN_DEFAULT AS column_default, c.CHARACTER_MAXIMUM_LENGTH AS char_len,
       c.NUMERIC_PRECISION AS num_precision, c.NUMERIC_SCALE AS num_scale,
       CASE WHEN pk.COLUMN_NAME IS NOT NULL THEN 1 ELSE 0 END AS is_primary_key,
       CASE WHEN COLUMNPROPERTY(OBJECT_ID(QUOTENAME(c.TABLE_SCHEMA) + '.' + QUOTENAME(c.TABLE_NAME)), c.COLUMN_NAME, 'IsIdentity') = 1 THEN 1 ELSE 0 END AS is_identity
FROM INFORMATION_SCHEMA.COLUMNS c
LEFT JOIN (
  SELECT kcu.COLUMN_NAME
  FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
  JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu
    ON tc.CONSTRAINT_NAME = kcu.CONSTRAINT_NAME AND tc.TABLE_SCHEMA = kcu.TABLE_SCHEMA
  WHERE tc.CONSTRAINT_TYPE = 'PRIMARY KEY' AND tc.TABLE_SCHEMA = $s AND tc.TABLE_NAME = $t
) pk ON pk.COLUMN_NAME = c.COLUMN_NAME
WHERE c.TABLE_SCHEMA = $s AND c.TABLE_NAME = $t
ORDER BY c.ORDINAL_POSITION
''',
  };
}

String _sqlLiteral(String value) => "'${value.replaceAll("'", "''")}'";
