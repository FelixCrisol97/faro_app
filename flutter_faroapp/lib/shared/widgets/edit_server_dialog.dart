import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/models/server.dart';
import '../../data/providers/servers_providers.dart';
import 'app_button.dart';
import 'app_dialog.dart';

/// "Editar servidor" — the tree only ever showed `server.name` as plain
/// read-only text, so there was no way to rename a server without a
/// dedicated entry point. Reuses `ServersNotifier.renameServer` — the same
/// mutator Administración's
/// old inline field called before that screen was absorbed into the tree
/// (2026-08-12), so this doesn't add any new data-layer behavior, just
/// another entry point to it.
///
/// **Engine selector removed 2026-08-13** — it briefly lived here
/// (2026-08-12) to correct a guessed engine when merging two "Sin grupo"
/// databases created a new server, but engine moved off `Server` onto
/// `DatabaseEntry` that same day (user-requested: "cada grupo/servidor
/// puede tener diferentes motores") — there's no longer a single
/// server-level engine to guess or correct. See
/// `edit_database_dialog.dart` for the per-database equivalent instead.
Future<void> showEditServerDialog(
    BuildContext context, WidgetRef ref, Server server) async {
  final nameController = TextEditingController(text: server.name);
  // Real bug fixed 2026-08-03 (AUDITORIA_CODIGO.md): never disposed on
  // any exit path.
  try {
    void submit() => Navigator.of(context).pop(true);

    final saved = await showAppDialog<bool>(
      context: context,
      title: 'Editar servidor',
      body: TextField(
        controller: nameController,
        decoration: const InputDecoration(labelText: 'Nombre'),
        onSubmitted: (_) => submit(),
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
    if (name.isEmpty) return;

    ref.read(serversProvider.notifier).renameServer(server.id, name);
  } finally {
    nameController.dispose();
  }
}
