import '../../core/constants/db_engine.dart';
import '../datasources/database_connection_config.dart';
import '../datasources/db_connector.dart';

/// Lists the real databases living on a host, given credentials — used by
/// Administración's "Descubrir bases de datos" so the user doesn't have to
/// already know/type every database name by hand. Bootstraps the connection
/// through [bootstrapDatabase] if given, falling back to the engine's
/// always-present maintenance database (`DbEngine.maintenanceDatabase`)
/// otherwise, then reads the engine's own catalog of sibling databases (a
/// server-wide catalog, readable from *any* database on that instance, not
/// specifically `master`/`postgres`) — the same kind of introspection
/// `table_names_provider.dart` already does for table-name autocomplete,
/// just one level up (databases instead of tables).
///
/// **Real failure, 2026-07-22:** a client's SQL Server login had access to
/// its own bodega databases but not to `master` at all (a common hardening
/// practice — restricting which logins may connect to system databases) —
/// discovery failed outright with "No se pudo conectar" before it ever got
/// to list anything, even though the same login could query fine once
/// connected to a real database. `bootstrapDatabase` lets a caller that
/// already has at least one registered database for this server (the
/// overwhelmingly common case — discovery/"add all" exist precisely to find
/// *more* bodegas after the first one) connect through a database name
/// already known to work, instead of blindly trying `master`/`postgres`
/// every time.
class DatabaseDiscoveryService {
  const DatabaseDiscoveryService({required this.connectors});

  final Map<DbEngine, DbConnector> connectors;

  Future<List<String>> discover({
    required DbEngine engine,
    required String host,
    required int port,
    required String username,
    required String password,
    String? bootstrapDatabase,
  }) async {
    final config = DatabaseConnectionConfig(
      engine: engine,
      host: host,
      port: port,
      databaseName: bootstrapDatabase ?? engine.maintenanceDatabase,
      username: username,
      password: password,
    );
    final sql = switch (engine) {
      // `datistemplate = false` excludes template0/template1 only —
      // deliberately not excluding `postgres` itself by name, since it can
      // in principle hold real data; the user can just leave it unchecked
      // in the results list if not.
      DbEngine.postgres =>
        'SELECT datname FROM pg_database WHERE datistemplate = false ORDER BY datname',
      // database_id 1-4 (master/tempdb/model/msdb) are a hard engine
      // boundary, never user databases — unlike Postgres's `postgres`,
      // there's no ambiguous case here worth surfacing.
      DbEngine.sqlServer =>
        'SELECT name FROM sys.databases WHERE database_id > 4 ORDER BY name',
    };

    final result = await connectors[engine]!.runQuery(config, sql);
    if (result.columns.isEmpty) return const [];
    return [for (final row in result.rows) '${row[0]}'];
  }
}
