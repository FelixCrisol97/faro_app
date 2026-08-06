import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/models/database_entry.dart';
import '../../data/models/server.dart';
import '../../data/providers/core_providers.dart';
import '../../data/providers/servers_providers.dart';
import 'app_button.dart';
import 'app_dialog.dart';

/// "Eliminar servidor" — confirms, then clears its saved credentials (the
/// server's own default plus every one of its databases' overrides, so no
/// orphaned secret is left in the OS credential store) before removing it.
/// Originally only reachable from Administración's server card, now also
/// offered from Consulta's sidebar — same extraction pattern as the other
/// files in this folder.
Future<void> showRemoveServerDialog(
    BuildContext context, WidgetRef ref, Server server) async {
  final confirmed = await showAppDialog<bool>(
    context: context,
    title: 'Eliminar servidor',
    body: Text(
      '¿Eliminar "${server.name}" y sus ${server.databases.length} base(s) de datos? '
      'Esta acción no se puede deshacer.',
    ),
    actions: [
      AppButton(
          label: 'Cancelar',
          autofocus: true,
          onPressed: () => Navigator.of(context).pop(false)),
      AppButton(
        label: 'Eliminar',
        variant: AppButtonVariant.primary,
        onPressed: () => Navigator.of(context).pop(true),
      ),
    ],
  );
  if (confirmed != true) return;

  final credentialsRepo = ref.read(credentialsRepositoryProvider);
  await credentialsRepo.deleteServerCredentials(server.id);
  for (final db in server.databases) {
    await credentialsRepo.clearDatabaseOverride(db.id);
  }
  ref.read(serversProvider.notifier).removeServer(server.id);
}

/// "Eliminar base de datos" — confirms, then clears its saved credential
/// override (if any) before removing it. Same extraction pattern as
/// [showRemoveServerDialog].
Future<void> showRemoveDatabaseDialog(BuildContext context, WidgetRef ref,
    Server server, DatabaseEntry database) async {
  final confirmed = await showAppDialog<bool>(
    context: context,
    title: 'Eliminar base de datos',
    body:
        Text('¿Eliminar "${database.name}"? Esta acción no se puede deshacer.'),
    actions: [
      AppButton(
          label: 'Cancelar',
          autofocus: true,
          onPressed: () => Navigator.of(context).pop(false)),
      AppButton(
        label: 'Eliminar',
        variant: AppButtonVariant.primary,
        onPressed: () => Navigator.of(context).pop(true),
      ),
    ],
  );
  if (confirmed != true) return;

  await ref.read(credentialsRepositoryProvider).clearDatabaseOverride(database.id);
  ref.read(serversProvider.notifier).removeDatabase(server.id, database.id);
}
