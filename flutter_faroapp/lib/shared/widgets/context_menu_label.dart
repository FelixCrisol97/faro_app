import 'package:flutter/material.dart';

import '../../core/theme/app_theme.dart';

/// Shows a `showMenu` popup anchored at [globalPosition] — the
/// `RenderBox`/`RelativeRect` positioning boilerplate every right-click
/// context menu in the app repeated verbatim (schema tree rows, database
/// rows, the results grid's cell menu). Real duplication fixed 2026-08-03
/// (AUDITORIA_CODIGO.md).
Future<T?> showPositionedMenu<T>(
  BuildContext context,
  Offset globalPosition,
  List<PopupMenuEntry<T>> items,
) {
  final overlay = Overlay.of(context).context.findRenderObject() as RenderBox;
  return showMenu<T>(
    context: context,
    position: RelativeRect.fromLTRB(
      globalPosition.dx,
      globalPosition.dy,
      overlay.size.width - globalPosition.dx,
      overlay.size.height - globalPosition.dy,
    ),
    items: items,
  );
}

/// Icon + label row for a `PopupMenuItem`'s `child` — shared by every
/// right-click context menu in the app (schema tree, results grid cells).
class ContextMenuLabel extends StatelessWidget {
  const ContextMenuLabel(this.icon, this.label, {super.key});

  final IconData icon;
  final String label;

  @override
  Widget build(BuildContext context) {
    final colors = context.appTheme.colors;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 14, color: colors.textMuted),
        const SizedBox(width: 8),
        // Flexible, not a bare Text — real bug, user-reported with a
        // screenshot: a long label (e.g. "Descubrir más bases de datos en
        // esta IP") forced the Row past the menu's max width (Material
        // caps a PopupMenuButton/showMenu menu at 280px by default),
        // overflowing instead of truncating. Every shorter label already
        // fits within that width on its own, so this is a no-op for them.
        Flexible(child: Text(label, overflow: TextOverflow.ellipsis)),
      ],
    );
  }
}
