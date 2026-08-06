import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/theme/app_spacing.dart';
import '../../application/query_tabs_providers.dart';
import 'query_results_split.dart';
import 'query_tab_bar.dart';

/// Decides what the main content area of Consulta shows. With zero tabs
/// open this is byte-for-byte what `consulta_screen.dart` rendered before
/// this feature existed (`Padding(all: space4, child: QueryResultsSplit())`)
/// — no tab strip, no behavior change, matching the user's explicit "no
/// mover esto que ya está".
///
/// Once ≥1 tab is open, a [QueryTabBar] appears and **only the active
/// tab's widget tree is built** — never an `IndexedStack`/`Offstage`
/// holding every open tab's tree alive at once, which would keep every
/// inactive tab's `_VirtualizedTable` (potentially thousands of rows)
/// mounted and compositing for nothing. The per-tab *state* (editor text,
/// results) still survives the switch regardless — it lives in
/// `query_tabs_providers.dart`'s non-autoDispose `.family` providers, not
/// in this widget tree, so unmounting it here doesn't lose it.
///
/// The `ValueKey` on the active [QueryResultsSplit] is load-bearing, not
/// decorative: `SqlEditor`/`_VirtualizedTable` seed heavy local state
/// (`TextEditingController`, `ScrollController`) once in `initState`. Same
/// `Key` on two different tabs' content would make Flutter reuse the same
/// `State` (`didUpdateWidget`, not a fresh `initState`) and briefly (or
/// permanently, in edge cases) show the previous tab's controller state —
/// a distinct key per tab forces a clean remount instead. Accepted cost:
/// the results table's scroll position and the editor/results split ratio
/// reset on every tab switch (both live in widget-local state, not
/// Riverpod) — cosmetic, not data, and already effectively true once per
/// session today (resets on app restart).
class QueryTabWorkspace extends ConsumerWidget {
  const QueryTabWorkspace({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final tabsState = ref.watch(queryTabsProvider);

    if (tabsState.tabs.isEmpty) {
      return const Padding(
        padding: EdgeInsets.all(AppSpacing.space4),
        child: QueryResultsSplit(),
      );
    }

    final activeTabId = tabsState.activeTabId;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const QueryTabBar(),
        Expanded(
          child: Padding(
            padding: const EdgeInsets.all(AppSpacing.space4),
            child: activeTabId == null
                ? const QueryResultsSplit()
                : QueryResultsSplit(
                    key: ValueKey(activeTabId), tabId: activeTabId),
          ),
        ),
      ],
    );
  }
}
