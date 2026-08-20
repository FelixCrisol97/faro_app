import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/constants/db_engine.dart';
import '../../../data/datasources/database_connection_config.dart';
import '../../../data/providers/core_providers.dart';
import '../../../data/providers/servers_providers.dart';
import 'query_tabs_providers.dart';

/// Table names to suggest after `FROM <partial>` (README.md
/// "Autocompletado de nombres de tabla") — queries `information_schema.tables`
/// (supported by both PostgreSQL and SQL Server) against every database
/// currently selected for the query (`selectedQueryTargetsProvider`),
/// unioning the results.
///
/// Used to pick one "representative" database off the *active server*
/// instead — a real bug, since with "Consulta masiva" that representative
/// pick could land on a database that isn't even the one the user actually
/// selected (a table only the real target had, e.g. a user-created
/// `test_1`, would silently never be suggested). With "Consulta masiva"
/// off there's exactly one selected database at a time, so this now
/// queries precisely that one, no ambiguity; with it on, suggestions come
/// from the union of every selected database — still a best-effort editor
/// convenience, not a correctness-critical feature, so a servidor grouping
/// genuinely unrelated databases just gets a wider (not wrong) suggestion
/// list. Any one target's failure (unreachable host, bad credentials,
/// etc.) is swallowed and just contributes nothing, rather than surfacing
/// an error in the editor or failing the others.
final tableNamesProvider =
    FutureProvider.autoDispose<List<String>>((ref) async {
  final targets = ref.watch(selectedQueryTargetsProvider);
  return _fetchTableNames(ref, targets);
});

/// Same convenience, scoped to one query tab's fixed target instead of
/// whatever's selected in the sidebar — a tab's `FROM <partial>` should
/// suggest tables from *its own* database, not from the home tab's
/// selection. `.autoDispose` is correct here (unlike the tab's editor/run
/// state, which deliberately isn't) — losing cached suggestions on a tab
/// switch is harmless, they're refetched cheaply on demand.
final tabTableNamesProvider =
    FutureProvider.autoDispose.family<List<String>, String>((ref, tabId) async {
  final target = ref.watch(resolvedTabTargetProvider(tabId));
  return _fetchTableNames(ref, target == null ? const [] : [target]);
});

Future<List<String>> _fetchTableNames(Ref ref, List<QueryTarget> targets) =>
    _fetchDistinctFirstColumn(ref, targets, _queryFor);

/// Shared by [_fetchTableNames]/[_fetchColumnNames] — real duplication
/// fixed 2026-08-03 (AUDITORIA_CODIGO.md). Both used to repeat this exact
/// ~20-line "for every target: resolve credentials/config, run one query,
/// take the first column of every row, union+sort across targets,
/// swallow any single target's failure rather than surfacing it" shape,
/// differing only in which query each one runs.
Future<List<String>> _fetchDistinctFirstColumn(
  Ref ref,
  List<QueryTarget> targets,
  String Function(DbEngine engine) queryFor,
) async {
  if (targets.isEmpty) return [];

  final connectors = ref.read(dbConnectorsProvider);
  final credentialsRepository = ref.read(credentialsRepositoryProvider);

  final results = await Future.wait(targets.map((target) async {
    if (target.database.host.isEmpty) return const <String>[];
    final connector = connectors[target.database.engine];
    if (connector == null) return const <String>[];
    try {
      final credentials = await credentialsRepository.resolve(
          target.server?.id, target.database.id);
      final config = DatabaseConnectionConfig.forDatabase(
          database: target.database, credentials: credentials);
      final result =
          await connector.runQuery(config, queryFor(target.database.engine));
      if (result.columns.isEmpty) return const <String>[];
      return result.rows.map((row) => row[0].toString()).toList();
    } catch (_) {
      return const <String>[];
    }
  }));

  final union = <String>{for (final names in results) ...names};
  return union.toList()..sort();
}

String _queryFor(DbEngine engine) => switch (engine) {
      DbEngine.postgres => "SELECT table_name FROM information_schema.tables "
          "WHERE table_schema NOT IN ('pg_catalog', 'information_schema') ORDER BY table_name",
      DbEngine.sqlServer =>
        "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME",
    };

/// Column names to suggest after `WHERE`/`AND`/`OR`/`ON`/`HAVING`/`ORDER BY`/
/// `GROUP BY`/`SELECT` (`sql_editor.dart`'s second autocomplete trigger) —
/// same "query information_schema against every selected database, union
/// results" shape as [tableNamesProvider], parameterized by whichever
/// table(s) are already referenced via `FROM`/`JOIN` in the query text.
/// Keyed by a single sorted, comma-joined String (not a raw List/Set) so
/// Riverpod's family equality check actually matches across rebuilds — two
/// freshly-built Lists with the same elements aren't `==` to each other,
/// but two identical Strings are, so an unchanged set of referenced tables
/// reuses the cached fetch instead of re-querying on every keystroke.
final columnNamesProvider = FutureProvider.autoDispose
    .family<List<String>, String>((ref, tablesKey) async {
  if (tablesKey.isEmpty) return [];
  final targets = ref.watch(selectedQueryTargetsProvider);
  return _fetchColumnNames(ref, targets, tablesKey.split(','));
});

/// Same, scoped to one query tab's fixed target instead of the sidebar's
/// selection — see [tabTableNamesProvider]'s doc comment for why. The key
/// is `(tabId, tablesKey)` — a record, same "structural equality for free"
/// reasoning already used for `TableColumnsKey`/`SchemaExplorerKey`
/// elsewhere in this app.
final tabColumnNamesProvider = FutureProvider.autoDispose
    .family<List<String>, ({String tabId, String tablesKey})>((ref, key) async {
  if (key.tablesKey.isEmpty) return [];
  final target = ref.watch(resolvedTabTargetProvider(key.tabId));
  return _fetchColumnNames(
      ref, target == null ? const [] : [target], key.tablesKey.split(','));
});

Future<List<String>> _fetchColumnNames(
    Ref ref, List<QueryTarget> targets, List<String> tables) {
  if (tables.isEmpty) return Future.value(const []);
  return _fetchDistinctFirstColumn(
      ref, targets, (engine) => _columnsQueryFor(engine, tables));
}

/// No schema filter (unlike `table_columns_provider.dart`'s equivalent
/// query) — the editor only ever has a bare table name typed after `FROM`,
/// never a schema-qualified one, so there's nothing to filter by here.
String _columnsQueryFor(DbEngine engine, List<String> tables) {
  final list = tables.map(_sqlLiteral).join(', ');
  return switch (engine) {
    DbEngine.postgres =>
      'SELECT DISTINCT column_name FROM information_schema.columns '
          'WHERE table_name IN ($list) ORDER BY column_name',
    DbEngine.sqlServer =>
      'SELECT DISTINCT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS '
          'WHERE TABLE_NAME IN ($list) ORDER BY COLUMN_NAME',
  };
}

String _sqlLiteral(String value) => "'${value.replaceAll("'", "''")}'";
