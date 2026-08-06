import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme/app_spacing.dart';
import '../../data/models/database_entry.dart';
import '../../data/models/server.dart';
import '../../data/providers/servers_providers.dart';
import 'app_button.dart';
import 'app_dialog.dart';

/// "Editar base de datos" — Consulta's sidebar tree never showed (let alone
/// let you edit) [DatabaseEntry.host]/[DatabaseEntry.databaseName] at all,
/// and showed [DatabaseEntry.name] (the alias) as plain read-only text —
/// all three are only inline-editable from Administración's `DatabaseRow`.
/// Same extraction pattern as `add_database_dialog.dart` (same field
/// labels/hints, so it reads as the same "family" of dialog), reusing the
/// mutators `database_row.dart`'s inline fields already call — no new
/// data-layer behavior, just another entry point to it. Deliberately no
/// usuario/contraseña fields here — that's `showDatabaseCredentialsDialog`'s
/// job (the key icon right next to this one), not duplicated.
Future<void> showEditDatabaseDialog(BuildContext context, WidgetRef ref,
    Server server, DatabaseEntry database) async {
  final nameController = TextEditingController(text: database.name);
  final hostController = TextEditingController(text: database.host);
  final dbNameController = TextEditingController(text: database.databaseName);
  // Real bug fixed 2026-08-03 (AUDITORIA_CODIGO.md): never disposed on
  // any exit path.
  try {
    void submit() => Navigator.of(context).pop(true);

    final saved = await showAppDialog<bool>(
      context: context,
      title: 'Editar base de datos',
      body: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          TextField(
            controller: nameController,
            decoration: const InputDecoration(labelText: 'Alias'),
            onSubmitted: (_) => submit(),
          ),
          const SizedBox(height: AppSpacing.space2),
          TextField(
            controller: hostController,
            decoration: const InputDecoration(
              labelText: 'Host',
              hintText: '192.168.1.10:5432',
            ),
            onSubmitted: (_) => submit(),
          ),
          const SizedBox(height: AppSpacing.space2),
          TextField(
            controller: dbNameController,
            decoration: const InputDecoration(
                labelText: 'Nombre real de la base de datos'),
            onSubmitted: (_) => submit(),
          ),
        ],
      ),
      actions: [
        AppButton(
          label: 'Guardar',
          variant: AppButtonVariant.primary,
          autofocus: true,
          onPressed: submit,
        ),
      ],
    );

    if (saved != true) return;
    final name = nameController.text.trim();
    final host = hostController.text.trim();
    final databaseName = dbNameController.text.trim();

    // Real bug fixed 2026-08-03 (AUDITORIA_CODIGO.md): this used to be
    // all-or-nothing — leaving any ONE field blank silently discarded every
    // other field's edit too, with no message explaining why nothing got
    // saved. Each field now commits independently if it isn't blank,
    // matching how Administración's inline per-field editing already
    // behaves (each `InlineEditableText` commits on its own).
    final notifier = ref.read(serversProvider.notifier);
    if (name.isNotEmpty) notifier.renameDatabase(server.id, database.id, name);
    if (host.isNotEmpty) notifier.setDatabaseHost(server.id, database.id, host);
    if (databaseName.isNotEmpty) {
      notifier.setDatabaseName(server.id, database.id, databaseName);
    }
  } finally {
    nameController.dispose();
    hostController.dispose();
    dbNameController.dispose();
  }
}
