import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/datasources/database_connection_config.dart';
import '../../data/models/database_entry.dart';
import '../../data/models/server.dart';
import '../../data/providers/core_providers.dart';
import '../../data/providers/servers_providers.dart';

/// "Probar conexión" — no context/UI of its own (the result lives in
/// `DatabaseEntry.testStatus`/`testError`, already shared Riverpod state, so
/// every place watching it — Administración's `_StatusPill`, Consulta's
/// sidebar — updates on its own). Originally only reachable from
/// Administración's `_DatabaseRow`, now also offered from Consulta's
/// sidebar. Extracted here (rather than duplicated) so both call sites
/// share the exact same logic — same pattern as the dialogs in this folder.
Future<void> testDatabaseConnection(
    WidgetRef ref, Server? server, DatabaseEntry database) async {
  final notifier = ref.read(serversProvider.notifier);
  notifier.setConnectionTestStatus(
      server?.id, database.id, ConnectionTestStatus.testing);
  try {
    final connector = ref.read(dbConnectorsProvider)[database.engine]!;
    final credentials = await ref
        .read(credentialsRepositoryProvider)
        .resolve(server?.id, database.id);
    final config = DatabaseConnectionConfig.forDatabase(
        database: database, credentials: credentials);
    await connector.testConnection(config);
    notifier.setConnectionTestStatus(
        server?.id, database.id, ConnectionTestStatus.connected);
  } catch (e, st) {
    // Still printed for anyone debugging from a console, but the status
    // itself also carries this via testError — no more "Error" with no way
    // to find out why.
    debugPrint(
        'Probar conexión falló para "${database.name}" (${server?.name ?? 'Sin grupo'}): $e\n$st');
    notifier.setConnectionTestStatus(
        server?.id, database.id, ConnectionTestStatus.failed,
        errorMessage: e.toString());
  }
}
