import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/models/database_credentials.dart';
import '../../data/providers/core_providers.dart';
import '../../data/providers/servers_providers.dart';
import '../../data/repositories/server_config_codec.dart';
import '../utils/file_paths.dart';
import 'app_button.dart';
import 'app_dialog.dart';

/// "Importar configuración" / "Exportar configuración" — used to live as
/// two page-level buttons at the bottom of the now-deleted `features/
/// administracion/` screen; extracted here 2026-08-13 (real gap, missing
/// entirely for a stretch after that screen was absorbed into the tree —
/// user-reported: "no veo la opcion de configuracion") so the tree
/// (`app_tree.dart`) has somewhere to call them from. Logic itself is
/// unchanged from the original screen.
Future<void> exportServerConfig(BuildContext context, WidgetRef ref) async {
  final includeCredentials = await showAppDialog<bool>(
    context: context,
    title: 'Exportar configuración',
    body: const Text(
      '¿Incluir las credenciales guardadas (usuario/contraseña) en el archivo? '
      'Si las incluyes, el archivo queda con contraseñas en texto plano — trátalo '
      'como una contraseña más (no lo compartas ni lo subas a un repositorio).',
    ),
    actions: [
      AppButton(
          label: 'Sin credenciales',
          autofocus: true,
          onPressed: () => Navigator.of(context).pop(false)),
      AppButton(
        label: 'Incluir credenciales',
        variant: AppButtonVariant.primary,
        onPressed: () => Navigator.of(context).pop(true),
      ),
    ],
  );
  if (includeCredentials == null) return;
  if (!context.mounted) return;

  final rawPath = await FilePicker.platform.saveFile(
    dialogTitle: 'Exportar configuración de servidores',
    fileName: 'faro_servidores.json',
    type: FileType.custom,
    allowedExtensions: ['json'],
  );
  if (rawPath == null) return;
  final path = ensureExtension(rawPath, 'json');

  // Deliberately `serverListProvider`, not `ungroupedDatabasesProvider` —
  // "Sin grupo" databases are a transient staging area, not configuration
  // worth round-tripping through a hand-authored bootstrap file (matches
  // ServersNotifier.replaceAll's own doc comment).
  final servers = ref.read(serverListProvider);
  var serverCredentials = const <String, DatabaseCredentials>{};
  var databaseCredentials = const <String, DatabaseCredentials>{};
  if (includeCredentials) {
    final repo = ref.read(credentialsRepositoryProvider);
    final serverEntries = <String, DatabaseCredentials>{};
    final databaseEntries = <String, DatabaseCredentials>{};
    for (final server in servers) {
      final creds = await repo.serverCredentials(server.id);
      if (creds != null) serverEntries[server.id] = creds;
      for (final db in server.databases) {
        final dbCreds = await repo.databaseOverride(db.id);
        if (dbCreds != null) databaseEntries[db.id] = dbCreds;
      }
    }
    serverCredentials = serverEntries;
    databaseCredentials = databaseEntries;
  }

  await File(path).writeAsString(ServerConfigCodec.encode(servers,
      serverCredentials: serverCredentials,
      databaseCredentials: databaseCredentials));
}

Future<void> importServerConfig(BuildContext context, WidgetRef ref) async {
  final picked = await FilePicker.platform
      .pickFiles(type: FileType.custom, allowedExtensions: ['json']);
  final path = picked?.files.single.path;
  if (path == null) return;

  final ParsedServerConfig parsed;
  try {
    parsed = ServerConfigCodec.decode(await File(path).readAsString());
  } catch (e) {
    if (!context.mounted) return;
    await showAppDialog<void>(
      context: context,
      title: 'No se pudo importar',
      body: Text('El archivo no tiene un formato válido: $e'),
      actions: [
        AppButton(
            label: 'Cerrar',
            autofocus: true,
            onPressed: () => Navigator.of(context).pop())
      ],
    );
    return;
  }

  if (!context.mounted) return;
  final currentServers = ref.read(serverListProvider);
  final hasCredentials = parsed.serverCredentials.isNotEmpty ||
      parsed.databaseCredentials.isNotEmpty;
  final confirmed = await showAppDialog<bool>(
    context: context,
    title: 'Importar configuración',
    body: Text(
      'Esto reemplaza los ${currentServers.length} servidor(es) actuales por los '
      '${parsed.servers.length} del archivo. Esta acción no se puede deshacer. '
      'Las bases de datos "Sin grupo" no se ven afectadas.'
      '${hasCredentials ? '\n\nEl archivo también trae credenciales guardadas — se guardarán en el almacén seguro.' : ''}',
    ),
    actions: [
      AppButton(
          label: 'Cancelar',
          autofocus: true,
          onPressed: () => Navigator.of(context).pop(false)),
      AppButton(
        label: 'Reemplazar',
        variant: AppButtonVariant.primary,
        onPressed: () => Navigator.of(context).pop(true),
      ),
    ],
  );
  if (confirmed != true) return;

  final credentialsRepo = ref.read(credentialsRepositoryProvider);
  for (final server in currentServers) {
    await credentialsRepo.deleteServerCredentials(server.id);
    for (final db in server.databases) {
      await credentialsRepo.clearDatabaseOverride(db.id);
    }
  }
  for (final entry in parsed.serverCredentials.entries) {
    await credentialsRepo.setServerCredentials(entry.key, entry.value);
  }
  for (final entry in parsed.databaseCredentials.entries) {
    await credentialsRepo.setDatabaseOverride(entry.key, entry.value);
  }
  ref.read(serversProvider.notifier).replaceAll(parsed.servers);
}
