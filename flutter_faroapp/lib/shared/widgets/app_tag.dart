import 'package:flutter/material.dart';

import '../../core/theme/app_radii.dart';
import '../../core/theme/app_theme.dart';

/// Mirrors the redesigned `.chip` variants (design_system/design_handoff_faro)
/// — success/error/warning are fixed semantic colors, independent of the
/// user's chosen accent (a "Solo lectura" pill looks the same regardless of
/// accent; a failed-database pill is always red, not accent-tinted).
enum AppTagVariant {
  /// Success chip: per-database success pill, "Éxito" in Historial.
  success,

  /// Error chip: per-database error pill, "Bloqueada" in Historial.
  error,

  /// Tinted warning: toolbar "Desarrollo" status pill, "Parcial" in Historial.
  warnSoft,

  /// Solid warning: the small "DEV" sidebar badge.
  warnSolid,

  /// "Solo lectura" pill, engine tag, idle connection-test pill.
  neutral,
}

class AppTag extends StatelessWidget {
  const AppTag(
      {super.key,
      required this.label,
      this.icon,
      this.variant = AppTagVariant.neutral});

  final String label;
  final IconData? icon;
  final AppTagVariant variant;

  @override
  Widget build(BuildContext context) {
    final colors = context.appTheme.colors;

    late final Color background;
    late final Color foreground;

    switch (variant) {
      case AppTagVariant.success:
        background = colors.success.soft;
        foreground = colors.success.softText;
      case AppTagVariant.error:
        background = colors.error.soft;
        foreground = colors.error.softText;
      case AppTagVariant.warnSoft:
        background = colors.warn.soft;
        foreground = colors.warn.softText;
      case AppTagVariant.warnSolid:
        background = colors.warn.base;
        foreground = Colors.white;
      case AppTagVariant.neutral:
        background = colors.surfaceAlt;
        foreground = colors.textMuted;
    }

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 3),
      decoration:
          BoxDecoration(color: background, borderRadius: AppRadii.chipRadius),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (icon != null) ...[
            Icon(icon, size: 12, color: foreground),
            const SizedBox(width: 4),
          ],
          Text(
            label,
            style: TextStyle(
              fontSize: 11.5,
              fontWeight: FontWeight.w600,
              letterSpacing: 0.02,
              color: foreground,
              fontFamily: 'Manrope',
            ),
          ),
        ],
      ),
    );
  }
}
