import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:uuid/uuid.dart';

import '../../core/constants/db_engine.dart';
import '../../core/theme/app_spacing.dart';
import '../../data/models/database_entry.dart';
import '../../data/models/server.dart';
import '../../data/providers/core_providers.dart';
import '../../data/providers/servers_providers.dart';
import 'app_button.dart';
import 'app_dialog.dart';
import 'app_segmented_control.dart';

const _uuid = Uuid();

/// "Agregar base de datos" — originally only reachable from each server
/// card in Administración, now also offered directly from Consulta's
/// sidebar (a small "+" next to each server row) so adding one doesn't
/// require leaving Consulta first. Extracted here (rather than duplicated)
/// so both call sites share the exact same dialog/logic.
///
/// **Redesigned 2026-07-24, user-reported ("se ve horrible"):** used to
/// have long explanatory `helperText` under each field (Flutter's default
/// `InputDecoration` clips helper/error text to a single line and
/// ellipsizes it, so "Solo para identificarla en Faro — no tiene que ser
/// el nombre real de la base de datos" rendered as a nonsensical
/// mid-sentence "…"). Restyled to match [showAddServerDialog]'s clean,
/// minimal look instead — short labels, no helper text, optional
/// Usuario/Contraseña right here (same "fill in now or use the key icon
/// later" pattern [showAddServerDialog] already has for a server's
/// default credentials) so a database with a known login doesn't need a
/// second trip through the key icon just to type it in.
///
/// **Engine picker added 2026-08-13** — [engine] moved from `Server` to
/// [DatabaseEntry] (user-requested: "cuando crear una bd tu escoges si es
/// postgresql o sql server de una vez"), so it's asked here unconditionally,
/// not just when [server] is null — a database always picks its own driver
/// now, grouping or not.
///
/// [server] `null` (2026-08-12, "Sin grupo" section) adds the database to
/// [ServersNotifier.addUngroupedDatabase] instead of a specific server's
/// list — same fields, same credentials handling (already keyed by
/// `databaseId` alone, nothing serverId-dependent), just a different
/// destination list.
Future<void> showAddDatabaseDialog(
    BuildContext context, WidgetRef ref, Server? server) async {
  final nameController = TextEditingController();
  final hostController = TextEditingController();
  final dbNameController = TextEditingController();
  final usernameController = TextEditingController();
  final passwordController = TextEditingController();
  var engine = DbEngine.postgres;
  // Real bug fixed 2026-08-03 (AUDITORIA_CODIGO.md): these were never
  // disposed on any exit path — wrapped in try/finally so every return
  // below (including the early ones) still cleans them up.
  try {
    void submit() => Navigator.of(context).pop(true);

    final created = await showAppDialog<bool>(
      context: context,
      title: 'Agregar base de datos',
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
            const SizedBox(height: AppSpacing.space3),
            TextField(
              controller: nameController,
              decoration: const InputDecoration(
                labelText: 'Alias',
                hintText: 'Bodega Norte',
              ),
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
                labelText: 'Nombre real de la base de datos',
                hintText: 'bodega',
              ),
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
          label: 'Agregar',
          variant: AppButtonVariant.primary,
          autofocus: true,
          onPressed: submit,
        ),
      ],
    );

    if (created != true) return;
    final name = nameController.text.trim();
    final host = hostController.text.trim();
    final databaseName = dbNameController.text.trim();
    if (name.isEmpty || host.isEmpty || databaseName.isEmpty) {
      // Real bug fixed 2026-08-03 (AUDITORIA_CODIGO.md): unlike editing an
      // existing database (where committing whichever fields aren't blank
      // makes sense — see `edit_database_dialog.dart`), *creating* one
      // genuinely needs all three values at once, so all-or-nothing is the
      // right behavior here. What wasn't right was doing it in total
      // silence — the dialog just closed with nothing added and no
      // indication why.
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
          content: Text(
              'Alias, Host y el nombre real de la base de datos son obligatorios — no se agregó nada.'),
          duration: Duration(seconds: 4)));
      return;
    }

    final databaseId = _uuid.v4();
    final database = DatabaseEntry(
        id: databaseId,
        name: name,
        host: host,
        databaseName: databaseName,
        engine: engine);
    final notifier = ref.read(serversProvider.notifier);
    if (server == null) {
      notifier.addUngroupedDatabase(database);
    } else {
      notifier.addDatabase(server.id, database);
    }

    final username = usernameController.text.trim();
    if (username.isNotEmpty) {
      await ref.read(credentialsRepositoryProvider).setDatabaseOverride(
          databaseId, (username: username, password: passwordController.text));
    }
  } finally {
    nameController.dispose();
    hostController.dispose();
    dbNameController.dispose();
    usernameController.dispose();
    passwordController.dispose();
  }
}
