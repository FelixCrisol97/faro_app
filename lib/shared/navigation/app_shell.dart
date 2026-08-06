import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../features/administracion/presentation/administracion_screen.dart';
import '../../features/apariencia/presentation/apariencia_screen.dart';
import '../../features/consulta/presentation/consulta_screen.dart';
import '../../features/favoritos/presentation/favoritos_screen.dart';
import '../../features/historial/presentation/historial_screen.dart';
import 'app_nav_bar.dart';
import 'app_screen.dart';

/// The persistent-nav-bar + current-screen shell (README.md "Interactions
/// & behavior": "Top nav is a persistent 5-item link bar").
///
/// All 5 screens are kept mounted at once via [IndexedStack] (only the
/// active one is painted/hit-testable) instead of switching which single
/// widget is built — real bug, user-reported: with only the active screen
/// ever in the tree, navigating away and back tore down and rebuilt every
/// screen from scratch, silently resetting any purely-local widget `State`
/// along the way (Consulta's sidebar drag-resized width, the editor/results
/// split's dragged height, the schema tree's expansion state — none of
/// that lives in a Riverpod provider, all of it is a `StatefulWidget`
/// field). [IndexedStack] keeps every screen's `State` alive for the
/// lifetime of the app, the same tradeoff already accepted for a small,
/// fixed set of things (5 screens here) — unlike query tabs, which are
/// deliberately NOT all kept mounted at once (see
/// `query_tab_workspace.dart`) because that count is unbounded and each tab
/// carries a heavy results table; 5 fixed nav screens is a different, safe
/// scale for this pattern.
class AppShell extends ConsumerWidget {
  const AppShell({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final screen = ref.watch(currentScreenProvider);

    return Scaffold(
      appBar: const AppNavBar(),
      body: IndexedStack(
        index: screen.index,
        children: const [
          ConsultaScreen(),
          HistorialScreen(),
          FavoritosScreen(),
          AdministracionScreen(),
          AparienciaScreen(),
        ],
      ),
    );
  }
}
