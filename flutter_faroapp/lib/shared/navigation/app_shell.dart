import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme/app_theme.dart';
import '../../features/consulta/presentation/consulta_screen.dart';
import 'side_panel/side_panel.dart';
import 'side_panel/side_panel_overlay.dart';
import 'tree/app_tree.dart';

/// The app's root layout — a permanent left [AppTree] (server/database
/// picker + schema explorer) beside Consulta's content, with an optional
/// [SidePanelOverlay] drawn over the tree when Historial/Favoritos/
/// Apariencia is open.
///
/// **2026-08-12: replaces the old 5-tab `AppNavBar` + `IndexedStack`.**
/// Consulta is no longer one of several "screens" to navigate to — it's
/// just what's always here; Administración was absorbed into [AppTree]
/// itself (deleted as a separate screen); Historial/Favoritos/Apariencia
/// became overlay panels instead of full-screen swaps (see
/// `side_panel.dart`). [ConsultaScreen] used to sit inside an
/// [IndexedStack] specifically to keep its `State` alive across
/// navigation (its own local widget state — sidebar width, split-pane
/// height, tree expansion — used to reset on every screen switch
/// otherwise); it no longer needs that here since it's the *only* thing in
/// this `Stack`'s base layer now, never rebuilt out from under itself.
class AppShell extends ConsumerWidget {
  const AppShell({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colors = context.appTheme.colors;
    // Real bug found live (user screenshot): `Scaffold` (used here before
    // 2026-08-12) doesn't just draw the old app bar — it also wraps its
    // body in a `Material` ancestor, which `TextField`/`InkWell`/etc.
    // throughout the app rely on implicitly. Swapping it for a plain
    // `ColoredBox`/`Row`/`Stack` dropped that ancestor for everything
    // under it, crashing the SQL editor's `TextField` with "No Material
    // widget found." `Scaffold(appBar: null, body: ...)` keeps the
    // Material ancestor without bringing back the deleted nav bar.
    return Scaffold(
      backgroundColor: colors.background,
      body: const Row(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          AppTree(),
          Expanded(
            child: Stack(
              children: [
                ConsultaScreen(),
                // Placed *before* SidePanelOverlay so the panel's own
                // content still gets first dibs on any click within its
                // own bounds (painted after = hit-tested first) — only
                // clicks landing outside the panel (over ConsultaScreen's
                // visible area) reach this and close it.
                _PanelDismissBarrier(),
                SidePanelOverlay(),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// Click-anywhere-else-to-close for the side panel — user-reported
/// 2026-08-13: clicking elsewhere in the app didn't dismiss an open
/// Historial/Favoritos/Apariencia panel. Invisible and hit-test-inert
/// (`SizedBox.shrink`) when no panel is open, so it never intercepts
/// ordinary clicks on the editor the rest of the time.
class _PanelDismissBarrier extends ConsumerWidget {
  const _PanelDismissBarrier();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final panel = ref.watch(activeSidePanelProvider);
    if (panel == null) return const SizedBox.shrink();

    return Positioned.fill(
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: () => ref.read(activeSidePanelProvider.notifier).state = null,
        child: const SizedBox.expand(),
      ),
    );
  }
}
