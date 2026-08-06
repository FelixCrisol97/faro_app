import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:uuid/uuid.dart';

import '../../core/constants/db_engine.dart';
import '../../core/theme/app_spacing.dart';
import '../../data/models/server.dart';
import '../../data/providers/core_providers.dart';
import '../../data/providers/servers_providers.dart';
import 'app_button.dart';
import 'app_dialog.dart';
import 'app_segmented_control.dart';

const _uuid = Uuid();

/// "Agregar servidor" — originally only reachable from Administración, now
/// also offered as a quick action from Consulta's sidebar ("+ Registrar
/// servidor") so adding a brand-new server doesn't require leaving Consulta
/// first. Extracted here (rather than duplicated) so both call sites share
/// the exact same dialog/logic.
Future<void> showAddServerDialog(BuildContext context, WidgetRef ref) async {
  final nameController = TextEditingController();
  final usernameController = TextEditingController();
  final passwordController = TextEditingController();
  // Real bug fixed 2026-08-03 (AUDITORIA_CODIGO.md): these were never
  // disposed on any exit path — wrapped in try/finally so every return
  // below (including the early ones) still cleans them up.
  try {
    var engine = DbEngine.postgres;

    void submit() => Navigator.of(context).pop(true);

    final created = await showAppDialog<bool>(
      context: context,
      title: 'Agregar servidor',
      body: StatefulBuilder(
        builder: (context, setState) => Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            TextField(
                controller: nameController,
                decoration: const InputDecoration(labelText: 'Nombre'),
                onSubmitted: (_) => submit()),
            const SizedBox(height: AppSpacing.space2),
            AppSegmentedControl<DbEngine>(
              value: engine,
              onChanged: (value) => setState(() => engine = value),
              options: const [
                AppSegmentedOption(
                    value: DbEngine.postgres, label: 'PostgreSQL'),
                AppSegmentedOption(
                    value: DbEngine.sqlServer, label: 'SQL Server'),
              ],
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
    if (name.isEmpty) return;

    final serverId = _uuid.v4();
    ref
        .read(serversProvider.notifier)
        .addServer(Server(id: serverId, name: name, engine: engine));

    final username = usernameController.text.trim();
    if (username.isNotEmpty) {
      await ref.read(credentialsRepositoryProvider).setServerCredentials(
          serverId, (username: username, password: passwordController.text));
    }
  } finally {
    nameController.dispose();
    usernameController.dispose();
    passwordController.dispose();
  }
}
