import 'package:flutter/material.dart';

import '../../core/theme/app_radii.dart';
import '../../core/theme/app_shadows.dart';
import '../../core/theme/app_spacing.dart';
import '../../core/theme/app_theme.dart';

/// Mirrors styles.css `.dialog-backdrop`/`.dialog(-title/-body/-actions)`.
/// README.md: "centered modals over a dimmed backdrop, with a quick
/// fade+scale-in (~180ms)".
const _transitionDuration = Duration(milliseconds: 180);

/// Real bug found live 2026-08-13 (user screenshot: "A TextEditingController
/// was used after being disposed", then a cascading `_dependents.isEmpty`
/// assertion filling the whole screen): most callers of this function do
/// `final result = await showAppDialog(...); ...; controller.dispose();`
/// (`save_favorite_dialog.dart` and several others under this same
/// `shared/widgets/` folder) — but `showGeneralDialog`'s returned `Future`
/// resolves as soon as `Navigator.pop()` is called, **not** once the
/// popped route's exit transition actually finishes. For those 180ms the
/// dialog's own content (including any `TextField` in it) is still alive
/// and can still rebuild while fading/scaling out — disposing its
/// controller that early means that rebuild touches an already-disposed
/// controller, which is exactly what crashed. Waiting out the transition
/// here, once, fixes every dialog built on this helper instead of each
/// one having to know to delay its own disposal.
Future<T?> showAppDialog<T>({
  required BuildContext context,
  required String title,
  required Widget body,
  required List<Widget> actions,
}) async {
  final result = await showGeneralDialog<T>(
    context: context,
    barrierDismissible: true,
    barrierLabel: title,
    barrierColor: AppShadows.backdrop,
    transitionDuration: _transitionDuration,
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
  // See this function's doc comment — `Navigator.pop()` already happened
  // by the time `showGeneralDialog` resolves, but the route is still
  // playing its exit transition; a small safety margin on top of the
  // transition's own duration so callers that dispose a controller right
  // after `await`ing this never race the last frame of that transition.
  await Future.delayed(_transitionDuration + const Duration(milliseconds: 50));
  return result;
}
