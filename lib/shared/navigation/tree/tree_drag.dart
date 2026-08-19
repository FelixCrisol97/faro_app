import 'package:flutter/material.dart';
import 'package:flutter_lucide/flutter_lucide.dart';

import '../../../core/theme/app_theme.dart';

/// What a tree row's drag handle carries — either a whole server (reorder)
/// or a database (reorder/move/merge, from a server or from "Sin grupo").
/// No existing drag-and-drop precedent anywhere in this codebase (checked
/// before writing this) — `ReorderableListView` alone can't do a drop that
/// crosses into a *different* list, or a drop-onto-a-row that means "merge
/// into a new group" rather than "reorder", both hard requirements here, so
/// this is `Draggable`/`DragTarget` composed by hand instead.
sealed class TreeDragPayload {
  const TreeDragPayload();
}

class ServerDragPayload extends TreeDragPayload {
  const ServerDragPayload(this.serverId);
  final String serverId;
}

class DatabaseDragPayload extends TreeDragPayload {
  const DatabaseDragPayload({required this.serverId, required this.databaseId});

  /// `null` — currently in "Sin grupo".
  final String? serverId;
  final String databaseId;
}

/// A small grip handle that starts a drag carrying [payload] — placed
/// before a row's chevron/icon, deliberately *not* the whole row: every
/// tree row already crams 5-7 tap targets (chevron/name/test/mode/edit/
/// credentials/delete), so making the entire row a drag source would
/// reintroduce the exact "ambiguous nested tap target" class of bug
/// `database_check_row.dart`'s own history already documents fixing once.
/// Plain `Draggable` (not `LongPressDraggable`) — this is a mouse-driven
/// desktop app, no competing touch-scroll gesture to disambiguate against.
class TreeDragHandle extends StatelessWidget {
  const TreeDragHandle({
    super.key,
    required this.payload,
    required this.label,
    required this.icon,
  });

  final TreeDragPayload payload;
  final String label;
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    final colors = context.appTheme.colors;
    final grip = Icon(LucideIcons.grip_vertical, size: 14, color: colors.textMuted);

    return Draggable<TreeDragPayload>(
      data: payload,
      feedback: _DragFeedback(icon: icon, label: label),
      childWhenDragging: Opacity(opacity: 0.35, child: grip),
      child: MouseRegion(
        cursor: SystemMouseCursors.grab,
        child: Padding(padding: const EdgeInsets.all(2), child: grip),
      ),
    );
  }
}

class _DragFeedback extends StatelessWidget {
  const _DragFeedback({required this.icon, required this.label});

  final IconData icon;
  final String label;

  @override
  Widget build(BuildContext context) {
    final colors = context.appTheme.colors;
    final typography = context.appTheme.typography;
    return Material(
      color: Colors.transparent,
      child: Opacity(
        opacity: 0.9,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
          decoration: BoxDecoration(
            color: colors.surface,
            borderRadius: const BorderRadius.all(Radius.circular(6)),
            border: Border.all(color: colors.accent.base),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(icon, size: 13, color: colors.accent.base),
              const SizedBox(width: 6),
              Text(label, style: typography.body.copyWith(fontSize: 12)),
            ],
          ),
        ),
      ),
    );
  }
}

/// Wraps [child] (a whole tree row) so it also accepts a drop — [onAccept]
/// only ever fires for a payload [onWillAccept] already said yes to.
/// Highlights with the accent tint while a valid drag hovers over it, same
/// visual language `database_check_row.dart` already uses for "selected".
class TreeDropTarget extends StatelessWidget {
  const TreeDropTarget({
    super.key,
    required this.onWillAccept,
    required this.onAccept,
    required this.child,
  });

  final bool Function(TreeDragPayload payload) onWillAccept;
  final void Function(TreeDragPayload payload) onAccept;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final colors = context.appTheme.colors;
    return DragTarget<TreeDragPayload>(
      onWillAcceptWithDetails: (details) => onWillAccept(details.data),
      onAcceptWithDetails: (details) => onAccept(details.data),
      builder: (context, candidateData, rejectedData) {
        if (candidateData.isEmpty) return child;
        return DecoratedBox(
          decoration: BoxDecoration(
            color: colors.accent.soft,
            borderRadius: const BorderRadius.all(Radius.circular(8)),
          ),
          child: child,
        );
      },
    );
  }
}

/// A thin drop zone at the end of a list ("move to the very end") — reached
/// by drag alone otherwise only "Mover al inicio"'s inverse existed as a
/// button. Invisible until a compatible drag is hovering over it, so it
/// doesn't add visual clutter to lists nobody is currently reordering.
class TreeTrailingDropZone extends StatelessWidget {
  const TreeTrailingDropZone({
    super.key,
    required this.onWillAccept,
    required this.onAccept,
  });

  final bool Function(TreeDragPayload payload) onWillAccept;
  final void Function(TreeDragPayload payload) onAccept;

  @override
  Widget build(BuildContext context) {
    final colors = context.appTheme.colors;
    return DragTarget<TreeDragPayload>(
      onWillAcceptWithDetails: (details) => onWillAccept(details.data),
      onAcceptWithDetails: (details) => onAccept(details.data),
      builder: (context, candidateData, rejectedData) {
        return Container(
          height: candidateData.isEmpty ? 6 : 20,
          margin: const EdgeInsets.symmetric(vertical: 2),
          decoration: candidateData.isEmpty
              ? null
              : BoxDecoration(
                  color: colors.accent.soft,
                  borderRadius: const BorderRadius.all(Radius.circular(6)),
                  border: Border.all(color: colors.accent.base),
                ),
        );
      },
    );
  }
}
