import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_lucide/flutter_lucide.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_spacing.dart';
import '../../../core/theme/app_theme.dart';
import '../../../data/providers/servers_providers.dart';
import '../../consulta/application/consulta_providers.dart';
import '../../consulta/presentation/widgets/query_results_split.dart';
import '../../consulta/presentation/widgets/toolbar_card.dart';

/// The whole UI of a query window — a lighter shell than [AppShell]: no
/// nav bar, no 5 tabs, no sidebar/schema tree. Just a header naming the
/// server/database this window is permanently pinned to (its
/// `ProviderScope` overrides `selectedQueryTargetsProvider` to always
/// resolve to just [target] — see `query_window_bootstrap.dart`) and the
/// same editor/results split the main window's Consulta tab uses.
class QueryWindowScreen extends ConsumerWidget {
  const QueryWindowScreen({super.key, required this.target});

  final QueryTarget target;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colors = context.appTheme.colors;
    final typography = context.appTheme.typography;

    return CallbackShortcuts(
      bindings: {
        const SingleActivator(LogicalKeyboardKey.f5): () => _toggleRun(ref),
        const SingleActivator(LogicalKeyboardKey.keyG, control: true): () =>
            saveQueryToFile(context, ref, null),
      },
      child: Focus(
        autofocus: true,
        child: Scaffold(
          body: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                height: 60,
                padding:
                    const EdgeInsets.symmetric(horizontal: AppSpacing.space4),
                decoration: BoxDecoration(
                  color: colors.surface,
                  border: Border(bottom: BorderSide(color: colors.border)),
                ),
                child: Row(
                  children: [
                    Icon(LucideIcons.database, size: 18, color: colors.accent.base),
                    const SizedBox(width: AppSpacing.space2),
                    Expanded(
                      child: Text(
                        target.server == null
                            ? target.database.name
                            : '${target.server!.name} · ${target.database.name}',
                        style: typography.heading.copyWith(fontSize: 16),
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                    Text(target.database.engine.label,
                        style: typography.caption
                            .copyWith(color: colors.textMuted)),
                  ],
                ),
              ),
              Expanded(
                child: Padding(
                  padding: const EdgeInsets.all(AppSpacing.space4),
                  child: QueryResultsSplit(pinnedTarget: target),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// Simpler than `ConsultaScreen`'s: no "is anything selected" guard needed
  /// — a query window's target never changes, `selectedQueryTargetsProvider`
  /// always resolves to exactly `[target]`.
  void _toggleRun(WidgetRef ref) {
    final notifier = ref.read(queryRunProvider.notifier);
    if (ref.read(queryRunProvider).isRunning) {
      notifier.cancel();
      return;
    }
    notifier.run();
  }
}
