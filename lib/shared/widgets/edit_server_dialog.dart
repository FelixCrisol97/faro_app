import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/models/server.dart';
import '../../data/providers/servers_providers.dart';
import 'app_button.dart';
import 'app_dialog.dart';

/// "Editar servidor" — Consulta's sidebar tree only ever showed
/// `server.name` as plain read-only text (Administración edits it inline
/// via `InlineEditableText` right in the card), so there was no way to
/// rename a server without leaving Consulta. Same extraction pattern as
/// `add_server_dialog.dart`, reusing `ServersNotifier.renameServer` — the
/// same mutator `server_admin_card.dart`'s inline field already calls, so
/// this doesn't add any new data-layer behavior, just another entry point
/// to it.
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
