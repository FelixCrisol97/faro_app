import 'package:flutter/material.dart';
import 'package:flutter_lucide/flutter_lucide.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/theme/app_spacing.dart';
import '../../../../core/theme/app_theme.dart';
import '../../../../data/models/database_entry.dart';
import '../../../../data/models/server.dart';
import '../../../../data/providers/servers_providers.dart';
import '../../../../shared/widgets/add_database_dialog.dart';
import '../../../../shared/widgets/app_button.dart';
import '../../../../shared/widgets/app_card.dart';
import '../../../../shared/widgets/app_tag.dart';
import '../../../../shared/widgets/inline_editable_text.dart';
import '../../../../shared/widgets/remove_dialogs.dart';
import '../../../../shared/widgets/server_credentials_dialog.dart';
import 'database_row.dart';

/// Reorder actions — order is just `serversProvider`'s own list order (see
/// `servers_providers.dart`'s `moveServer*` doc comment), so this and
/// Consulta's sidebar (right-click there instead, since that screen
/// already has that convention — this one doesn't, everything here is an
/// explicit button) both just call the same notifier methods.
enum _ServerCardMenuAction { moveToStart, moveUp, moveDown }

/// README.md "4. Administración" — one card per server: inline-editable
/// name, engine tag, mode segmented control (with a confirm dialog when
/// switching TO Sin restricciones), and one row per database (each with its own
/// inline-editable host, since IPs differ per bodega — see
/// [DatabaseEntry.host]). The per-database row and its status pill live in
/// `database_row.dart`/`status_pill.dart` — this file keeps only the card
/// shell (header + reorder/credentials/delete actions + the "add database"
/// button row).
class ServerAdminCard extends ConsumerStatefulWidget {
  ServerAdminCard({
    super.key,
    required this.server,
    required this.isFirst,
    required this.isLast,
    List<DatabaseEntry>? visibleDatabases,
    this.forceExpanded = false,
  }) : visibleDatabases = visibleDatabases ?? server.databases;

  final Server server;

  /// Which reorder actions make sense — no "Mover al inicio"/"Subir" for
  /// the first server, no "Bajar" for the last. Computed by the caller
  /// (`administracion_screen.dart`) from `serversProvider`'s real order,
  /// not `visibleDatabases`-filtered position.
  final bool isFirst;
  final bool isLast;

  /// Which of [server]'s databases to actually render — defaults to all of
  /// them. Administración's search box narrows this to matching rows only
  /// (see `administracion_screen.dart`) without touching anything else that
  /// still needs the *real* full list (credentials, delete-confirmation
  /// counts, "+ Agregar base de datos").
  final List<DatabaseEntry> visibleDatabases;

  /// While Administración's search box has something typed, every card
  /// stays expanded regardless of its own collapse state — a match hiding
  /// behind a manually-collapsed card would make the search look broken.
  final bool forceExpanded;

  @override
  ConsumerState<ServerAdminCard> createState() => _ServerAdminCardState();
}

class _ServerAdminCardState extends ConsumerState<ServerAdminCard> {
  // Defaults open — matches the screen's behavior before this existed, so
  // adding it doesn't suddenly hide every already-configured server.
  bool _expanded = true;

  @override
  Widget build(BuildContext context) {
    final colors = context.appTheme.colors;
    final typography = context.appTheme.typography;
    final notifier = ref.read(serversProvider.notifier);
    final server = widget.server;
    final expanded = widget.forceExpanded || _expanded;

    return AppCard(
      padding: const EdgeInsets.all(AppSpacing.space4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              // Collapsible, same chevron/rotation pattern as Consulta's
              // sidebar tree — a server with 30+ databases took up the
              // whole screen with no way to tuck it away.
              InkWell(
                borderRadius: const BorderRadius.all(Radius.circular(6)),
                onTap: () => setState(() => _expanded = !_expanded),
                child: Padding(
                  padding: const EdgeInsets.all(2),
                  child: AnimatedRotation(
                    turns: expanded ? 0.25 : 0,
                    duration: const Duration(milliseconds: 120),
                    child: Icon(LucideIcons.chevron_right,
                        size: 16, color: colors.textMuted),
                  ),
                ),
              ),
              const SizedBox(width: 4),
              Icon(LucideIcons.server, color: colors.textMuted, size: 20),
              const SizedBox(width: AppSpacing.space2),
              Expanded(
                child: InlineEditableText(
                  value: server.name,
                  hint: 'Nombre del servidor',
                  style: typography.heading,
                  onChanged: (name) => notifier.renameServer(server.id, name),
                ),
              ),
              Text('${server.databases.length}',
                  style: typography.caption.copyWith(color: colors.textMuted)),
              const SizedBox(width: AppSpacing.space2),
              AppTag(
                  label: server.engine.label, variant: AppTagVariant.neutral),
              const SizedBox(width: AppSpacing.space3),
              // No right-click convention on this screen (unlike Consulta's
              // sidebar tree) — everything here is an explicit button, so
              // reordering gets one too instead of a hidden gesture.
              PopupMenuButton<_ServerCardMenuAction>(
                tooltip: 'Mover servidor',
                icon: Icon(LucideIcons.arrow_up_down,
                    size: 16, color: colors.textMuted),
                padding: EdgeInsets.zero,
                splashRadius: 18,
                itemBuilder: (context) => [
                  if (!widget.isFirst)
                    const PopupMenuItem(
                        value: _ServerCardMenuAction.moveToStart,
                        child: Text('Mover al inicio')),
                  if (!widget.isFirst)
                    const PopupMenuItem(
                        value: _ServerCardMenuAction.moveUp,
                        child: Text('Subir')),
                  if (!widget.isLast)
                    const PopupMenuItem(
                        value: _ServerCardMenuAction.moveDown,
                        child: Text('Bajar')),
                ],
                onSelected: (action) {
                  switch (action) {
                    case _ServerCardMenuAction.moveToStart:
                      notifier.moveServerToStart(server.id);
                    case _ServerCardMenuAction.moveUp:
                      notifier.moveServerUp(server.id);
                    case _ServerCardMenuAction.moveDown:
                      notifier.moveServerDown(server.id);
                  }
                },
              ),
              const SizedBox(width: AppSpacing.space1),
              AppIconButton(
                icon: LucideIcons.key,
                tooltip: 'Credenciales por defecto de este servidor',
                onPressed: () => showServerCredentialsDialog(context, ref, server),
              ),
              AppIconButton(
                icon: LucideIcons.trash_2,
                tooltip: 'Eliminar servidor',
                onPressed: () => showRemoveServerDialog(context, ref, server),
              ),
            ],
          ),
          if (expanded) ...[
            const SizedBox(height: AppSpacing.space3),
            for (final db in widget.visibleDatabases)
              Padding(
                key: ValueKey(db.id),
                padding: const EdgeInsets.only(bottom: AppSpacing.space1),
                child: DatabaseRow(server: server, database: db),
              ),
            const SizedBox(height: AppSpacing.space1),
            Wrap(
              spacing: AppSpacing.space2,
              runSpacing: AppSpacing.space1,
              children: [
                AppButton(
                  label: '+ Agregar base de datos',
                  variant: AppButtonVariant.ghost,
                  onPressed: () => showAddDatabaseDialog(context, ref, server),
                ),
              ],
            ),
          ],
        ],
      ),
    );
  }
}
