import 'package:flutter/material.dart';
import 'package:flutter_lucide/flutter_lucide.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../../core/theme/app_spacing.dart';
import '../../../../../core/theme/app_theme.dart';
import '../../../../../data/models/database_entry.dart';
import '../../../../../data/models/server.dart';
import '../../../../../data/providers/servers_providers.dart';
import '../../../../../shared/widgets/add_database_dialog.dart';
import '../../../../../shared/widgets/app_button.dart';
import '../../../../../shared/widgets/context_menu_label.dart';
import '../../../../../shared/widgets/database_add_icon.dart';
import '../../../../../shared/widgets/edit_server_dialog.dart';
import '../../../../../shared/widgets/remove_dialogs.dart';
import '../../../../../shared/widgets/server_credentials_dialog.dart';
import 'database_check_row.dart';
import 'host_group_node.dart';

/// Right-click on a server — reorder it relative to the others. Order is
/// just `serversProvider`'s own list order (see `servers_providers.dart`'s
/// `moveServer*` doc comment) — both this sidebar and Administración
/// render servers in that same order, so reordering here shows up there
/// too, and persists across restarts for free.
enum _ServerMenuAction { moveToStart, moveUp, moveDown }

class ServerNode extends ConsumerWidget {
  const ServerNode({
    super.key,
    required this.server,
    required this.isFirst,
    required this.isLast,
    required this.expanded,
    required this.onToggle,
  });

  final Server server;
  final bool isFirst;
  final bool isLast;
  final bool expanded;
  final VoidCallback onToggle;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colors = context.appTheme.colors;
    final typography = context.appTheme.typography;
    const rowRadius = BorderRadius.all(Radius.circular(8));
    Offset? tapPosition;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Material(
          color: Colors.transparent,
          borderRadius: rowRadius,
          child: InkWell(
            borderRadius: rowRadius,
            onTap: onToggle,
            onTapDown: (details) => tapPosition = details.globalPosition,
            onSecondaryTapDown: (details) =>
                tapPosition = details.globalPosition,
            onSecondaryTap: () =>
                _showContextMenu(context, ref, tapPosition!),
            child: Padding(
              padding: const EdgeInsets.symmetric(
                  horizontal: AppSpacing.space1, vertical: 6),
              child: Row(
                children: [
                  AnimatedRotation(
                    turns: expanded ? 0.25 : 0,
                    duration: const Duration(milliseconds: 120),
                    child: Icon(LucideIcons.chevron_right,
                        size: 14, color: colors.textMuted),
                  ),
                  const SizedBox(width: 2),
                  // Fixed color, no longer tied to "selected": a server row
                  // lighting up like it was itself "selected" just because a
                  // database inside it got picked was confusing — selection
                  // is a per-database thing (selectedServerIdProvider is
                  // purely cosmetic focus now, not query-relevant — see
                  // servers_providers.dart).
                  Icon(LucideIcons.server, size: 13, color: colors.accent.base),
                  const SizedBox(width: 6),
                  Expanded(
                    // Used to show `server.databases.first.host` as a
                    // quick-glance subtitle here — removed 2026-07-24,
                    // user-reported as actively misleading: a servidor
                    // routinely spans several real IPs (see [Server]'s doc
                    // comment and the host-group sub-nodes below), so
                    // pinning the label to whichever database happened to
                    // be added first implied a single-host identity the
                    // servidor doesn't have. No replacement — the real
                    // per-host breakdown lives in the `HostGroupNode`s
                    // below once expanded.
                    child: Text(
                      server.name,
                      overflow: TextOverflow.ellipsis,
                      style: typography.body.copyWith(
                        fontSize: 13,
                        fontWeight: FontWeight.w600,
                        color: colors.text,
                      ),
                    ),
                  ),
                  Text('${server.databases.length}',
                      style: typography.caption.copyWith(color: colors.textMuted)),
                  // Compact, same pattern as the per-database mode lock icon
                  // below — a full AppIconButton (36x36 tap target) is too
                  // bulky for this dense tree row.
                  Tooltip(
                    message: 'Editar servidor',
                    child: InkWell(
                      borderRadius: const BorderRadius.all(Radius.circular(6)),
                      onTap: () => showEditServerDialog(context, ref, server),
                      child: Padding(
                        padding: const EdgeInsets.all(4),
                        child: Icon(LucideIcons.pencil,
                            size: 13, color: colors.textMuted),
                      ),
                    ),
                  ),
                  Tooltip(
                    message: 'Agregar base de datos',
                    child: InkWell(
                      borderRadius: const BorderRadius.all(Radius.circular(6)),
                      onTap: () => showAddDatabaseDialog(context, ref, server),
                      child: Padding(
                        padding: const EdgeInsets.all(4),
                        child: DatabaseAddIcon(color: colors.accent.base),
                      ),
                    ),
                  ),
                  Tooltip(
                    message: 'Credenciales por defecto de este servidor',
                    child: InkWell(
                      borderRadius: const BorderRadius.all(Radius.circular(6)),
                      onTap: () =>
                          showServerCredentialsDialog(context, ref, server),
                      child: Padding(
                        padding: const EdgeInsets.all(4),
                        child: Icon(LucideIcons.key,
                            size: 13, color: colors.textMuted),
                      ),
                    ),
                  ),
                  Tooltip(
                    message: 'Eliminar servidor',
                    child: InkWell(
                      borderRadius: const BorderRadius.all(Radius.circular(6)),
                      onTap: () => showRemoveServerDialog(context, ref, server),
                      child: Padding(
                        padding: const EdgeInsets.all(4),
                        child: Icon(LucideIcons.trash_2,
                            size: 13, color: colors.textMuted),
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
        if (expanded)
          Padding(
            padding: const EdgeInsets.only(left: 24),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // Meaningless under "Consulta masiva" off — selection is
                // exclusive (one database app-wide) there, so there's
                // nothing "Todas"/"Ninguna" could sensibly do.
                if (ref.watch(massQueryModeProvider) &&
                    server.databases.isNotEmpty)
                  Align(
                    alignment: Alignment.centerRight,
                    child: AppButton(
                      label: server.databases.every((db) => db.selected)
                          ? 'Ninguna'
                          : 'Todas',
                      variant: AppButtonVariant.ghost,
                      onPressed: () {
                        final allSelected =
                            server.databases.every((db) => db.selected);
                        ref
                            .read(serversProvider.notifier)
                            .setAllDatabasesSelected(server.id, !allSelected);
                      },
                    ),
                  ),
                ..._buildDatabaseRows(server),
              ],
            ),
          ),
      ],
    );
  }

  /// Groups [server]'s databases by host into a [HostGroupNode] each —
  /// but only when the servidor actually spans more than one distinct
  /// host. The common case (one central host, several databases) keeps
  /// rendering as a flat list exactly like before; the extra tree level
  /// only earns its place once there's genuinely more than one IP to tell
  /// apart (2026-07-24, user request — couldn't find/discover databases
  /// on a second IP added to an existing servidor, and had no way to see
  /// which databases belonged to which IP in the tree).
  List<Widget> _buildDatabaseRows(Server server) {
    final byHost = <String, List<DatabaseEntry>>{};
    for (final db in server.databases) {
      byHost.putIfAbsent(db.host, () => []).add(db);
    }
    if (byHost.length <= 1) {
      return [
        for (final db in server.databases)
          DatabaseCheckRow(
            // Missing keys here were the real cause of "clicking one
            // database's refresh/load button affects a different one" —
            // without a key, Flutter matches this list's widgets to their
            // previous Elements purely by position, so any rebuild that
            // reorders/adds/removes a database (or even just re-renders
            // the list from a fresh `serversProvider` state) can silently
            // hand THIS db's row the state (and its `_loadRequested`/
            // `_expanded` flags) that used to belong to whatever database
            // was previously at this position. Keying by the database's
            // own id ties each row's local state to that specific
            // database, regardless of list order changes.
            key: ValueKey(db.id),
            serverId: server.id,
            server: server,
            database: db,
            engine: server.engine,
          ),
      ];
    }
    return [
      for (final entry in byHost.entries)
        HostGroupNode(
          key: ValueKey(entry.key),
          server: server,
          host: entry.key,
          databases: entry.value,
          engine: server.engine,
        ),
    ];
  }

  Future<void> _showContextMenu(
      BuildContext context, WidgetRef ref, Offset globalPosition) async {
    final action = await showPositionedMenu<_ServerMenuAction>(
      context,
      globalPosition,
      [
        if (!isFirst)
          const PopupMenuItem(
              value: _ServerMenuAction.moveToStart,
              child: ContextMenuLabel(
                  LucideIcons.chevrons_up, 'Mover al inicio')),
        if (!isFirst)
          const PopupMenuItem(
              value: _ServerMenuAction.moveUp,
              child: ContextMenuLabel(LucideIcons.chevron_up, 'Subir')),
        if (!isLast)
          const PopupMenuItem(
              value: _ServerMenuAction.moveDown,
              child: ContextMenuLabel(LucideIcons.chevron_down, 'Bajar')),
      ],
    );
    if (action == null) return;
    final notifier = ref.read(serversProvider.notifier);
    switch (action) {
      case _ServerMenuAction.moveToStart:
        notifier.moveServerToStart(server.id);
      case _ServerMenuAction.moveUp:
        notifier.moveServerUp(server.id);
      case _ServerMenuAction.moveDown:
        notifier.moveServerDown(server.id);
    }
  }
}
