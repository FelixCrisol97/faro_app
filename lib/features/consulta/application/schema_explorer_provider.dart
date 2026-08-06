import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/constants/db_engine.dart';
import '../../../data/models/schema_object.dart';
import '../../../data/providers/servers_providers.dart';

/// Identifies which database's catalog to introspect — a servidor's id
/// alone isn't enough since credentials/host live per [DatabaseEntry].
typedef SchemaExplorerKey = ({String serverId, String databaseId});

/// [SchemaExplorerKey] plus which object type to fetch — see
/// [schemaTypeExplorerProvider].
typedef SchemaTypeExplorerKey = ({
  String serverId,
  String databaseId,
  SchemaObjectType type,
});

/// Backs the sidebar's per-database, per-category catalog browsing (tablas,
/// vistas, funciones, procedimientos, triggers — CONTEXTO_SESIONES.md's
/// SSMS/pgAdmin style ask). One query per **object type**, not per database:
///
/// **Real bug (2026-07-19), fixed:** this used to be a single
/// `schemaExplorerProvider(SchemaExplorerKey)` that fetched every object
/// type for a database in one `UNION ALL` round-trip, as soon as "Cargar
/// estructura" was tapped — fine for a handful of objects, but a real
/// client database with thousands of tables/functions made that one query
/// (and then building/rendering a tree with every one of those rows at
/// once) freeze the UI. Splitting into one provider **per (database,
/// object type)** and only having the sidebar `ref.watch` a given type's
/// entry once its category header is actually expanded (see
/// `_SchemaTypeGroup` in `server_sidebar.dart`) means expanding "Tablas"
/// never touches "Funciones" at all, and vice versa — true lazy loading,
/// not just a lazy *button press* in front of an eager fetch.
///
/// Still a plain `.family` (not `.autoDispose`): once a category has been
/// expanded and fetched, it stays cached for the rest of the session —
/// collapsing/re-expanding never re-queries, only that category's own
/// "Actualizar" (`ref.invalidate`) does.
///
/// Unlike `table_names_provider.dart`'s best-effort autocomplete (which
/// swallows errors into an empty list), this does NOT catch exceptions —
/// it's a deliberate user action, so a connection failure should surface as
/// `AsyncValue.error` in the tree with a retry affordance, not silently look
/// like "no objects".
///
/// **Reads `serversProvider` via `ref.read`, not `watch`** — see the
/// analogous, previously-diagnosed bug on the old whole-database version of
/// this provider: `serversProvider` gets a brand-new list identity on every
/// unrelated database change (toggling selection, mode, etc.), so `watch`
/// here would re-fetch every cached category on every one of those changes.
/// This only needs a one-shot config snapshot at fetch time.
final schemaTypeExplorerProvider = FutureProvider.family<List<SchemaObject>,
    SchemaTypeExplorerKey>((ref, key) async {
  final resolved = await resolveConnectorAndConfig(ref,
      serverId: key.serverId, databaseId: key.databaseId);
  if (resolved == null) return [];

  final result = await resolved.connector.runQuery(resolved.config,
      _catalogQueryFor(resolved.config.engine, key.type));
  return result.rowMaps
      .map((row) => SchemaObject.fromRow(row, type: key.type))
      .toList();
});

/// [SchemaExplorerKey] plus a name filter — see [schemaSearchProvider].
typedef SchemaSearchKey = ({String serverId, String databaseId, String query});

/// Filters across **every** object type at once, for the sidebar's schema
/// search box — a real client database can have thousands of tables and
/// functions (see [schemaTypeExplorerProvider]'s doc comment on why
/// browsing is split per type), so "search everything" can't mean "fetch
/// everything and filter it in Dart" without reintroducing that exact
/// freeze. Instead the name filter is pushed into the SQL (`ILIKE`/`LIKE`)
/// so each of the five per-type queries only ever returns matching rows,
/// then the (small) results are merged client-side. Runs all five in
/// parallel, same pattern as `QueryExecutionService`'s multi-target runs.
///
/// `.family` keyed by the literal query string, so a fresh keystroke is a
/// fresh cache entry — the sidebar debounces input before updating the
/// query that feeds this (see `_SchemaObjectsTreeState._onSearchChanged`)
/// so this doesn't fire a round-trip per keystroke.
final schemaSearchProvider =
    FutureProvider.family<List<SchemaObject>, SchemaSearchKey>((ref, key) async {
  final resolved = await resolveConnectorAndConfig(ref,
      serverId: key.serverId, databaseId: key.databaseId);
  if (resolved == null) return [];

  final perType = await Future.wait(SchemaObjectType.values.map((type) async {
    final result = await resolved.connector.runQuery(resolved.config,
        _catalogQueryFor(resolved.config.engine, type, filter: key.query));
    return result.rowMaps.map((row) => SchemaObject.fromRow(row, type: type));
  }));
  return perType.expand((objects) => objects).toList();
});

/// One query per (engine, object type), optionally narrowed to names
/// containing [filter] (case-insensitive) — always projects `schema_name,
/// object_name, parent_object` so [SchemaObject.fromRow] can parse either
/// engine's result the same way. `parent_object` is NULL except for
/// triggers (see [SchemaObject.parentTable]'s doc comment for why triggers
/// need it).
///
/// [filter] is embedded directly into the SQL text (neither driver here
/// exposes parameterized queries — see `DbConnector.runQuery`), so it's
/// escaped by doubling any `'` before being wrapped in a `LIKE` literal —
/// standard SQL string-literal escaping, sufficient since the value only
/// ever lands inside one bounded `'...'` slot.
String _catalogQueryFor(DbEngine engine, SchemaObjectType type,
    {String? filter}) {
  final pattern =
      (filter == null || filter.isEmpty) ? null : '%${_escapeLike(filter)}%';
  return switch ((engine, type)) {
      (DbEngine.postgres, SchemaObjectType.table) => '''
SELECT table_schema AS schema_name, table_name AS object_name, NULL AS parent_object
FROM information_schema.tables
WHERE table_type = 'BASE TABLE' AND table_schema NOT IN ('pg_catalog', 'information_schema')
${pattern == null ? '' : "AND table_name ILIKE '$pattern'"}
ORDER BY 1, 2
''',
      (DbEngine.postgres, SchemaObjectType.view) => '''
SELECT table_schema AS schema_name, table_name AS object_name, NULL AS parent_object
FROM information_schema.tables
WHERE table_type = 'VIEW' AND table_schema NOT IN ('pg_catalog', 'information_schema')
${pattern == null ? '' : "AND table_name ILIKE '$pattern'"}
ORDER BY 1, 2
''',
      (DbEngine.postgres, SchemaObjectType.function) => '''
SELECT n.nspname AS schema_name, p.proname AS object_name, NULL AS parent_object
FROM pg_proc p
JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname NOT IN ('pg_catalog', 'information_schema') AND p.prokind = 'f'
${pattern == null ? '' : "AND p.proname ILIKE '$pattern'"}
ORDER BY 1, 2
''',
      (DbEngine.postgres, SchemaObjectType.procedure) => '''
SELECT n.nspname AS schema_name, p.proname AS object_name, NULL AS parent_object
FROM pg_proc p
JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname NOT IN ('pg_catalog', 'information_schema') AND p.prokind = 'p'
${pattern == null ? '' : "AND p.proname ILIKE '$pattern'"}
ORDER BY 1, 2
''',
      (DbEngine.postgres, SchemaObjectType.trigger) => '''
SELECT DISTINCT event_object_schema AS schema_name, trigger_name AS object_name, event_object_table AS parent_object
FROM information_schema.triggers
${pattern == null ? '' : "WHERE trigger_name ILIKE '$pattern'"}
ORDER BY 1, 2
''',
      (DbEngine.sqlServer, SchemaObjectType.table) => '''
SELECT TABLE_SCHEMA AS schema_name, TABLE_NAME AS object_name, NULL AS parent_object
FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_TYPE = 'BASE TABLE'
${pattern == null ? '' : "AND TABLE_NAME LIKE '$pattern'"}
ORDER BY 1, 2
''',
      (DbEngine.sqlServer, SchemaObjectType.view) => '''
SELECT TABLE_SCHEMA AS schema_name, TABLE_NAME AS object_name, NULL AS parent_object
FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_TYPE = 'VIEW'
${pattern == null ? '' : "AND TABLE_NAME LIKE '$pattern'"}
ORDER BY 1, 2
''',
      (DbEngine.sqlServer, SchemaObjectType.function) => '''
SELECT ROUTINE_SCHEMA AS schema_name, ROUTINE_NAME AS object_name, NULL AS parent_object
FROM INFORMATION_SCHEMA.ROUTINES WHERE ROUTINE_TYPE = 'FUNCTION'
${pattern == null ? '' : "AND ROUTINE_NAME LIKE '$pattern'"}
ORDER BY 1, 2
''',
      (DbEngine.sqlServer, SchemaObjectType.procedure) => '''
SELECT ROUTINE_SCHEMA AS schema_name, ROUTINE_NAME AS object_name, NULL AS parent_object
FROM INFORMATION_SCHEMA.ROUTINES WHERE ROUTINE_TYPE = 'PROCEDURE'
${pattern == null ? '' : "AND ROUTINE_NAME LIKE '$pattern'"}
ORDER BY 1, 2
''',
      (DbEngine.sqlServer, SchemaObjectType.trigger) => '''
SELECT s.name AS schema_name, tr.name AS object_name, o.name AS parent_object
FROM sys.triggers tr
JOIN sys.objects o ON tr.parent_id = o.object_id
JOIN sys.schemas s ON o.schema_id = s.schema_id
WHERE tr.parent_class = 1
${pattern == null ? '' : "AND tr.name LIKE '$pattern'"}
ORDER BY 1, 2
''',
    };
}

String _escapeLike(String raw) => raw.replaceAll("'", "''");

/// The engine's implicit schema — object names inside it are shown bare
/// (e.g. `clientes`); anything else keeps its `esquema.objeto` prefix so the
/// tree stays legible without hiding real schema information.
String defaultSchemaFor(DbEngine engine) => switch (engine) {
      DbEngine.postgres => 'public',
      DbEngine.sqlServer => 'dbo',
    };
