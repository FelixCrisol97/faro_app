import 'package:flutter/material.dart';
import 'package:flutter_lucide/flutter_lucide.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../../core/constants/db_engine.dart';
import '../../../../../core/theme/app_spacing.dart';
import '../../../../../core/theme/app_theme.dart';
import '../../../../../data/models/database_entry.dart';
import '../../../../../data/models/server.dart';
import '../../../../../data/providers/servers_providers.dart';
import '../../../../../shared/widgets/app_button.dart';
import '../../../../../shared/widgets/app_dialog.dart';
import '../../../../../shared/widgets/context_menu_label.dart';
import '../../../../../shared/widgets/discover_databases_dialog.dart';
import '../../../../../shared/widgets/edit_database_dialog.dart';
import '../../../../../shared/widgets/remove_dialogs.dart';
import '../../../../../shared/widgets/server_credentials_dialog.dart';
import '../../../../../shared/widgets/test_connection_action.dart';
import '../../../../../shared/windowing/query_window_launcher.dart';
import '../../../application/query_tabs_providers.dart';
import '../../../application/schema_explorer_provider.dart';
import 'schema_objects_tree.dart';

class DatabaseCheckRow extends ConsumerStatefulWidget {
  const DatabaseCheckRow(
      {super.key,
      required this.serverId,
      required this.server,
      required this.database,
      required this.engine});

  final String serverId;
  final Server server;
  final DatabaseEntry database;
  final DbEngine engine;

  @override
  ConsumerState<DatabaseCheckRow> createState() => _DatabaseCheckRowState();
}

/// Right-click on a database's name — open it as a query tab or a separate
/// native window, or (2026-07-24) look for more databases sharing this
/// one's IP.
enum _DatabaseMenuAction { openTab, openWindow, discoverMore }

class _DatabaseCheckRowState extends ConsumerState<DatabaseCheckRow> {
  // Both start false: expanding the node is separate from actually browsing
  // the engine's catalog — "Cargar estructura" is an explicit action, not
  // something that fires just from opening the tree node. Per-category
  // fetching (and its own loading/refresh state) lives in `SchemaTypeGroup`
  // now — see schema_explorer_provider.dart.
  bool _expanded = false;
  bool _loadRequested = false;

  SchemaExplorerKey get _key =>
      (serverId: widget.serverId, databaseId: widget.database.id);

  /// "Consulta masiva" on: today's independent toggle, any number of
  /// databases across any servers. Off (the default): exclusive selection —
  /// picking a new database clears every other one app-wide
  /// (`ServersNotifier.selectOnlyDatabase`) instead of just toggling this
  /// one — and, since there's now exactly one thing to look at, also
  /// expands this row and kicks off its schema load automatically, the
  /// same way tapping "Cargar estructura" would (`_loadRequested = true`
  /// feeds the same cached `schemaExplorerProvider` either way — no
  /// separate caching logic needed). Tapping an already-selected database
  /// deselects it, back to nothing selected.
  void _onSelectTap(DatabaseEntry database) {
    final massQueryMode = ref.read(massQueryModeProvider);
    final notifier = ref.read(serversProvider.notifier);
    if (massQueryMode) {
      notifier.toggleDatabaseSelected(
          widget.serverId, database.id, !database.selected);
      return;
    }
    if (database.selected) {
      notifier.toggleDatabaseSelected(widget.serverId, database.id, false);
      return;
    }
    notifier.selectOnlyDatabase(widget.serverId, database.id);
    setState(() {
      _expanded = true;
      _loadRequested = true;
    });
  }

  /// Right-click on a database's name — a single-item menu, not a new
  /// `ObjectAction` (that enum is scoped to schema objects, one level down
  /// the tree; a database row has no other actions to share a menu with).
  Future<void> _showDatabaseContextMenu(
      Offset globalPosition, DatabaseEntry database) async {
    final action = await showPositionedMenu<_DatabaseMenuAction>(
      context,
      globalPosition,
      const [
        PopupMenuItem(
            value: _DatabaseMenuAction.openTab,
            child: ContextMenuLabel(LucideIcons.panel_top, 'Abrir en pestaña')),
        PopupMenuItem(
            value: _DatabaseMenuAction.openWindow,
            child: ContextMenuLabel(
                LucideIcons.external_link, 'Abrir en nueva ventana')),
        PopupMenuItem(
            value: _DatabaseMenuAction.discoverMore,
            child: ContextMenuLabel(
                LucideIcons.database_zap, 'Descubrir más bases de datos en esta IP')),
      ],
    );
    if (action == null || !mounted) return;
    switch (action) {
      case _DatabaseMenuAction.openTab:
        ref.read(queryTabsProvider.notifier).openTab(
            serverId: widget.serverId, databaseId: database.id);
      case _DatabaseMenuAction.openWindow:
        await openQueryWindow(
            serverId: widget.serverId, databaseId: database.id);
      case _DatabaseMenuAction.discoverMore:
        await showDiscoverDatabasesDialog(context, ref, widget.server,
            from: database);
    }
  }

  @override
  Widget build(BuildContext context) {
    final colors = context.appTheme.colors;
    final typography = context.appTheme.typography;
    final database = widget.database;
    // Selection used to be a Checkbox — swapped for the same tinted-row
    // treatment ServerNode already uses for its active server, so a
    // selected database reads the same way the rest of the tree does
    // instead of standing out as a form control.
    final rowColor = database.selected ? colors.accent.softText : colors.textMuted;
    const rowRadius = BorderRadius.all(Radius.circular(8));
    Offset? tapPosition;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Material(
          color: database.selected ? colors.accent.soft : Colors.transparent,
          borderRadius: rowRadius,
          // Used to be one big InkWell wrapping the whole row (chevron,
          // name, refresh, lock all as descendants) with "select database"
          // as its onTap — nested InkWells inside that gave the refresh
          // button an ambiguous, unreliable hit target: clicks meant for it
          // kept triggering the outer row's select instead. Now each
          // control (chevron / name / refresh / lock) is its own InkWell as
          // a direct sibling in the Row below, not nested inside another
          // one — no overlapping tap regions left to compete over. The
          // Material here only supplies the shared tinted background, no
          // tap handler of its own.
          child: Padding(
            padding: const EdgeInsets.symmetric(
                horizontal: AppSpacing.space1, vertical: 6),
            child: Row(
              children: [
                InkWell(
                  borderRadius: const BorderRadius.all(Radius.circular(6)),
                  onTap: () => setState(() => _expanded = !_expanded),
                  child: Padding(
                    padding: const EdgeInsets.all(2),
                    child: AnimatedRotation(
                      turns: _expanded ? 0.25 : 0,
                      duration: const Duration(milliseconds: 120),
                      child:
                          Icon(LucideIcons.chevron_right, size: 12, color: rowColor),
                    ),
                  ),
                ),
                const SizedBox(width: 4),
                Icon(LucideIcons.database, size: 13, color: rowColor),
                const SizedBox(width: 6),
                Expanded(
                  child: InkWell(
                    borderRadius: const BorderRadius.all(Radius.circular(6)),
                    onTap: () => _onSelectTap(database),
                    onTapDown: (details) => tapPosition = details.globalPosition,
                    onSecondaryTapDown: (details) =>
                        tapPosition = details.globalPosition,
                    onSecondaryTap: () => _showDatabaseContextMenu(tapPosition!, database),
                    child: Text(
                      database.name,
                      overflow: TextOverflow.ellipsis,
                      style: typography.body.copyWith(
                        fontSize: 13,
                        color: database.selected ? colors.accent.softText : colors.text,
                      ),
                    ),
                  ),
                ),
                // Compact test-connection trigger + status — status itself
                // is shared Riverpod state (DatabaseEntry.testStatus/
                // testError, same as Administración's _StatusPill reads),
                // so a test kicked off from either screen updates both.
                // Tapping always retries except mid-flight; the tooltip
                // carries the error text on hover instead of a separate
                // copy-to-clipboard affordance (too fine-grained a control
                // for this dense row).
                Tooltip(
                  message: switch (database.testStatus) {
                    ConnectionTestStatus.idle => 'Probar conexión',
                    ConnectionTestStatus.testing => 'Probando…',
                    ConnectionTestStatus.connected =>
                      'Conectado (clic para volver a probar)',
                    ConnectionTestStatus.failed =>
                      '${database.testError ?? 'Error desconocido'}\n(clic para volver a probar)',
                  },
                  child: InkWell(
                    borderRadius: const BorderRadius.all(Radius.circular(6)),
                    onTap: database.testStatus == ConnectionTestStatus.testing
                        ? null
                        : () =>
                            testDatabaseConnection(ref, widget.server, database),
                    child: Padding(
                      padding: const EdgeInsets.all(4),
                      child: switch (database.testStatus) {
                        ConnectionTestStatus.idle => Icon(LucideIcons.plug,
                            size: 13, color: colors.textMuted),
                        ConnectionTestStatus.testing => SizedBox(
                            width: 13,
                            height: 13,
                            child: CircularProgressIndicator(
                                strokeWidth: 2, color: colors.textMuted),
                          ),
                        ConnectionTestStatus.connected => Icon(
                            LucideIcons.circle_check,
                            size: 13,
                            color: colors.success.base),
                        ConnectionTestStatus.failed => Icon(
                            LucideIcons.circle_alert,
                            size: 13,
                            color: colors.error.base),
                      },
                    ),
                  ),
                ),
                // Every database always shows its mode, not just the risky one
                // — green closed lock = protected (Solo lectura), amber open
                // lock = unrestricted, so 🔒 never means "something's wrong."
                // Tappable: toggles the mode right here, with the same confirm
                // dialog Administración uses when switching TO unrestricted.
                Tooltip(
                  message: database.mode == ServerMode.readOnly
                      ? 'Solo lectura (clic para cambiar)'
                      : 'Consultas sin restricciones (clic para cambiar)',
                  child: InkWell(
                    borderRadius: const BorderRadius.all(Radius.circular(6)),
                    onTap: () => _onToggleMode(context, ref),
                    child: Padding(
                      padding: const EdgeInsets.all(4),
                      child: AnimatedSwitcher(
                        duration: const Duration(milliseconds: 200),
                        transitionBuilder: (child, animation) =>
                            ScaleTransition(scale: animation, child: child),
                        child: database.mode == ServerMode.readOnly
                            ? Icon(LucideIcons.lock,
                                key: const ValueKey('locked'),
                                size: 13,
                                color: colors.success.base)
                            : Icon(LucideIcons.lock_open,
                                key: const ValueKey('unlocked'),
                                size: 13,
                                color: colors.warn.base),
                      ),
                    ),
                  ),
                ),
                Tooltip(
                  message: 'Editar base de datos',
                  child: InkWell(
                    borderRadius: const BorderRadius.all(Radius.circular(6)),
                    onTap: () => showEditDatabaseDialog(
                        context, ref, widget.server, database),
                    child: Padding(
                      padding: const EdgeInsets.all(4),
                      child: Icon(LucideIcons.pencil,
                          size: 13, color: colors.textMuted),
                    ),
                  ),
                ),
                Tooltip(
                  message: 'Credenciales solo para esta base de datos (opcional)',
                  child: InkWell(
                    borderRadius: const BorderRadius.all(Radius.circular(6)),
                    onTap: () => showDatabaseCredentialsDialog(
                        context, ref, widget.server, database),
                    child: Padding(
                      padding: const EdgeInsets.all(4),
                      child:
                          Icon(LucideIcons.key, size: 13, color: colors.textMuted),
                    ),
                  ),
                ),
                Tooltip(
                  message: 'Eliminar base de datos',
                  child: InkWell(
                    borderRadius: const BorderRadius.all(Radius.circular(6)),
                    onTap: () => showRemoveDatabaseDialog(
                        context, ref, widget.server, database),
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
        if (_expanded)
          Padding(
            padding: const EdgeInsets.only(left: 30, bottom: 2),
            child: _loadRequested
                ? SchemaObjectsTree(dbKey: _key, engine: widget.engine)
                // AppButton's Row sizes to fit its icon+label tightly
                // (mainAxisSize.min) and doesn't shrink or ellipsize on its
                // own — "Cargar estructura" plus its icon is long enough
                // that, combined with this row's indentation, it didn't fit
                // at the sidebar's narrower widths, causing a RenderFlex
                // ("RIGHT OVERFLOWED") error. FittedBox scales the whole
                // button down just enough to fit instead of letting it
                // overflow — a no-op at normal sidebar widths.
                : FittedBox(
                    fit: BoxFit.scaleDown,
                    alignment: Alignment.centerLeft,
                    child: AppButton(
                      label: 'Cargar estructura',
                      icon: LucideIcons.list_tree,
                      variant: AppButtonVariant.ghost,
                      onPressed: () => setState(() => _loadRequested = true),
                    ),
                  ),
          ),
      ],
    );
  }

  Future<void> _onToggleMode(BuildContext context, WidgetRef ref) async {
    final database = widget.database;
    final notifier = ref.read(serversProvider.notifier);
    if (database.mode == ServerMode.development) {
      notifier.setDatabaseMode(widget.serverId, database.id, ServerMode.readOnly);
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
      notifier.setDatabaseMode(
          widget.serverId, database.id, ServerMode.development);
    }
  }
}
