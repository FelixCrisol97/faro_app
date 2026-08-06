import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../core/constants/db_engine.dart';
import '../datasources/db_connector.dart';
import '../datasources/postgres_connector.dart';
import '../datasources/sqlserver_connector.dart';
import '../repositories/credentials_repository.dart';
import '../repositories/csv_import_service.dart';
import '../repositories/database_discovery_service.dart';
import '../repositories/favoritos_repository.dart';
import '../repositories/historial_repository.dart';
import '../repositories/query_execution_service.dart';
import '../repositories/servers_repository.dart';
import '../repositories/settings_repository.dart';

/// Overridden in `main.dart` with the real instance obtained from
/// `SharedPreferences.getInstance()` before `runApp` — every provider that
/// needs persistence depends on this one, directly or indirectly.
final sharedPreferencesProvider = Provider<SharedPreferences>((ref) {
  throw UnimplementedError(
      'sharedPreferencesProvider must be overridden in main() before runApp.');
});

final settingsRepositoryProvider = Provider<SettingsRepository>((ref) {
  return SettingsRepository(ref.watch(sharedPreferencesProvider));
});

final serversRepositoryProvider = Provider<ServersRepository>((ref) {
  return ServersRepository(ref.watch(sharedPreferencesProvider));
});

final favoritosRepositoryProvider = Provider<FavoritosRepository>((ref) {
  return FavoritosRepository(ref.watch(sharedPreferencesProvider));
});

final historialRepositoryProvider = Provider<HistorialRepository>((ref) {
  return HistorialRepository(ref.watch(sharedPreferencesProvider));
});

final credentialsRepositoryProvider = Provider<CredentialsRepository>((ref) {
  return CredentialsRepository();
});

/// One [DbConnector] per engine, shared across every server of that engine.
final dbConnectorsProvider = Provider<Map<DbEngine, DbConnector>>((ref) {
  return {
    DbEngine.postgres: PostgresConnector(),
    DbEngine.sqlServer: SqlServerConnector()
  };
});

final queryExecutionServiceProvider = Provider<QueryExecutionService>((ref) {
  return QueryExecutionService(connectors: ref.watch(dbConnectorsProvider));
});

final databaseDiscoveryServiceProvider =
    Provider<DatabaseDiscoveryService>((ref) {
  return DatabaseDiscoveryService(connectors: ref.watch(dbConnectorsProvider));
});

final csvImportServiceProvider = Provider<CsvImportService>((ref) {
  return CsvImportService(connectors: ref.watch(dbConnectorsProvider));
});
