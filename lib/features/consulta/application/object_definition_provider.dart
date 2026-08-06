import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/constants/db_engine.dart';
import '../../../data/models/schema_object.dart';
import '../../../data/providers/servers_providers.dart';

/// Identifies which object's CREATE script to fetch. A record (not
/// [SchemaObject] itself) so `.family` gets structural equality for free —
/// [SchemaObject] doesn't override `==`/`hashCode`.
typedef ObjectDefinitionKey = ({
  String serverId,
  String databaseId,
  String schema,
  String objectName,
  SchemaObjectType type,
  String? parentTable,
});

/// Backs "Generar script CREATE" for vistas/funciones/procedimientos/
/// triggers (tables use `sql_script_generator.dart`'s client-side generator
/// instead — neither engine has a simple built-in for CREATE TABLE DDL).
/// Same session-cache shape as `schemaExplorerProvider`/`tableColumnsProvider`.
///
/// `ref.read`, not `ref.watch`, on `serversProvider` — see
/// `schema_explorer_provider.dart`'s doc comment for why watching it here
/// would re-trigger a real fetch for every cached key whenever ANY database
/// changed anywhere, not just this one.
final objectDefinitionProvider =
    FutureProvider.family<String, ObjectDefinitionKey>((ref, key) async {
  final resolved = await resolveConnectorAndConfig(ref,
      serverId: key.serverId, databaseId: key.databaseId);
  if (resolved == null) return '';

  final result = await resolved.connector.runQuery(
      resolved.config, _definitionQueryFor(resolved.config.engine, key));
  final raw =
      result.rows.isEmpty ? '' : (result.rows.first[0]?.toString() ?? '');

  // pg_get_viewdef only returns the SELECT body, not a full CREATE
  // statement — every other branch (functions/procedures/triggers on
  // Postgres, everything on SQL Server via OBJECT_DEFINITION) already
  // returns a complete, ready-to-run script.
  if (resolved.config.engine == DbEngine.postgres &&
      key.type == SchemaObjectType.view) {
    return 'CREATE OR REPLACE VIEW ${key.schema}.${key.objectName} AS\n$raw';
  }
  return raw;
});

String _definitionQueryFor(DbEngine engine, ObjectDefinitionKey key) {
  if (engine == DbEngine.sqlServer) {
    // Works uniformly for view/function/procedure/trigger.
    return "SELECT OBJECT_DEFINITION(OBJECT_ID(${_sqlLiteral('${key.schema}.${key.objectName}')}));";
  }
  final s = _sqlLiteral(key.schema);
  final n = _sqlLiteral(key.objectName);
  return switch (key.type) {
    SchemaObjectType.view =>
      "SELECT pg_get_viewdef('\"${key.schema}\".\"${key.objectName}\"'::regclass, true);",
    SchemaObjectType.function || SchemaObjectType.procedure =>
      "SELECT pg_get_functiondef(p.oid) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = $s AND p.proname = $n;",
    SchemaObjectType.trigger =>
      "SELECT pg_get_triggerdef(t.oid, true) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = $s AND c.relname = ${_sqlLiteral(key.parentTable ?? '')} AND t.tgname = $n AND NOT t.tgisinternal;",
    SchemaObjectType.table => '',
  };
}

String _sqlLiteral(String value) => "'${value.replaceAll("'", "''")}'";
