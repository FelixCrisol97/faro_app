import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:uuid/uuid.dart';

import '../../core/theme/app_spacing.dart';
import '../../data/models/database_entry.dart';
import '../../data/models/server.dart';
import '../../data/providers/core_providers.dart';
import '../../data/providers/servers_providers.dart';
import 'app_button.dart';
import 'app_dialog.dart';

const _uuid = Uuid();

/// "Agregar base de datos" — originally only reachable from each server
/// card in Administración, now also offered directly from Consulta's
/// sidebar (a small "+" next to each server row) so adding one doesn't
/// require leaving Consulta first. Extracted here (rather than duplicated)
/// so both call sites share the exact same dialog/logic — same pattern as
/// `add_server_dialog.dart`.
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
Future<void> showAddDatabaseDialog(
    BuildContext context, WidgetRef ref, Server server) async {
  final nameController = TextEditingController();
  final hostController = TextEditingController();
  final dbNameController = TextEditingController();
  final usernameController = TextEditingController();
  final passwordController = TextEditingController();
  // Real bug fixed 2026-08-03 (AUDITORIA_CODIGO.md): these were never
  // disposed on any exit path — wrapped in try/finally so every return
  // below (including the early ones) still cleans them up.
  try {
    void submit() => Navigator.of(context).pop(true);

    final created = await showAppDialog<bool>(
      context: context,
      title: 'Agregar base de datos',
      body: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
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
            decoration: const InputDecoration(labelText: 'Usuario (opcional)'),
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
    ref.read(serversProvider.notifier).addDatabase(
        server.id,
        DatabaseEntry(
            id: databaseId,
            name: name,
            host: host,
            databaseName: databaseName));

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
