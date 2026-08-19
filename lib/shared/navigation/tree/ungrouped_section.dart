import 'package:flutter/material.dart';
import 'package:flutter_lucide/flutter_lucide.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_spacing.dart';
import '../../../core/theme/app_theme.dart';
import '../../../data/providers/servers_providers.dart';
import '../../widgets/add_database_dialog.dart';
import 'database_check_row.dart';
import 'tree_drag.dart';

/// "Sin grupo" — databases not assigned to a server yet (2026-08-12, user
/// request: be able to create/park a database before deciding which group
/// it belongs to, then drag it into place later — see
/// `ServersNotifier`'s "Sin grupo" section doc comment). **Fully queryable
/// from day one (2026-08-13, user-confirmed)** — engine/credentials live
/// on [DatabaseEntry] itself now, not the servidor, so an ungrouped
/// database is never a second-class, non-connectable state; each row here
/// is the exact same [DatabaseCheckRow] `ServerNode` uses, just with
/// `serverId`/`server` both null — no separate near-duplicate row widget
/// to maintain.
class UngroupedSection extends ConsumerWidget {
  const UngroupedSection({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final typography = context.appTheme.typography;
    final colors = context.appTheme.colors;
    final databases = ref.watch(ungroupedDatabasesProvider);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        TreeDropTarget(
          onWillAccept: (payload) =>
              payload is DatabaseDragPayload && payload.serverId != null,
          onAccept: (payload) {
            final p = payload as DatabaseDragPayload;
            ref
                .read(serversProvider.notifier)
                .moveDatabaseToUngrouped(p.serverId!, p.databaseId);
          },
          child: Padding(
            padding: const EdgeInsets.symmetric(
                horizontal: AppSpacing.space1, vertical: 4),
            child: Row(
              children: [
                Expanded(
                  child: Text('Sin grupo',
                      style: typography.caption.copyWith(letterSpacing: 1.1)),
                ),
                Tooltip(
                  message: 'Agregar base de datos sin grupo',
                  child: InkWell(
                    borderRadius: const BorderRadius.all(Radius.circular(6)),
                    onTap: () => showAddDatabaseDialog(context, ref, null),
                    child: Padding(
                      padding: const EdgeInsets.all(4),
                      child: Icon(LucideIcons.plus,
                          size: 13, color: colors.accent.base),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
        if (databases.isEmpty)
          Padding(
            padding: const EdgeInsets.only(left: 20, bottom: 4),
            child: Text('Ninguna',
                style: typography.caption.copyWith(color: colors.textMuted)),
          )
        else
          for (final db in databases)
            DatabaseCheckRow(
              key: ValueKey(db.id),
              serverId: null,
              server: null,
              database: db,
            ),
        Padding(
          padding: const EdgeInsets.only(left: 20),
          child: TreeTrailingDropZone(
            onWillAccept: (payload) => payload is DatabaseDragPayload,
            onAccept: (payload) {
              final p = payload as DatabaseDragPayload;
              final notifier = ref.read(serversProvider.notifier);
              if (p.serverId == null) {
                notifier.reorderUngroupedDatabase(
                    p.databaseId, databases.length);
              } else {
                notifier.moveDatabaseToUngrouped(p.serverId!, p.databaseId,
                    targetIndex: databases.length);
              }
            },
          ),
        ),
      ],
    );
  }
}
