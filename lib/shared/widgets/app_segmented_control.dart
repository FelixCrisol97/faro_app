import 'package:flutter/material.dart';

import '../../core/theme/app_colors.dart';
import '../../core/theme/app_radii.dart';
import '../../core/theme/app_shadows.dart';
import '../../core/theme/app_theme.dart';
import '../../core/theme/app_typography.dart';

class AppSegmentedOption<T> {
  const AppSegmentedOption(
      {required this.value, required this.label, this.icon});

  final T value;
  final String label;
  final IconData? icon;
}

/// Mirrors styles.css `.seg`/`.seg-opt` — used for Claro/Oscuro and Solo
/// lectura/Desarrollo, and engine (PostgreSQL/SQL Server) pickers.
class AppSegmentedControl<T> extends StatelessWidget {
  const AppSegmentedControl(
      {super.key,
      required this.options,
      required this.value,
      required this.onChanged});

  final List<AppSegmentedOption<T>> options;
  final T value;
  final ValueChanged<T> onChanged;

  @override
  Widget build(BuildContext context) {
    final colors = context.appTheme.colors;
    final typography = context.appTheme.typography;

    // Rounded track (surface-2) containing the options — the selected one
    // gets its own surface-colored pill + shadow, per the redesign.
    return Container(
      padding: const EdgeInsets.all(3),
      decoration: BoxDecoration(
          color: colors.surfaceAlt, borderRadius: AppRadii.segmentTrackRadius),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          for (var i = 0; i < options.length; i++)
            _SegOption<T>(
              option: options[i],
              selected: options[i].value == value,
              onSelected: () => onChanged(options[i].value),
              colors: colors,
              typography: typography,
            ),
        ],
      ),
    );
  }
}

class _SegOption<T> extends StatelessWidget {
  const _SegOption({
    required this.option,
    required this.selected,
    required this.onSelected,
    required this.colors,
    required this.typography,
  });

  final AppSegmentedOption<T> option;
  final bool selected;
  final VoidCallback onSelected;
  final AppColors colors;
  final AppTypography typography;

  @override
  Widget build(BuildContext context) {
    final selectedColor = colors.accent.softText;
    return InkWell(
      onTap: onSelected,
      borderRadius: AppRadii.segmentOptionRadius,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
        decoration: BoxDecoration(
          color: selected ? colors.surface : Colors.transparent,
          borderRadius: AppRadii.segmentOptionRadius,
          boxShadow: selected ? AppShadows.sm : null,
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (option.icon != null) ...[
              Icon(option.icon,
                  size: 15, color: selected ? selectedColor : colors.textMuted),
              const SizedBox(width: 6),
            ],
            Text(
              option.label,
              style: typography.bodySmall.copyWith(
                fontWeight: FontWeight.w600,
                color: selected ? selectedColor : colors.textMuted,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
