import 'package:flutter/material.dart';
import 'package:flutter_lucide/flutter_lucide.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_shadows.dart';
import '../../../core/theme/app_spacing.dart';
import '../../../core/theme/app_theme.dart';
import '../../../features/apariencia/presentation/apariencia_screen.dart';
import '../../../features/favoritos/presentation/favoritos_screen.dart';
import '../../../features/historial/presentation/historial_screen.dart';
import '../../widgets/app_button.dart';
import 'side_panel.dart';

/// Historial/Favoritos/Apariencia render here now instead of as full
/// top-level screens (2026-08-12, replacing `AppNavBar`'s 5-tab
/// `IndexedStack`) — a resizable panel drawn over the tree's own screen
/// region, not a modal dialog or a small popover: Historial (a virtualized
/// table) and Favoritos (a card grid) are real content-browsing surfaces,
/// not confirmation prompts, so a heavy dimmed-backdrop dialog would be the
/// wrong weight for "glance at recent runs while still writing a query".
/// One mechanism for all three (rather than a dialog for two of them and
/// something else for Apariencia) keeps the interaction consistent: click
/// an icon, a panel covers the tree, the editor stays visible and running.
class SidePanelOverlay extends ConsumerStatefulWidget {
  const SidePanelOverlay({super.key});

  @override
  ConsumerState<SidePanelOverlay> createState() => _SidePanelOverlayState();
}

class _SidePanelOverlayState extends ConsumerState<SidePanelOverlay> {
  // Bumped 420->480 2026-08-13 (user-requested, after removing Apariencia's
  // own now-redundant width column — this is the only width left to tune):
  // Apariencia's shortcut rows are the widest content any panel shows, and
  // 480 is comfortably enough for those without wrapping so awkwardly that
  // most users would feel the need to drag the handle wider immediately.
  double _width = 480;
  static const double _minWidth = 320;
  static const double _maxWidth = 640;

  @override
  Widget build(BuildContext context) {
    final panel = ref.watch(activeSidePanelProvider);
    if (panel == null) return const SizedBox.shrink();

    final colors = context.appTheme.colors;

    return Positioned(
      left: 0,
      top: 0,
      bottom: 0,
      width: _width + _TreeStyleResizeHandle.width,
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Expanded(
            // Real bug found live (user screenshot: panel looked
            // washed-out gray, not the solid white every other surface in
            // the app uses): wrapping this in `Material(color: ...)`
            // instead of a plain `Container` like every other surface in
            // this app (`AppCard`) let Flutter's Material 3 elevation
            // overlay tint it — `Material` derives its own tint from the
            // ambient `ColorScheme` even with `color` set explicitly.
            // Matches `AppCard`'s exact recipe now: a themed `Container`,
            // no `Material` of its own — the app-wide `Scaffold` already
            // provides the `Material` ancestor this panel's own buttons
            // need for ink splashes.
            child: Container(
              decoration: BoxDecoration(
                color: colors.surface,
                border: Border(right: BorderSide(color: colors.border)),
                boxShadow: AppShadows.lg,
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  // No title row — user-reported 2026-08-13, "no veo
                  // necesario el titulo de la pantalla abierta": the icon
                  // that opened this panel already says what it is. Just a
                  // small close affordance, top-right — the main way to
                  // close is now clicking anywhere outside the panel (see
                  // `app_shell.dart`'s dismiss barrier) or clicking the
                  // same tree icon again; this stays as an explicit
                  // fallback.
                  Padding(
                    padding: const EdgeInsets.only(
                        top: AppSpacing.space1, right: AppSpacing.space1),
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.end,
                      children: [
                        AppIconButton(
                          icon: LucideIcons.x,
                          tooltip: 'Cerrar',
                          onPressed: () => ref
                              .read(activeSidePanelProvider.notifier)
                              .state = null,
                        ),
                      ],
                    ),
                  ),
                  // No padding wrapper here — real bug found live (user
                  // screenshot: "RIGHT OVERFLOWED BY 146 PIXELS" in
                  // Apariencia): all 3 of these screens already carry
                  // their own internal `Padding(EdgeInsets.all(space4))`
                  // (built that way back when each was a full top-level
                  // screen with nothing else providing edge spacing) —
                  // padding here on top of that redundantly ate 32px of
                  // width on each side in an already-narrow panel, for no
                  // visual gain since the inner padding already exists.
                  Expanded(
                    child: switch (panel) {
                      SidePanel.historial => const HistorialScreen(),
                      SidePanel.favoritos => const FavoritosScreen(),
                      SidePanel.apariencia => const AparienciaScreen(),
                    },
                  ),
                ],
              ),
            ),
          ),
          _TreeStyleResizeHandle(
            onDrag: (dx) => setState(() {
              _width = (_width + dx).clamp(_minWidth, _maxWidth);
            }),
          ),
        ],
      ),
    );
  }
}

/// Same draggable-strip pattern as `app_tree.dart`'s own `_ResizeHandle`
/// (thin visible line, wider invisible hit target, highlights on hover) —
/// duplicated rather than shared, since both are small and private to their
/// own file; not worth a shared widget for two ~25-line call sites.
class _TreeStyleResizeHandle extends StatefulWidget {
  const _TreeStyleResizeHandle({required this.onDrag});

  final ValueChanged<double> onDrag;

  static const double width = 6;

  @override
  State<_TreeStyleResizeHandle> createState() =>
      _TreeStyleResizeHandleState();
}

class _TreeStyleResizeHandleState extends State<_TreeStyleResizeHandle> {
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
          width: _TreeStyleResizeHandle.width,
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
