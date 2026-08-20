import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../data/providers/servers_providers.dart';
import '../application/query_tabs_providers.dart';
import 'widgets/query_tab_workspace.dart';
import 'widgets/toolbar_card.dart';

/// README.md "1. Consulta (main / default screen)": flexible main content
/// (toolbar card, results card) — or, once ≥1 query tab is open
/// (`query_tab_workspace.dart`), a tab strip above that same content, one
/// editor/results pane per tab.
///
/// 2026-08-12: no longer renders its own sidebar — the server/database tree
/// (`shared/navigation/tree/app_tree.dart`, formerly this screen's private
/// `ServerSidebar`) moved up to [AppShell] as a permanent left pane shown
/// behind every panel, not just Consulta's own content.
class ConsultaScreen extends ConsumerWidget {
  const ConsultaScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return CallbackShortcuts(
      bindings: {
        const SingleActivator(LogicalKeyboardKey.f5): () => _toggleRun(ref),
        const SingleActivator(LogicalKeyboardKey.keyG, control: true): () =>
            _save(context, ref),
      },
      child: const Focus(
        autofocus: true,
        child: QueryTabWorkspace(),
      ),
    );
  }

  /// Mirrors the Ejecutar/Cancelar button exactly — routes to whichever
  /// query tab is active (or the sidebar-driven home selection, if none
  /// is), same guard against running with nothing selected either way.
  void _toggleRun(WidgetRef ref) {
    final activeTabId = ref.read(queryTabsProvider).activeTabId;
    final canRun = activeTabId == null
        ? (ref.read(selectedServerProvider)?.selectedCount ?? 0) > 0
        : ref.read(resolvedTabTargetProvider(activeTabId)) != null;
    toggleQueryRun(
      runActionsFor(ref, activeTabId),
      isRunning: readRunState(ref, activeTabId).isRunning,
      canRun: canRun,
    );
  }

  /// Mirrors the "Guardar" button exactly — routes to whichever query tab
  /// is active (or the home pane, if none is), same as F5/[_toggleRun].
  void _save(BuildContext context, WidgetRef ref) {
    final activeTabId = ref.read(queryTabsProvider).activeTabId;
    saveQueryToFile(context, ref, activeTabId);
  }
}
