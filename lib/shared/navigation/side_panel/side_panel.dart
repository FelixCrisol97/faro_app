import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_lucide/flutter_lucide.dart';
import 'package:flutter/widgets.dart';

/// The 3 screens that used to be their own top-level nav tabs
/// (`AppScreen`/`AppNavBar`, removed 2026-08-12 when the server/database
/// tree became the app's permanent left pane — see `app_tree.dart`) — now
/// opened as an overlay panel over the tree instead of a full-screen swap.
/// Consulta itself is no longer a "screen" to navigate to: it's just
/// whatever's visible when no panel is open.
enum SidePanel {
  historial('Historial', LucideIcons.history),
  favoritos('Favoritos', LucideIcons.star),
  apariencia('Apariencia', LucideIcons.palette);

  const SidePanel(this.label, this.icon);

  final String label;
  final IconData icon;
}

/// `null` = no panel open (the tree + query editor are what's visible).
/// Session-only, same lifecycle as `massQueryModeProvider` — doesn't need
/// to survive a restart.
final activeSidePanelProvider = StateProvider<SidePanel?>((ref) => null);
