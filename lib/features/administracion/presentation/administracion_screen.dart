import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_lucide/flutter_lucide.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_spacing.dart';
import '../../../core/theme/app_theme.dart';
import '../../../data/models/database_credentials.dart';
import '../../../data/models/database_entry.dart';
import '../../../data/providers/core_providers.dart';
import '../../../data/providers/servers_providers.dart';
import '../../../data/repositories/server_config_codec.dart';
import '../../../shared/utils/file_paths.dart';
import '../../../shared/widgets/add_server_dialog.dart';
import '../../../shared/widgets/app_button.dart';
import '../../../shared/widgets/app_dialog.dart';
import 'widgets/server_admin_card.dart';

/// README.md "4. Administración": list of server cards + "Agregar servidor".
class AdministracionScreen extends ConsumerStatefulWidget {
  const AdministracionScreen({super.key});

  @override
  ConsumerState<AdministracionScreen> createState() =>
      _AdministracionScreenState();
}

class _AdministracionScreenState extends ConsumerState<AdministracionScreen> {
  // Screen-local, not persisted — resets each time Administración opens,
  // same as the Consulta sidebar's own transient UI state.
  final _searchController = TextEditingController();
  String _query = '';

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = context.appTheme.colors;
    final servers = ref.watch(serversProvider);
    final query = _query.trim().toLowerCase();

    // A server with many databases (30+ isn't unusual — see bulk-imported
    // configs) meant finding one near the bottom required scrolling past
    // every row above it. Typing here either (a) matches the server's own
    // name, in which case its card shows in full, or (b) matches individual
    // databases within it (by alias or real name), in which case only those
    // rows show — so a hit is always at most one card away, never a scroll.
    final visibleServers = query.isEmpty
        ? servers
        : servers.where((s) =>
            s.name.toLowerCase().contains(query) ||
            s.databases.any((db) => _matchesQuery(db, query)));

    // Real bug fixed 2026-08-03 (AUDITORIA_CODIGO.md): `servers.indexOf`
    // was called twice per visible server inside the loop below — each
    // call is O(n) over the full list, so the loop as a whole was O(n²).
    // Computed once here instead.
    final serverIndexById = {
      for (final (i, s) in servers.indexed) s.id: i,
    };

    return Padding(
      padding: const EdgeInsets.all(AppSpacing.space4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (servers.isNotEmpty) ...[
            TextField(
              controller: _searchController,
              onChanged: (value) => setState(() => _query = value),
              decoration: InputDecoration(
                hintText: 'Buscar servidor o base de datos…',
                prefixIcon: Icon(LucideIcons.search, color: colors.textMuted),
                suffixIcon: _query.isEmpty
                    ? null
                    : IconButton(
                        icon: Icon(LucideIcons.x, color: colors.textMuted),
                        onPressed: () => setState(() {
                          _searchController.clear();
                          _query = '';
                        }),
                      ),
              ),
            ),
            const SizedBox(height: AppSpacing.space3),
          ],
          Expanded(
            child: ListView(
              children: [
                for (final server in visibleServers)
                  Padding(
                    key: ValueKey(server.id),
                    padding: const EdgeInsets.only(bottom: AppSpacing.space3),
                    child: ServerAdminCard(
                      server: server,
                      // Computed against the full `servers` list, not
                      // `visibleServers` — while the search box narrows
                      // which cards show, reordering always acts on the
                      // real underlying list, so "Mover al inicio"/"Subir"/
                      // "Bajar" should reflect the actual position, not
                      // the position among just the search matches.
                      isFirst: serverIndexById[server.id] == 0,
                      isLast: serverIndexById[server.id] == servers.length - 1,
                      // Only narrow the *rows shown* when the match came
                      // from individual databases, not the server's own
                      // name — searching "PROD" should reveal everything
                      // in that server, not just a database that happens
                      // to also contain "prod".
                      visibleDatabases: query.isEmpty ||
                              server.name.toLowerCase().contains(query)
                          ? server.databases
                          : server.databases
                              .where((db) => _matchesQuery(db, query))
                              .toList(),
                      // A match hiding inside a manually-collapsed card
                      // would make the search look broken — force every
                      // card open while there's something typed.
                      forceExpanded: query.isNotEmpty,
                    ),
                  ),
              ],
            ),
          ),
          const SizedBox(height: AppSpacing.space2),
          Row(
            children: [
              AppButton(
                label: 'Agregar servidor',
                icon: LucideIcons.plus,
                variant: AppButtonVariant.primary,
                onPressed: () => showAddServerDialog(context, ref),
              ),
              const SizedBox(width: AppSpacing.space2),
              AppButton(
                label: 'Importar configuración',
                icon: LucideIcons.upload,
                onPressed: () => _importConfig(context, ref),
              ),
              const SizedBox(width: AppSpacing.space2),
              AppButton(
                label: 'Exportar configuración',
                icon: LucideIcons.download,
                onPressed: () => _exportConfig(context, ref),
              ),
            ],
          ),
        ],
      ),
    );
  }

  bool _matchesQuery(DatabaseEntry db, String query) =>
      db.name.toLowerCase().contains(query) ||
      db.databaseName.toLowerCase().contains(query);

  Future<void> _exportConfig(BuildContext context, WidgetRef ref) async {
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

    final servers = ref.read(serversProvider);
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

    await File(
      path,
    ).writeAsString(ServerConfigCodec.encode(servers,
        serverCredentials: serverCredentials,
        databaseCredentials: databaseCredentials));
  }

  Future<void> _importConfig(BuildContext context, WidgetRef ref) async {
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
    final currentServers = ref.read(serversProvider);
    final hasCredentials = parsed.serverCredentials.isNotEmpty ||
        parsed.databaseCredentials.isNotEmpty;
    final confirmed = await showAppDialog<bool>(
      context: context,
      title: 'Importar configuración',
      body: Text(
        'Esto reemplaza los ${currentServers.length} servidor(es) actuales por los '
        '${parsed.servers.length} del archivo. Esta acción no se puede deshacer.'
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
}
