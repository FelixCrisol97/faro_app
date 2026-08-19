import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme/app_spacing.dart';
import '../../data/models/database_entry.dart';
import '../../data/models/server.dart';
import '../../data/providers/core_providers.dart';
import 'app_button.dart';
import 'app_dialog.dart';

enum _CredentialsDialogResult { save, clear }

/// "Credenciales" — the servidor's default username/password, applied to
/// every one of its databases unless overridden per-database. Originally
/// only reachable from Administración's server card (key icon), now also
/// offered from Consulta's sidebar so setting them up doesn't require
/// leaving Consulta first. Extracted here (rather than duplicated) so both
/// call sites share the exact same dialog/logic.
Future<void> showServerCredentialsDialog(
    BuildContext context, WidgetRef ref, Server server) async {
  final repo = ref.read(credentialsRepositoryProvider);
  final current = await repo.serverCredentials(server.id);
  if (!context.mounted) return;

  final usernameController =
      TextEditingController(text: current?.username ?? '');
  final passwordController =
      TextEditingController(text: current?.password ?? '');
  // Real bug fixed 2026-08-03 (AUDITORIA_CODIGO.md): never disposed on
  // any exit path.
  try {
    void submit() => Navigator.of(context).pop(true);

    final saved = await showAppDialog<bool>(
      context: context,
      title: 'Credenciales — ${server.name}',
      body: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
              'Usuario y contraseña por defecto para todas las bases de datos de este servidor.'),
          const SizedBox(height: AppSpacing.space2),
          TextField(
              controller: usernameController,
              decoration: const InputDecoration(labelText: 'Usuario'),
              onSubmitted: (_) => submit()),
          const SizedBox(height: AppSpacing.space2),
          TextField(
            controller: passwordController,
            obscureText: true,
            decoration: const InputDecoration(labelText: 'Contraseña'),
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

    if (saved == true) {
      await repo.setServerCredentials(
        server.id,
        (
          username: usernameController.text.trim(),
          password: passwordController.text
        ),
      );
    }
  } finally {
    usernameController.dispose();
    passwordController.dispose();
  }
}

/// "Credenciales" per database — an optional override of the servidor's
/// default, for the one-off case where a single database needs a different
/// login. Originally only reachable from Administración's `_DatabaseRow`
/// (key icon), now also offered from Consulta's sidebar — same
/// extraction pattern as [showServerCredentialsDialog]. [server] `null`
/// (2026-08-13) — a "Sin grupo" database has no servidor default to fall
/// back to (see `CredentialsRepository.resolve`'s doc comment), so
/// whatever is typed here is this database's *only* credentials, not an
/// override.
Future<void> showDatabaseCredentialsDialog(BuildContext context, WidgetRef ref,
    Server? server, DatabaseEntry database) async {
  final repo = ref.read(credentialsRepositoryProvider);
  final current = await repo.databaseOverride(database.id);
  if (!context.mounted) return;

  final usernameController =
      TextEditingController(text: current?.username ?? '');
  final passwordController =
      TextEditingController(text: current?.password ?? '');
  // Real bug fixed 2026-08-03 (AUDITORIA_CODIGO.md): never disposed on
  // any exit path.
  try {
    void submit() => Navigator.of(context).pop(_CredentialsDialogResult.save);

    final result = await showAppDialog<_CredentialsDialogResult>(
      context: context,
      title: 'Credenciales — ${database.name}',
      body: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            server == null
                ? 'Esta base de datos no pertenece a ningún servidor, así que estas son sus únicas credenciales.'
                : current != null
                    ? 'Sobreescribe las credenciales del servidor "${server.name}" solo para esta base de datos.'
                    : 'Vacío por defecto: usa las credenciales del servidor "${server.name}". Llena estos campos '
                        'solo si esta base de datos necesita un usuario distinto.',
          ),
          const SizedBox(height: AppSpacing.space2),
          TextField(
              controller: usernameController,
              decoration: const InputDecoration(labelText: 'Usuario'),
              onSubmitted: (_) => submit()),
          const SizedBox(height: AppSpacing.space2),
          TextField(
            controller: passwordController,
            obscureText: true,
            decoration: const InputDecoration(labelText: 'Contraseña'),
            onSubmitted: (_) => submit(),
          ),
        ],
      ),
      actions: [
        if (current != null)
          AppButton(
            label: 'Usar las del servidor',
            onPressed: () =>
                Navigator.of(context).pop(_CredentialsDialogResult.clear),
          ),
        AppButton(
          label: 'Guardar',
          variant: AppButtonVariant.primary,
          autofocus: true,
          onPressed: submit,
        ),
      ],
    );

    if (result == _CredentialsDialogResult.save) {
      await repo.setDatabaseOverride(
        database.id,
        (
          username: usernameController.text.trim(),
          password: passwordController.text
        ),
      );
    } else if (result == _CredentialsDialogResult.clear) {
      await repo.clearDatabaseOverride(database.id);
    }
  } finally {
    usernameController.dispose();
    passwordController.dispose();
  }
}
