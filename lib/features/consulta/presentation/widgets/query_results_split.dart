import 'package:flutter/material.dart';

import '../../../../core/theme/app_theme.dart';
import '../../../../data/providers/servers_providers.dart';
import 'results_card.dart';
import 'toolbar_card.dart';

/// Drag-resizable vertical split between [ToolbarCard] (query editor, on
/// top) and [ResultsCard] (below). Both cards need a genuinely bounded
/// height for this to work (their internal `Expanded`/virtualized-table
/// children rely on it — see `toolbar_card.dart`/`sql_editor.dart` and
/// `results_card.dart`'s `_VirtualizedTable`), so this is a `Column` inside
/// a fixed-height `LayoutBuilder`, not a scrollable.
///
/// Extracted out of `consulta_screen.dart` (2026-07-20) so a query window
/// (see `query_window_screen.dart` — a separate native window pinned to one
/// database, no sidebar/nav bar) can reuse the exact same editor/results
/// pane instead of duplicating it. [pinnedTarget], when set, is threaded
/// straight through to [ToolbarCard] — see its doc comment for what that
/// changes.
///
/// [tabId], when set, is a second and independent way to scope this pane —
/// used by `query_tab_workspace.dart` for in-window query tabs (as opposed
/// to [pinnedTarget]'s separate-OS-window case). The two are never set
/// together: a query window overrides its whole `ProviderScope` instead
/// (see `query_window_bootstrap.dart`), so it never needs `tabId`-based
/// routing at all.
class QueryResultsSplit extends StatefulWidget {
  const QueryResultsSplit({super.key, this.pinnedTarget, this.tabId});

  final QueryTarget? pinnedTarget;
  final String? tabId;

  @override
  State<QueryResultsSplit> createState() => _QueryResultsSplitState();
}

class _QueryResultsSplitState extends State<QueryResultsSplit> {
  static const _minPaneHeight = 160.0;
  static const _handleHeight = 14.0;

  // Raw, unclamped preference — kept separate from the clamped value used
  // for layout so shrinking the window and then growing it back restores
  // what the user actually dragged to, instead of permanently losing it
  // the moment it got clamped once.
  double? _topHeight;

  // Where the drag started, in both the pane's own height and the
  // pointer's absolute screen position — real bug, user-reported: an
  // earlier version tracked this via `details.delta.dy` summed frame by
  // frame (amplified by a tuned multiplier, bumped once already from 1.8
  // to 3.6) and it still visibly lagged behind a fast mouse move. Delta
  // accumulation can only ever catch up to the cursor's *current* position
  // one dropped/queued frame at a time; anchoring to the absolute pointer
  // position instead means every frame computes the pane height directly
  // from "how far the cursor already is from where the drag started," so
  // it can never trail behind — the next frame is always correct
  // regardless of how many pointer-move events got coalesced before it.
  double? _dragStartTopHeight;
  double? _dragStartGlobalY;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final maxTop = (constraints.maxHeight - _handleHeight - _minPaneHeight)
            .clamp(_minPaneHeight, double.infinity);
        // First layout pass only: a comfortable default split rather than
        // an arbitrary fixed pixel count that could be wildly wrong for a
        // very small or very large window.
        _topHeight ??= constraints.maxHeight * 0.42;
        final topHeight = _topHeight!.clamp(_minPaneHeight, maxTop);

        return Column(
          children: [
            SizedBox(
                height: topHeight,
                child: ToolbarCard(
                    pinnedTarget: widget.pinnedTarget, tabId: widget.tabId)),
            _DragHandle(
              height: _handleHeight,
              onDragStart: (globalY) {
                _dragStartTopHeight = topHeight;
                _dragStartGlobalY = globalY;
              },
              onDragUpdate: (globalY) => setState(() {
                final delta = globalY - _dragStartGlobalY!;
                _topHeight = (_dragStartTopHeight! + delta)
                    .clamp(_minPaneHeight, maxTop);
              }),
            ),
            Expanded(child: ResultsCard(tabId: widget.tabId)),
          ],
        );
      },
    );
  }
}

class _DragHandle extends StatelessWidget {
  const _DragHandle({
    required this.height,
    required this.onDragStart,
    required this.onDragUpdate,
  });

  final double height;
  final ValueChanged<double> onDragStart;
  final ValueChanged<double> onDragUpdate;

  @override
  Widget build(BuildContext context) {
    final colors = context.appTheme.colors;
    return MouseRegion(
      cursor: SystemMouseCursors.resizeUpDown,
      child: GestureDetector(
        behavior: HitTestBehavior.translucent,
        onVerticalDragStart: (details) =>
            onDragStart(details.globalPosition.dy),
        onVerticalDragUpdate: (details) =>
            onDragUpdate(details.globalPosition.dy),
        child: SizedBox(
          height: height,
          width: double.infinity,
          child: Center(
            child: Container(
              width: 40,
              height: 4,
              decoration: BoxDecoration(
                color: colors.border,
                borderRadius: const BorderRadius.all(Radius.circular(2)),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
