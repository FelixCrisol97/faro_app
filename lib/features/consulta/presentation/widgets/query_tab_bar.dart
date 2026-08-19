import 'package:flutter/material.dart';
import 'package:flutter_lucide/flutter_lucide.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/theme/app_spacing.dart';
import '../../../../core/theme/app_theme.dart';
import '../../application/query_tabs_providers.dart';

/// Only rendered once ≥1 explicit tab is open (see `query_tab_workspace.dart`)
/// — a fixed "Consulta" chip (the sidebar-driven home pane, no close
/// button) followed by one chip per open [QueryTabMeta]. Styled after
/// `app_nav_bar.dart` (surface background, bottom border, 2px accent
/// underline on the active item) but its own implementation — the list is
/// dynamic and each chip needs its own close button, which that widget
/// doesn't handle.
class QueryTabBar extends ConsumerWidget {
  const QueryTabBar({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colors = context.appTheme.colors;
    // Only watched for the list of ids/which is active — rebuilding this
    // outer bar when a tab opens/closes/activates is cheap (it's not the
    // heavy widget tree, see query_tab_workspace.dart). Each chip below
    // resolves its own label independently so unrelated changes elsewhere
    // (e.g. toggling a database in the sidebar) don't reconstruct chips
    // that have nothing to do with it.
    final tabsState = ref.watch(queryTabsProvider);

    return Container(
      height: 40,
      decoration: BoxDecoration(
        color: colors.surface,
        border: Border(bottom: BorderSide(color: colors.border)),
      ),
      child: SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        child: Row(
          children: [
            _HomeTabChip(active: tabsState.activeTabId == null),
            for (final tab in tabsState.tabs)
              _QueryTabChip(key: ValueKey(tab.id), tab: tab,
                  active: tabsState.activeTabId == tab.id),
          ],
        ),
      ),
    );
  }
}

class _HomeTabChip extends ConsumerWidget {
  const _HomeTabChip({required this.active});

  final bool active;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colors = context.appTheme.colors;
    final typography = context.appTheme.typography;
    return _TabChipShell(
      active: active,
      child: InkWell(
        onTap: () => ref.read(queryTabsProvider.notifier).activate(null),
        child: Padding(
          padding: const EdgeInsets.symmetric(
              horizontal: AppSpacing.space3, vertical: AppSpacing.space2),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(LucideIcons.search,
                  size: 13,
                  color: active ? colors.accent.base : colors.textMuted),
              const SizedBox(width: 6),
              Text('Consulta',
                  style: typography.body.copyWith(
                    fontSize: 13,
                    color: active ? colors.text : colors.textMuted,
                  )),
            ],
          ),
        ),
      ),
    );
  }
}

class _QueryTabChip extends ConsumerWidget {
  const _QueryTabChip({super.key, required this.tab, required this.active});

  final QueryTabMeta tab;
  final bool active;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colors = context.appTheme.colors;
    final typography = context.appTheme.typography;
    final target = ref.watch(resolvedTabTargetProvider(tab.id));
    final label = target == null
        ? 'Base eliminada'
        : '${target.server?.name ?? 'Sin grupo'} · ${target.database.name}';

    return _TabChipShell(
      active: active,
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          InkWell(
            onTap: () => ref.read(queryTabsProvider.notifier).activate(tab.id),
            child: Padding(
              padding: const EdgeInsets.only(
                  left: AppSpacing.space3,
                  top: AppSpacing.space2,
                  bottom: AppSpacing.space2),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(LucideIcons.database,
                      size: 13,
                      color: active ? colors.accent.base : colors.textMuted),
                  const SizedBox(width: 6),
                  ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: 180),
                    child: Text(
                      label,
                      overflow: TextOverflow.ellipsis,
                      style: typography.body.copyWith(
                        fontSize: 13,
                        color: active ? colors.text : colors.textMuted,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
          // Sibling InkWell, not nested inside the activate one above —
          // this codebase has hit the ambiguous-tap-target bug from
          // nesting InkWells twice already (`database_check_row.dart`,
          // `shared/navigation/tree/`), not repeating it here.
          InkWell(
            borderRadius: const BorderRadius.all(Radius.circular(4)),
            onTap: () => ref.read(queryTabsProvider.notifier).closeTab(tab.id),
            child: Padding(
              padding: const EdgeInsets.all(AppSpacing.space2),
              child: Icon(LucideIcons.x, size: 13, color: colors.textMuted),
            ),
          ),
        ],
      ),
    );
  }
}

class _TabChipShell extends StatelessWidget {
  const _TabChipShell({required this.active, required this.child});

  final bool active;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final colors = context.appTheme.colors;
    return Container(
      decoration: BoxDecoration(
        border: Border(
          bottom: BorderSide(
            color: active ? colors.accent.base : Colors.transparent,
            width: 2,
          ),
        ),
      ),
      child: Material(color: Colors.transparent, child: child),
    );
  }
}
