import 'package:flutter/material.dart';
import 'package:flutter_lucide/flutter_lucide.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/constants/db_engine.dart';
import '../../../../core/theme/app_spacing.dart';
import '../../../../core/theme/app_theme.dart';
import '../../../../data/models/database_entry.dart';
import '../../../../data/models/server.dart';
import '../../../../data/providers/servers_providers.dart';
import '../../../../shared/widgets/app_button.dart';
import '../../../../shared/widgets/app_dialog.dart';
import '../../../../shared/widgets/app_segmented_control.dart';
import '../../../../shared/widgets/discover_databases_dialog.dart';
import '../../../../shared/widgets/inline_editable_text.dart';
import '../../../../shared/widgets/remove_dialogs.dart';
import '../../../../shared/widgets/server_credentials_dialog.dart';
import '../../../../shared/widgets/test_connection_action.dart';
import 'status_pill.dart';

class DatabaseRow extends ConsumerWidget {
  const DatabaseRow({super.key, required this.server, required this.database});
  final Server server;
  final DatabaseEntry database;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colors = context.appTheme.colors;
    final typography = context.appTheme.typography;
    final notifier = ref.read(serversProvider.notifier);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Real bug fixed 2026-08-03 (AUDITORIA_CODIGO.md): this row's
        // fixed-width fields (Alias/Host/Nombre real, each an explicit
        // SizedBox — see the comment on Alias's width below for why they
        // aren't Expanded) plus the icon buttons and "Probar conexión"
        // add up to comfortably more than the app's minimum window width
        // (800px, sized against Consulta's layout — this row was never
        // part of that analysis) — with no scroll container, the row
        // simply overflowed (the classic RenderFlex hazard-stripes
        // warning) once the window got close to that minimum. A
        // horizontal scroll here is the same fix already applied to wide
        // content elsewhere (`VirtualizedTable`'s results grid) — every
        // field keeps its designed width exactly, the row just scrolls
        // instead of overflowing when the window is too narrow to show
        // all of it at once.
        SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          child: Row(children: [
          Icon(LucideIcons.database, size: 16, color: colors.textMuted),
          const SizedBox(width: AppSpacing.space2),
          // Alias used to be Expanded — with a card that can span nearly
          // the full window width, that stretched a typically-short alias
          // across a huge run of empty space, at the cost of squeezing host
          // and the real database name into boxes too narrow for realistic
          // values (a routed IP:puerto or a name like "bodegamuebles.30001"
          // was already getting clipped). Fixed widths for all three
          // instead — host and real-name widened to fit what people
          // actually type, alias narrowed since it rarely needs as much,
          // and the gaps between them tightened so the whole row reads as
          // one compact block instead of stretching edge to edge.
          SizedBox(
            width: 210,
            child: Tooltip(
              message:
                  'Alias — solo para identificar esta base de datos dentro de Faro (en resultados, listas, etc). '
                  'No tiene que coincidir con el nombre real de la base de datos.',
              child: InlineEditableText(
                value: database.name,
                hint: 'Alias, ej. Bodega Norte',
                style: typography.body.copyWith(fontSize: 14),
                onChanged: (name) =>
                    notifier.renameDatabase(server.id, database.id, name),
              ),
            ),
          ),
          const SizedBox(width: AppSpacing.space1),
          if (database.host.isEmpty)
            Tooltip(
              message:
                  'Falta la dirección: IP o nombre de host, dos puntos, y el puerto — ej. 192.168.1.10:5432',
              child: Icon(LucideIcons.circle_alert,
                  size: 16, color: Theme.of(context).colorScheme.error),
            ),
          if (database.host.isEmpty) const SizedBox(width: 4),
          SizedBox(
            width: 210,
            child: Tooltip(
              message:
                  'IP o nombre de host, dos puntos, y el puerto — todo junto en un solo campo. Ej. 192.168.1.10:5432',
              child: InlineEditableText(
                value: database.host,
                hint: '192.168.1.10:5432',
                style: typography.monospace.copyWith(
                  fontSize: 12,
                  color: database.host.isEmpty
                      ? Theme.of(context).colorScheme.error
                      : null,
                ),
                onChanged: (host) =>
                    notifier.setDatabaseHost(server.id, database.id, host),
              ),
            ),
          ),
          const SizedBox(width: AppSpacing.space1),
          SizedBox(
            width: 170,
            child: Tooltip(
              message:
                  'Nombre real de la base de datos en el motor — este sí se usa para conectar. '
                  'Puede repetirse entre bodegas (es normal que todas se llamen igual).',
              child: InlineEditableText(
                value: database.databaseName,
                hint: 'nombre_bd',
                style: typography.monospace.copyWith(fontSize: 12),
                onChanged: (dbName) =>
                    notifier.setDatabaseName(server.id, database.id, dbName),
              ),
            ),
          ),
          const SizedBox(width: AppSpacing.space2),
          StatusPill(status: database.testStatus, errorMessage: database.testError),
          const SizedBox(width: AppSpacing.space2),
          AppIconButton(
            icon: LucideIcons.database_zap,
            tooltip: 'Descubrir más bases de datos en esta IP',
            onPressed: database.host.isEmpty
                ? null
                : () => showDiscoverDatabasesDialog(context, ref, server,
                    from: database),
          ),
          AppIconButton(
            icon: LucideIcons.key,
            tooltip: 'Credenciales solo para esta base de datos (opcional)',
            onPressed: () =>
                showDatabaseCredentialsDialog(context, ref, server, database),
          ),
          const SizedBox(width: AppSpacing.space2),
          // Ghost (accent-colored text, no fill) rather than primary
          // (solid fill) — this is the main action of the row, so it earns
          // the app's accent color, but a fully filled button repeated
          // down 30 rows would be a lot of solid color competing for
          // attention; key/trash stay neutral icon buttons since they're
          // secondary, occasional actions.
          AppButton(
            label: 'Probar conexión',
            variant: AppButtonVariant.ghost,
            onPressed: database.testStatus == ConnectionTestStatus.testing
                ? null
                : () => testDatabaseConnection(ref, server, database),
          ),
          AppIconButton(
            icon: LucideIcons.trash_2,
            tooltip: 'Eliminar base de datos',
            onPressed: () =>
                showRemoveDatabaseDialog(context, ref, server, database),
          ),
          ]),
        ),
        Padding(
          padding: const EdgeInsets.only(top: 4, left: 24),
          child: AppSegmentedControl<ServerMode>(
            value: database.mode,
            onChanged: (mode) => _onModeChanged(context, ref, mode),
            options: const [
              AppSegmentedOption(
                  value: ServerMode.readOnly, label: 'Solo lectura'),
              AppSegmentedOption(
                  value: ServerMode.development,
                  label: 'Consultas sin restricciones'),
            ],
          ),
        ),
      ],
    );
  }

  Future<void> _onModeChanged(
      BuildContext context, WidgetRef ref, ServerMode mode) async {
    final notifier = ref.read(serversProvider.notifier);
    if (mode == ServerMode.readOnly) {
      notifier.setDatabaseMode(server.id, database.id, mode);
      return;
    }
    final confirmed = await showAppDialog<bool>(
      context: context,
      title: 'Cambiar a Consultas sin restricciones',
      body: Text(
        'En este modo se permite ejecutar cualquier tipo de consulta contra "${database.name}", '
        'incluyendo las que modifican datos.',
      ),
      actions: [
        AppButton(
          label: 'Cancelar',
          onPressed: () => Navigator.of(context).pop(false),
        ),
        AppButton(
          label: 'Confirmar',
          variant: AppButtonVariant.primary,
          autofocus: true,
          onPressed: () => Navigator.of(context).pop(true),
        ),
      ],
    );
    if (confirmed ?? false) {
      notifier.setDatabaseMode(server.id, database.id, mode);
    }
  }
}
