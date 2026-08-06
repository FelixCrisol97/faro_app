import 'package:flutter/material.dart';
import 'package:flutter_lucide/flutter_lucide.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/theme/app_spacing.dart';
import '../../../../core/theme/app_theme.dart';
import '../../../../data/providers/servers_providers.dart';
import '../../../../shared/widgets/add_server_dialog.dart';
import '../../../../shared/widgets/app_button.dart';
import '../../../../shared/widgets/app_segmented_control.dart';
import '../../../../shared/widgets/discover_databases_dialog.dart';
import 'sidebar/server_node.dart';

/// README.md "Sidebar" — a single collapsible tree (SSMS/pgAdmin-style
/// object explorer), not the earlier fixed "Servidores" list plus a
/// separate always-visible "Bases de datos" section for whichever server
/// was selected. Each server is a node you expand to reveal its databases
/// (with selection checkboxes) nested underneath.
///
/// The tree itself lives across several files under `widgets/sidebar/` —
/// one per level/responsibility (server row, database row, schema
/// category, object row, column list) — this file only keeps the sidebar's
/// own shell (header, mass-query toggle, resize handle).
///
/// The background fill has to come from a `Material` (not just a
/// `Container` color), or `Checkbox`/`InkWell` below can't find a Material
/// ancestor to paint their own background/ink splashes on — Flutter throws
/// "ListTile background color or ink splashes may be invisible" otherwise.
class ServerSidebar extends ConsumerStatefulWidget {
  const ServerSidebar({super.key});

  @override
  ConsumerState<ServerSidebar> createState() => _ServerSidebarState();
}

class _ServerSidebarState extends ConsumerState<ServerSidebar> {
  final Set<String> _expanded = {};
  bool _seeded = false;

  // Session-only (resets on restart, like the tree's expansion state above)
  // — user-requested resizing, dragged via the handle at the right edge.
  // Default widened ~10 characters' worth (roughly 7px/char at this
  // sidebar's font size) so typical database/server names fit with less
  // truncation before anyone even touches the drag handle.
  double _width = 302;
  static const double _minWidth = 180;
  static const double _maxWidth = 480;

  @override
  Widget build(BuildContext context) {
    final colors = context.appTheme.colors;
    final typography = context.appTheme.typography;
    final servers = ref.watch(serversProvider);
    final selectedServer = ref.watch(selectedServerProvider);
    final massQueryMode = ref.watch(massQueryModeProvider);
    final massQueryTargets = ref.watch(selectedQueryTargetsProvider);
    final massQueryHostCount = {
      for (final t in massQueryTargets) (t.server.id, t.database.host)
    }.length;

    // Expand the active server the first time there's something to show —
    // after that, expansion is fully user-controlled.
    if (!_seeded && selectedServer != null) {
      _seeded = true;
      _expanded.add(selectedServer.id);
    }

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
                    child: Text('Servidores',
                        style:
                            typography.caption.copyWith(letterSpacing: 1.1)),
                  ),
                  Padding(
                    padding: const EdgeInsets.symmetric(
                        horizontal: AppSpacing.space1, vertical: 4),
                    child: Row(
                      children: [
                        // FittedBox: the segmented control's two labels
                        // don't shrink/ellipsize on their own (same
                        // reasoning as the "Cargar estructura" button
                        // above) — scales down instead of overflowing at
                        // the sidebar's narrower widths.
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
                        // instead of the button just vanishing.
                        if (massQueryMode) ...[
                          const SizedBox(width: AppSpacing.space1),
                          AppIconButton(
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
                                : () =>
                                    showDiscoverForMassQueryDialog(context, ref),
                          ),
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
                        for (final (index, server) in servers.indexed)
                          ServerNode(
                            key: ValueKey(server.id),
                            server: server,
                            isFirst: index == 0,
                            isLast: index == servers.length - 1,
                            expanded: _expanded.contains(server.id),
                            onToggle: () {
                              ref
                                  .read(selectedServerIdProvider.notifier)
                                  .state = server.id;
                              setState(() {
                                if (_expanded.contains(server.id)) {
                                  _expanded.remove(server.id);
                                } else {
                                  _expanded.add(server.id);
                                }
                              });
                            },
                          ),
                      ],
                    ),
                  ),
                  const SizedBox(height: AppSpacing.space1),
                  AppButton(
                    label: '+ Registrar servidor',
                    variant: AppButtonVariant.ghost,
                    onPressed: () => showAddServerDialog(context, ref),
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

/// A thin draggable strip at the sidebar's right edge — highlights on hover
/// so it reads as draggable, widens the hit target beyond its visible width
/// (a 2px line is correct-looking but nearly unclickable) via a wider
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
