import 'package:flutter/material.dart';

import '../../core/theme/app_colors.dart';
import '../../core/theme/app_radii.dart';
import '../../core/theme/app_spacing.dart';
import '../../core/theme/app_theme.dart';

/// Mirrors the redesigned `.btn` variants (design_system/design_handoff_faro):
/// `.btn-primary` (solid accent) / `.btn-secondary` (bordered, transparent
/// bg) / `.btn-ghost` (transparent, accent-colored text). Controls are
/// 7–9px rounded rectangles now, not pill-shaped — that was the earlier
/// "Organic" system's look. `.btn-icon` is [AppIconButton] instead.
enum AppButtonVariant { primary, secondary, ghost }

class AppButton extends StatelessWidget {
  const AppButton({
    super.key,
    required this.label,
    this.icon,
    this.variant = AppButtonVariant.secondary,
    this.onPressed,
    this.expand = false,
    this.autofocus = false,
  });

  final String label;
  final IconData? icon;
  final AppButtonVariant variant;
  final VoidCallback? onPressed;

  /// Full width, used e.g. below the Historial "Reusar" row.
  final bool expand;

  /// Focuses this button as soon as the dialog/screen it's in opens, so a
  /// bare Enter/Space press (no click needed) activates it — Flutter's
  /// `TextButton` already responds to those keys natively once focused,
  /// this just opts a button into grabbing that initial focus. Used on
  /// dialog primary actions (see `app_dialog.dart` call sites) so pressing
  /// Enter in a dialog with no text field to catch it (a plain
  /// confirmation) still does the obvious thing.
  final bool autofocus;

  @override
  Widget build(BuildContext context) {
    final colors = context.appTheme.colors;
    final typography = context.appTheme.typography;

    final button = TextButton(
      onPressed: onPressed,
      autofocus: autofocus,
      style: _styleFor(variant, colors),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (icon != null) ...[
            Icon(icon, size: 15),
            const SizedBox(width: 8),
          ],
          Text(label, style: typography.button),
        ],
      ),
    );

    return expand ? SizedBox(width: double.infinity, child: button) : button;
  }

  static ButtonStyle _styleFor(AppButtonVariant variant, AppColors colors) {
    const basePadding = EdgeInsets.symmetric(vertical: 10, horizontal: 16);
    const shape = RoundedRectangleBorder(borderRadius: AppRadii.controlRadius);

    switch (variant) {
      case AppButtonVariant.primary:
        return TextButton.styleFrom(
          shape: shape,
          padding: basePadding,
          backgroundColor: colors.accent.base,
          foregroundColor: Colors.white,
          disabledBackgroundColor: colors.accent.base.withValues(alpha: 0.45),
          disabledForegroundColor: Colors.white.withValues(alpha: 0.7),
        ).copyWith(
          backgroundColor: WidgetStateProperty.resolveWith((states) {
            if (states.contains(WidgetState.disabled))
              return colors.accent.base.withValues(alpha: 0.45);
            if (states.contains(WidgetState.pressed))
              return colors.accent.active;
            if (states.contains(WidgetState.hovered))
              return colors.accent.hover;
            return colors.accent.base;
          }),
        );
      case AppButtonVariant.secondary:
        return TextButton.styleFrom(
          shape: shape,
          padding: basePadding,
          foregroundColor: colors.text,
          side: BorderSide(color: colors.border),
        ).copyWith(
          backgroundColor: WidgetStateProperty.resolveWith((states) {
            if (states.contains(WidgetState.pressed))
              return colors.surfaceAlt.withValues(alpha: 0.8);
            if (states.contains(WidgetState.hovered)) return colors.surfaceAlt;
            return Colors.transparent;
          }),
        );
      case AppButtonVariant.ghost:
        return TextButton.styleFrom(
          shape: shape,
          padding: const EdgeInsets.symmetric(
              horizontal: AppSpacing.space2, vertical: AppSpacing.space1),
          foregroundColor: colors.accent.base,
        ).copyWith(
          backgroundColor: WidgetStateProperty.resolveWith((states) {
            if (states.contains(WidgetState.pressed))
              return colors.accent.soft.withValues(alpha: 0.8);
            if (states.contains(WidgetState.hovered)) return colors.accent.soft;
            return Colors.transparent;
          }),
        );
    }
  }
}

/// `.btn-icon` — a bare, bordered square icon button (e.g. the trash icon
/// on a Favoritos card, or Administración's inline actions).
class AppIconButton extends StatelessWidget {
  const AppIconButton(
      {super.key, required this.icon, this.onPressed, this.tooltip});

  final IconData icon;
  final VoidCallback? onPressed;
  final String? tooltip;

  @override
  Widget build(BuildContext context) {
    final colors = context.appTheme.colors;
    return IconButton(
      onPressed: onPressed,
      tooltip: tooltip,
      icon: Icon(icon, size: 16),
      color: colors.textMuted,
      constraints: const BoxConstraints(minWidth: 36, minHeight: 36),
      padding: EdgeInsets.zero,
      style: IconButton.styleFrom(
        shape:
            const RoundedRectangleBorder(borderRadius: AppRadii.controlRadius),
        side: BorderSide(color: colors.border),
      ),
    );
  }
}
