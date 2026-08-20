import 'package:flutter/material.dart';
import 'package:flutter_lucide/flutter_lucide.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/theme/app_colors.dart';
import '../../../../core/theme/app_spacing.dart';
import '../../../../core/theme/app_theme.dart';
import '../../../../core/theme/app_typography.dart';
import '../../../../data/models/query_result.dart';
import '../../../../data/providers/core_providers.dart';
import '../../../../data/repositories/query_execution_service.dart';
import '../../../../shared/widgets/app_card.dart';
import '../../../../shared/widgets/centered_scrollable.dart';
import '../../application/consulta_providers.dart';
import '../../application/query_tabs_providers.dart';
import 'results/results_pane.dart';

/// README.md "Main — results card": empty / running / results states. The
/// results-ready state itself (pills, CSV export, the virtualized grid)
/// lives in `widgets/results/` — see [ResultsPane].
class ResultsCard extends ConsumerWidget {
  const ResultsCard({super.key, this.tabId});

  /// Set only inside an in-window query tab — see `toolbar_card.dart`'s
  /// matching field for the full explanation.
  final String? tabId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final runState = watchRunState(ref, tabId);
    final typography = context.appTheme.typography;
    final colors = context.appTheme.colors;
    // Watched here, not just inside `ResultsPane` — this widget is never
    // disposed by the switch below (only its branches are), so an export
    // that's still running when a new query starts stays visible instead of
    // silently vanishing along with the old `ResultsPane` instance. See
    // `exportingCsvProvider`'s doc comment for the bug this fixes.
    final exporting = watchExportingCsv(ref, tabId);

    return AppCard(
      padding: const EdgeInsets.all(AppSpacing.space4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          if (exporting)
            Padding(
              padding: const EdgeInsets.only(bottom: AppSpacing.space3),
              child: _ExportingBanner(typography: typography, colors: colors),
            ),
          Expanded(
            child: switch (runState.status) {
              QueryRunStatus.idle =>
                _EmptyState(typography: typography, colors: colors),
              QueryRunStatus.running => _RunningState(
                  typography: typography,
                  colors: colors,
                  currentStatement: runState.currentStatement,
                  totalStatements: runState.totalStatements,
                ),
              QueryRunStatus.done => ResultsPane(
                  result: runState.result!,
                  queryText: runState.queryText,
                  released: runState.released,
                  onReleaseResults: () =>
                      runActionsFor(ref, tabId).releaseResults(),
                  paginated: runState.paginated,
                  hasMore: runState.hasMore,
                  loadingMore: runState.loadingMore,
                  onLoadMore: () => runActionsFor(ref, tabId).loadMore(),
                  resolveExportResult: () =>
                      _resolveExportResult(ref, runState),
                  tabId: tabId),
            },
          ),
        ],
      ),
    );
  }
}

/// Backs `ResultsPane`'s CSV export — see [ResultsPane.resolveExportResult]'s
/// doc comment for why this exists instead of the pane just serializing
/// whatever's currently in `widget.result`: a paginated grid only ever
/// holds the pages loaded so far, but "Exportar CSV" must always produce
/// the complete result (confirmed with the user — same expectation as
/// SSMS/pgAdmin/DBeaver: pagination is a *view* concern, export isn't).
/// Deliberately never writes into `QueryRunState` — this must not replace
/// whatever page the user is currently looking at.
Future<QueryResult> _resolveExportResult(
    WidgetRef ref, QueryRunState runState) async {
  if (!runState.paginated) return runState.result!;

  final target = runState.pagingTarget!;
  final original = runState.pagingOriginalStatement!;
  final full = await ref.read(queryExecutionServiceProvider).run(
        targets: [target],
        statements: [original],
        resolveCredentials: ref.read(credentialsRepositoryProvider).resolve,
        cancellationToken: CancellationToken(),
      );
  final outcome = full.perDatabase.firstOrNull;
  if (outcome == null || !outcome.success) {
    throw StateError(
        outcome?.errorMessage ?? 'No se pudo obtener el resultado completo.');
  }
  return full;
}

/// Persistent "still exporting" indicator — lives here, not inside
/// `ResultsPane`, specifically so it survives that widget being swapped out
/// for `_RunningState`/`_EmptyState` mid-export (see [ResultsCard.build]'s
/// comment). Same rotating-icon pattern as [_RunningState] below.
class _ExportingBanner extends StatefulWidget {
  const _ExportingBanner({required this.typography, required this.colors});
  final AppTypography typography;
  final AppColors colors;

  @override
  State<_ExportingBanner> createState() => _ExportingBannerState();
}

class _ExportingBannerState extends State<_ExportingBanner>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller;

  @override
  void initState() {
    super.initState();
    _controller =
        AnimationController(vsync: this, duration: const Duration(seconds: 1))
          ..repeat();
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        RotationTransition(
          turns: _controller,
          child: Icon(LucideIcons.loader_circle,
              size: 16, color: widget.colors.textMuted),
        ),
        const SizedBox(width: AppSpacing.space1),
        Text('Exportando CSV…', style: widget.typography.bodySmall),
      ],
    );
  }
}

class _EmptyState extends StatelessWidget {
  const _EmptyState({required this.typography, required this.colors});
  final AppTypography typography;
  final AppColors colors;

  @override
  Widget build(BuildContext context) {
    // Sits inside `ResultsCard`'s own `Expanded` — no fixed height needed
    // (or wanted: see `CenteredScrollable`'s doc comment for the overflow
    // bug a hardcoded height used to cause once the window got small).
    return CenteredScrollable(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(LucideIcons.search, size: 36, color: colors.textMuted),
          const SizedBox(height: AppSpacing.space2),
          Text('Ejecuta una consulta para ver resultados aquí.',
              style: typography.bodySmall),
        ],
      ),
    );
  }
}

class _RunningState extends StatefulWidget {
  const _RunningState({
    required this.typography,
    required this.colors,
    this.currentStatement,
    this.totalStatements,
  });
  final AppTypography typography;
  final AppColors colors;

  /// Only non-null for a single-target run with more than one statement —
  /// see `QueryRunState.currentStatement`'s doc comment for why several
  /// databases running in parallel don't get one of these.
  final int? currentStatement;
  final int? totalStatements;

  @override
  State<_RunningState> createState() => _RunningStateState();
}

class _RunningStateState extends State<_RunningState>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller;

  @override
  void initState() {
    super.initState();
    _controller =
        AnimationController(vsync: this, duration: const Duration(seconds: 1))
          ..repeat();
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    // Same reasoning as `_EmptyState` — no fixed height, sits inside
    // `ResultsCard`'s own `Expanded`.
    return CenteredScrollable(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          RotationTransition(
            turns: _controller,
            child: Icon(LucideIcons.loader_circle,
                size: 32, color: widget.colors.accent.base),
          ),
          const SizedBox(height: AppSpacing.space2),
          Text(
            widget.totalStatements != null && widget.totalStatements! > 1
                ? 'Ejecutando instrucción ${widget.currentStatement} de ${widget.totalStatements}…'
                : 'Ejecutando consulta…',
            style: widget.typography.bodySmall,
          ),
        ],
      ),
    );
  }
}
