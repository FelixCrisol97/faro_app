import 'package:flutter/material.dart';
import 'package:flutter_lucide/flutter_lucide.dart';

import '../../core/theme/app_theme.dart';

/// A database icon with a small "+" badge — Lucide (`flutter_lucide`
/// 1.11.0) has no dedicated "add database" glyph (unlike `file_plus`/
/// `folder_plus`/`user_plus`), and a bare [LucideIcons.plus] alone doesn't
/// say *what* it adds (user-reported, 2026-07-24: confusing next to the
/// database-row icons in the sidebar). Composed instead of using a plain
/// `Icon` — keep this the one place that builds this composite so it stays
/// visually consistent everywhere "agregar base de datos" shows up as an
/// icon-only action.
class DatabaseAddIcon extends StatelessWidget {
  const DatabaseAddIcon({super.key, required this.color, this.size = 13});

  final Color color;
  final double size;

  @override
  Widget build(BuildContext context) {
    final badgeSize = size * 0.6;
    return SizedBox(
      width: size,
      height: size,
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          Icon(LucideIcons.database, size: size, color: color),
          Positioned(
            right: -badgeSize * 0.35,
            bottom: -badgeSize * 0.35,
            child: Container(
              width: badgeSize,
              height: badgeSize,
              decoration: BoxDecoration(
                color: context.appTheme.colors.surface,
                shape: BoxShape.circle,
              ),
              child: Icon(LucideIcons.circle_plus, size: badgeSize, color: color),
            ),
          ),
        ],
      ),
    );
  }
}
