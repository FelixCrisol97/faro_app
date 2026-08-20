import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants/db_engine.dart';
import '../../core/theme/app_spacing.dart';
import '../../data/models/database_entry.dart';
import '../../data/models/server.dart';
import '../../data/providers/core_providers.dart';
import '../../data/providers/servers_providers.dart';
import 'app_button.dart';
import 'app_dialog.dart';
import 'app_segmented_control.dart';

/// "Editar base de datos" — Consulta's sidebar tree never showed (let alone
/// let you edit) [DatabaseEntry.host]/[DatabaseEntry.databaseName] at all,
/// and showed [DatabaseEntry.name] (the alias) as plain read-only text —
/// all three are only inline-editable from Administración's `DatabaseRow`.
/// Same extraction pattern as `add_database_dialog.dart` (same field
/// labels/hints, so it reads as the same "family" of dialog), reusing the
/// mutators `database_row.dart`'s inline fields already call — no new
/// data-layer behavior, just another entry point to it.
///
/// **Engine selector added 2026-08-13** — [DatabaseEntry.engine] is picked
/// once when a database is created (`add_database_dialog.dart`) but a wrong
/// guess (e.g. merging two "Sin grupo" databases used to guess an engine
/// before engine moved off `Server` — see `edit_server_dialog.dart`'s doc
/// comment) needs somewhere to be corrected afterward; this is that place.
///
/// **Usuario/Contraseña added 2026-08-19** — this dialog used to
/// deliberately exclude credentials ("that's `showDatabaseCredentialsDialog`'s
/// job, not duplicated"), on the reasoning that the key icon right next to
/// this one already covers it. Real user report: typing the wrong username
/// while creating a database (`add_database_dialog.dart` already asks for
/// it inline) and then not finding it here on "Editar" reads as "editing is
/// broken" — the user deleted and recreated the database rather than
/// noticing the separate key icon. Pre-fills the current per-database
/// override (if any — same lookup `showDatabaseCredentialsDialog` does),
/// blank otherwise, matching how "Agregar" starts blank; saves it the same
/// way (only writes a value when the field isn't left empty on submit).
/// The key icon/`showDatabaseCredentialsDialog` stays as-is for the one
/// thing this dialog doesn't attempt: explicitly clearing an override back
/// to the server's default ("Usar las del servidor").
Future<void> showEditDatabaseDialog(BuildContext context, WidgetRef ref,
    Server? server, DatabaseEntry database) async {
  final credentialsRepo = ref.read(credentialsRepositoryProvider);
  final currentCredentials = await credentialsRepo.databaseOverride(database.id);
  if (!context.mounted) return;

  final nameController = TextEditingController(text: database.name);
  final hostController = TextEditingController(text: database.host);
  final dbNameController = TextEditingController(text: database.databaseName);
  final usernameController =
      TextEditingController(text: currentCredentials?.username ?? '');
  final passwordController =
      TextEditingController(text: currentCredentials?.password ?? '');
  var engine = database.engine;
  // Real bug fixed 2026-08-03 (AUDITORIA_CODIGO.md): never disposed on
  // any exit path.
  try {
    void submit() => Navigator.of(context).pop(true);

    final saved = await showAppDialog<bool>(
      context: context,
      title: 'Editar base de datos',
      body: StatefulBuilder(
        builder: (context, setDialogState) => Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            AppSegmentedControl<DbEngine>(
              options: const [
                AppSegmentedOption(
                    value: DbEngine.postgres, label: 'PostgreSQL'),
                AppSegmentedOption(
                    value: DbEngine.sqlServer, label: 'SQL Server'),
              ],
              value: engine,
              onChanged: (value) => setDialogState(() => engine = value),
            ),
            const SizedBox(height: AppSpacing.space2),
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
            const SizedBox(height: AppSpacing.space2),
            TextField(
              controller: usernameController,
              decoration:
                  const InputDecoration(labelText: 'Usuario (opcional)'),
              onSubmitted: (_) => submit(),
            ),
            const SizedBox(height: AppSpacing.space2),
            TextField(
              controller: passwordController,
              obscureText: true,
              decoration:
                  const InputDecoration(labelText: 'Contraseña (opcional)'),
              onSubmitted: (_) => submit(),
            ),
          ],
        ),
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
    if (name.isNotEmpty) {
      notifier.renameDatabase(server?.id, database.id, name);
    }
    if (host.isNotEmpty) {
      notifier.setDatabaseHost(server?.id, database.id, host);
    }
    if (databaseName.isNotEmpty) {
      notifier.setDatabaseName(server?.id, database.id, databaseName);
    }
    if (engine != database.engine) {
      notifier.setDatabaseEngine(server?.id, database.id, engine);
    }
    final username = usernameController.text.trim();
    if (username.isNotEmpty) {
      await credentialsRepo.setDatabaseOverride(
          database.id, (username: username, password: passwordController.text));
    }
  } finally {
    nameController.dispose();
    hostController.dispose();
    dbNameController.dispose();
    usernameController.dispose();
    passwordController.dispose();
  }
}
