import 'package:flutter/material.dart';

import '../../core/theme/app_radii.dart';
import '../../core/theme/app_shadows.dart';
import '../../core/theme/app_spacing.dart';
import '../../core/theme/app_theme.dart';

enum AppCardElevation { none, sm, md, lg }

/// Mirrors styles.css `.card` (+ `.elev-sm/md/lg`). A plain surface-filled
/// container — screens compose their own title/body/meta rows inside it
/// rather than this widget prescribing a fixed layout, since Consulta's
/// toolbar card, results card, and Favoritos' cards all shape their
/// contents quite differently.
class AppCard extends StatelessWidget {
  const AppCard({
    super.key,
    required this.child,
    this.elevation = AppCardElevation.sm,
    this.padding = const EdgeInsets.all(AppSpacing.space3),
  });

  final Widget child;
  final AppCardElevation elevation;
  final EdgeInsetsGeometry padding;

  @override
  Widget build(BuildContext context) {
    final colors = context.appTheme.colors;
    return Container(
      padding: padding,
      decoration: BoxDecoration(
        color: colors.surface,
        border: Border.all(color: colors.border),
        borderRadius: AppRadii.containerRadius,
        boxShadow: switch (elevation) {
          AppCardElevation.none => null,
          AppCardElevation.sm => AppShadows.sm,
          AppCardElevation.md => AppShadows.md,
          AppCardElevation.lg => AppShadows.lg,
        },
      ),
      child: child,
    );
  }
}
