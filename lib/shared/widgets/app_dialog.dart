import 'package:flutter/material.dart';

import '../../core/theme/app_radii.dart';
import '../../core/theme/app_shadows.dart';
import '../../core/theme/app_spacing.dart';
import '../../core/theme/app_theme.dart';

/// Mirrors styles.css `.dialog-backdrop`/`.dialog(-title/-body/-actions)`.
/// README.md: "centered modals over a dimmed backdrop, with a quick
/// fade+scale-in (~180ms)".
Future<T?> showAppDialog<T>({
  required BuildContext context,
  required String title,
  required Widget body,
  required List<Widget> actions,
}) {
  return showGeneralDialog<T>(
    context: context,
    barrierDismissible: true,
    barrierLabel: title,
    barrierColor: AppShadows.backdrop,
    transitionDuration: const Duration(milliseconds: 180),
    pageBuilder: (context, animation, secondaryAnimation) {
      final colors = context.appTheme.colors;
      final typography = context.appTheme.typography;
      return Center(
        child: Material(
          type: MaterialType.transparency,
          child: Container(
            width: 440,
            constraints: const BoxConstraints(maxWidth: 440),
            padding: const EdgeInsets.all(AppSpacing.space4),
            decoration: BoxDecoration(
                color: colors.surface, borderRadius: AppRadii.containerRadius),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title, style: typography.h4),
                const SizedBox(height: AppSpacing.space3),
                // No opacity reduction here — dialog body copy is regular
                // reading content (confirmations, warnings), not a caption;
                // dimming it fought against legibility for no real reason.
                DefaultTextStyle(
                    style: typography.body.copyWith(color: colors.text),
                    child: body),
                const SizedBox(height: AppSpacing.space3),
                Row(mainAxisAlignment: MainAxisAlignment.end, children: [
                  for (final a in actions)
                    Padding(
                        padding: const EdgeInsets.only(left: AppSpacing.space2),
                        child: a)
                ]),
              ],
            ),
          ),
        ),
      );
    },
    transitionBuilder: (context, animation, secondaryAnimation, child) {
      final curved = CurvedAnimation(parent: animation, curve: Curves.easeOut);
      return FadeTransition(
        opacity: curved,
        child: ScaleTransition(
            scale: Tween(begin: 0.96, end: 1.0).animate(curved), child: child),
      );
    },
  );
}
