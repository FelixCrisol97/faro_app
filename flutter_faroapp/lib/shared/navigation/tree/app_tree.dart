import 'package:flutter/material.dart';
import 'package:flutter_lucide/flutter_lucide.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_spacing.dart';
import '../../../core/theme/app_theme.dart';
import '../../../data/models/database_entry.dart';
import '../../../data/providers/servers_providers.dart';
import '../../widgets/add_database_dialog.dart';
import '../../widgets/app_button.dart';
import '../../widgets/app_segmented_control.dart';
import '../../widgets/discover_databases_dialog.dart';
import '../../widgets/server_config_import_export.dart';
import '../side_panel/side_panel.dart';
import 'server_node.dart';
import 'tree_drag.dart';
import 'tree_providers.dart';
import 'ungrouped_section.dart';

enum _ConfigMenuAction { importConfig, exportConfig }

/// The app's permanent left pane (SSMS/pgAdmin-style object explorer) — a
/// single collapsible tree of servers/databases, each expandable to reveal
/// its databases (with selection checkboxes) and, below that, their schema.
///
/// **2026-08-12: absorbed `features/administracion/`.** What used to be a
/// separate full-screen "Administración" tab (card-per-server, its own
/// search box, its own persisted collapse state) is now just this same
/// tree — every action it offered (rename, credentials, mode, delete,
/// discover, reorder) was already available here too (see `server_node.dart`/
/// `database_check_row.dart`'s inline icons and right-click menus), so
/// nothing needed to be rebuilt, just ported: the search box below, and
/// `collapsedServerIdsProvider` (`tree_providers.dart`, moved from
/// Administración) replacing what used to be this tree's own
/// session-only `_expanded` field.
///
/// The tree itself lives across several files under this same directory —
/// one per level/responsibility (server row, database row, schema
/// category, object row, column list) — this file only keeps the shell
/// (header, search, mass-query toggle, "Sin grupo", resize handle, and the
/// bottom icon strip that opens Historial/Favoritos/Apariencia as an
/// overlay panel — see `side_panel_overlay.dart`).
///
/// The background fill has to come from a `Material` (not just a
/// `Container` color), or `Checkbox`/`InkWell` below can't find a Material
/// ancestor to paint their own background/ink splashes on — Flutter throws
/// "ListTile background color or ink splashes may be invisible" otherwise.
class AppTree extends ConsumerStatefulWidget {
  const AppTree({super.key});

  @override
  ConsumerState<AppTree> createState() => _AppTreeState();
}

class _AppTreeState extends ConsumerState<AppTree> {
  final _searchController = TextEditingController();
  String _query = '';

  // Session-only — user-requested resizing, dragged via the handle at the
  // right edge. Default widened ~10 characters' worth (roughly 7px/char at
  // this tree's font size) so typical database/server names fit with less
  // truncation before anyone even touches the drag handle.
  double _width = 302;
  static const double _minWidth = 180;
  static const double _maxWidth = 480;

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  bool _matchesQuery(DatabaseEntry db, String query) =>
      db.name.toLowerCase().contains(query) ||
      db.databaseName.toLowerCase().contains(query);

  @override
  Widget build(BuildContext context) {
    final colors = context.appTheme.colors;
    final typography = context.appTheme.typography;
    final servers = ref.watch(serverListProvider);
    final massQueryMode = ref.watch(massQueryModeProvider);
    final collapsed = ref.watch(collapsedServerIdsProvider);

    final query = _query.trim().toLowerCase();
    // Same matching rule Administración's own search used: a server shows
    // if its own name matches, or any of its databases' alias/real name
    // does — narrowing which *rows within* a matched server show is not
    // ported (every database in a matching server still shows), a
    // deliberate simplification since that needs a `visibleDatabases` plumb
    // through `ServerNode` for a small benefit over "just show the whole
    // matching server".
    final visibleServers = query.isEmpty
        ? servers
        : servers.where((s) =>
            s.name.toLowerCase().contains(query) ||
            s.databases.any((db) => _matchesQuery(db, query)));

    return Row(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SizedBox(
          width: _width,
          child: Material(
            color: colors.surface,
            child: Padding(
              padding: const EdgeInsets.all(AppSpacing.space2),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Padding(
                    padding: const EdgeInsets.symmetric(
                        horizontal: AppSpacing.space1, vertical: 4),
                    child: TextField(
                      controller: _searchController,
                      onChanged: (value) => setState(() => _query = value),
                      style: typography.body.copyWith(fontSize: 13),
                      decoration: InputDecoration(
                        isDense: true,
                        hintText: 'Buscar servidor o base de datos…',
                        prefixIcon:
                            Icon(LucideIcons.search, size: 14, color: colors.textMuted),
                        suffixIcon: _query.isEmpty
                            ? null
                            : InkWell(
                                onTap: () => setState(() {
                                  _searchController.clear();
                                  _query = '';
                                }),
                                child: Icon(LucideIcons.x,
                                    size: 14, color: colors.textMuted),
                              ),
                      ),
                    ),
                  ),
                  // "+ Agregar base de datos" / Configuración / los paneles
                  // de Historial-Favoritos-Apariencia — movidos arriba
                  // 2026-08-13 (usuario: "sería mas visible... arriba y no
                  // abajo"; antes vivían debajo de la lista de servidores,
                  // que puede ser larga y empujarlos fuera de la vista sin
                  // hacer scroll). "Registrar servidor" (crear un grupo
                  // vacío) se quitó el mismo día — agrupar es opcional y
                  // pasa después, arrastrando bases de datos entre sí (ver
                  // el comentario de `Server`); una base de datos ya es
                  // completamente usable sin ningún servidor.
                  Row(
                    children: [
                      Expanded(
                        // AppButton's Row doesn't shrink/ellipsize on its
                        // own (same reasoning as the "Cargar estructura"
                        // button elsewhere in this file) — scales down
                        // instead of overflowing when this row also shares
                        // space with the Configuración icon.
                        child: FittedBox(
                          fit: BoxFit.scaleDown,
                          alignment: Alignment.centerLeft,
                          child: AppButton(
                            label: '+ Agregar base de datos',
                            variant: AppButtonVariant.ghost,
                            onPressed: () =>
                                showAddDatabaseDialog(context, ref, null),
                          ),
                        ),
                      ),
                      // "Configuración" — real gap reported 2026-08-13: this
                      // (Importar/Exportar configuración) used to be two
                      // page-level buttons on the now-deleted Administración
                      // screen and had nowhere to live after that screen was
                      // absorbed into the tree — "no veo la opcion de
                      // configuracion" — until this menu was added.
                      PopupMenuButton<_ConfigMenuAction>(
                        tooltip: 'Configuración',
                        icon: Icon(LucideIcons.settings,
                            size: 16, color: colors.textMuted),
                        padding: EdgeInsets.zero,
                        splashRadius: 18,
                        itemBuilder: (context) => const [
                          PopupMenuItem(
                              value: _ConfigMenuAction.importConfig,
                              child: Text('Importar configuración')),
                          PopupMenuItem(
                              value: _ConfigMenuAction.exportConfig,
                              child: Text('Exportar configuración')),
                        ],
                        onSelected: (action) {
                          switch (action) {
                            case _ConfigMenuAction.importConfig:
                              importServerConfig(context, ref);
                            case _ConfigMenuAction.exportConfig:
                              exportServerConfig(context, ref);
                          }
                        },
                      ),
                    ],
                  ),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      for (final panel in SidePanel.values)
                        _PanelIconButton(panel: panel),
                    ],
                  ),
                  const SizedBox(height: AppSpacing.space1),
                  const Divider(height: 1),
                  Padding(
                    padding: const EdgeInsets.symmetric(
                        horizontal: AppSpacing.space1, vertical: 4),
                    child: Row(
                      children: [
                        // FittedBox: the segmented control's two labels
                        // don't shrink/ellipsize on their own (same
                        // reasoning as the "Cargar estructura" button
                        // above) — scales down instead of overflowing at
                        // the tree's narrower widths.
                        Expanded(
                          child: FittedBox(
                            fit: BoxFit.scaleDown,
                            alignment: Alignment.centerLeft,
                            child: AppSegmentedControl<bool>(
                              value: massQueryMode,
                              options: const [
                                AppSegmentedOption(
                                    value: false, label: 'Individual'),
                                AppSegmentedOption(
                                    value: true, label: 'Masiva'),
                              ],
                              onChanged: (value) {
                                ref
                                    .read(massQueryModeProvider.notifier)
                                    .state = value;
                                // Explicit user choice: turning mass mode
                                // off starts from a clean slate (nothing
                                // selected), not "keep whatever was
                                // selected last".
                                if (!value) {
                                  ref
                                      .read(serversProvider.notifier)
                                      .clearAllSelections();
                                }
                              },
                            ),
                          ),
                        ),
                        // Only relevant in mass-query mode — mirrors
                        // ServerNode's own "Todas/Ninguna" mass-query-only
                        // visibility, but stays visible-and-disabled (not
                        // hidden) when the selection is empty, since that
                        // emptiness is a transient selection state right
                        // here in this same panel, not a structural
                        // absence — a tooltip explains why it's off
                        // instead of the button just vanishing. Its own
                        // widget (`_MassDiscoverButton`, below) so
                        // watching `selectedQueryTargetsProvider` — which
                        // changes on every single database checkbox
                        // toggled anywhere in the tree — only rebuilds
                        // this one small button, not the whole tree.
                        if (massQueryMode) ...const [
                          SizedBox(width: AppSpacing.space1),
                          _MassDiscoverButton(),
                        ],
                      ],
                    ),
                  ),
                  Expanded(
                    child: ListView(
                      // Flutter's default desktop scrollbar renders inside
                      // the ListView's own viewport, right at its edge —
                      // with no padding reserved for it, its thumb/track
                      // overlaps whatever sits flush against the right edge
                      // of each row (reported by the user: the schema
                      // category's refresh icon and each database's
                      // read-only lock icon both got partly covered by it).
                      padding: const EdgeInsets.only(right: AppSpacing.space3),
                      children: [
                        for (final (index, server) in visibleServers.indexed)
                          ServerNode(
                            key: ValueKey(server.id),
                            server: server,
                            isFirst: index == 0,
                            isLast: index == servers.length - 1,
                            // A match hiding behind a manually-collapsed
                            // node would make the search look broken —
                            // force every node open while there's something
                            // typed, same as Administración's own search
                            // did.
                            expanded: query.isNotEmpty ||
                                !collapsed.contains(server.id),
                            onToggle: () {
                              ref
                                  .read(selectedServerIdProvider.notifier)
                                  .state = server.id;
                              ref
                                  .read(collapsedServerIdsProvider.notifier)
                                  .toggle(server.id);
                            },
                          ),
                        if (query.isEmpty)
                          TreeTrailingDropZone(
                            onWillAccept: (payload) =>
                                payload is ServerDragPayload,
                            onAccept: (payload) {
                              final p = payload as ServerDragPayload;
                              ref
                                  .read(serversProvider.notifier)
                                  .reorderServer(
                                      p.serverId, servers.length - 1);
                            },
                          ),
                        const SizedBox(height: AppSpacing.space2),
                        if (query.isEmpty) const UngroupedSection(),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
        _ResizeHandle(
          onDrag: (dx) => setState(() {
            _width = (_width + dx).clamp(_minWidth, _maxWidth);
          }),
        ),
      ],
    );
  }
}

/// One of the 3 bottom icons that open Historial/Favoritos/Apariencia as
/// an overlay panel (`side_panel_overlay.dart`) — replaces what used to be
/// 3 of `AppNavBar`'s 5 top tabs.
class _PanelIconButton extends ConsumerWidget {
  const _PanelIconButton({required this.panel});

  final SidePanel panel;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final active = ref.watch(activeSidePanelProvider) == panel;
    final colors = context.appTheme.colors;
    return Tooltip(
      message: panel.label,
      child: InkWell(
        borderRadius: const BorderRadius.all(Radius.circular(6)),
        onTap: () => ref.read(activeSidePanelProvider.notifier).state =
            active ? null : panel,
        child: Padding(
          padding: const EdgeInsets.all(8),
          child: Icon(panel.icon,
              size: 16, color: active ? colors.accent.base : colors.textMuted),
        ),
      ),
    );
  }
}

/// "Descubrir en todas las IPs seleccionadas" — extracted into its own
/// widget 2026-08-13 so watching `selectedQueryTargetsProvider` (which
/// changes on every single database checkbox toggled anywhere in the
/// tree) only rebuilds this one small button instead of the whole tree,
/// which used to watch it at the top of `AppTree.build()` just for this.
class _MassDiscoverButton extends ConsumerWidget {
  const _MassDiscoverButton();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final massQueryTargets = ref.watch(selectedQueryTargetsProvider);
    final massQueryHostCount = {
      for (final t in massQueryTargets) (t.server?.id, t.database.host)
    }.length;
    return AppIconButton(
      icon: LucideIcons.database_zap,
      tooltip: massQueryTargets.isEmpty
          ? 'Selecciona bases de datos en modo '
              '"Masiva" para descubrir en todas sus '
              'IPs a la vez'
          : (massQueryHostCount == 1
              ? 'Descubrir más bases de datos en la '
                  'IP seleccionada'
              : 'Descubrir más bases de datos en '
                  'las $massQueryHostCount IPs '
                  'seleccionadas'),
      onPressed: massQueryTargets.isEmpty
          ? null
          : () => showDiscoverForMassQueryDialog(context, ref),
    );
  }
}

/// A thin draggable strip at the tree's right edge — highlights on hover so
/// it reads as draggable, widens the hit target beyond its visible width (a
/// 2px line is correct-looking but nearly unclickable) via a wider
/// invisible `GestureDetector`.
class _ResizeHandle extends StatefulWidget {
  const _ResizeHandle({required this.onDrag});

  final ValueChanged<double> onDrag;

  @override
  State<_ResizeHandle> createState() => _ResizeHandleState();
}

class _ResizeHandleState extends State<_ResizeHandle> {
  bool _hovering = false;

  @override
  Widget build(BuildContext context) {
    final colors = context.appTheme.colors;
    return MouseRegion(
      cursor: SystemMouseCursors.resizeLeftRight,
      onEnter: (_) => setState(() => _hovering = true),
      onExit: (_) => setState(() => _hovering = false),
      child: GestureDetector(
        behavior: HitTestBehavior.translucent,
        onHorizontalDragUpdate: (details) => widget.onDrag(details.delta.dx),
        child: SizedBox(
          width: 6,
          child: Center(
            child: Container(
              width: 2,
              color: _hovering ? colors.accent.base : colors.border,
            ),
          ),
        ),
      ),
    );
  }
}
