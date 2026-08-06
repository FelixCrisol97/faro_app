import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme/app_spacing.dart';
import '../../core/theme/app_theme.dart';
import 'app_screen.dart';

/// Mirrors styles.css `.nav`/`.nav-brand` — persistent 5-item link bar,
/// active item colored with the accent (README.md "Interactions & behavior").
class AppNavBar extends ConsumerWidget implements PreferredSizeWidget {
  const AppNavBar({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colors = context.appTheme.colors;
    final typography = context.appTheme.typography;
    final current = ref.watch(currentScreenProvider);

    return Container(
      height: 60,
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.space6),
      decoration: BoxDecoration(
        color: colors.surface,
        border: Border(bottom: BorderSide(color: colors.border)),
      ),
      child: Row(
        children: [
          // Scrolls instead of overflowing on narrow windows — README.md
          // doesn't spec narrow-width nav behavior, so this is a judgment
          // call, not a documented requirement.
          Expanded(
            child: SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              reverse: true,
              child: Row(
                mainAxisAlignment: MainAxisAlignment.end,
                children: [
                  for (final screen in AppScreen.values)
                    Padding(
                      padding: const EdgeInsets.only(left: AppSpacing.space4),
                      child: InkWell(
                        onTap: () => ref
                            .read(currentScreenProvider.notifier)
                            .state = screen,
                        child: Container(
                          padding: const EdgeInsets.symmetric(vertical: 8),
                          decoration: BoxDecoration(
                            border: Border(
                              bottom: BorderSide(
                                color: current == screen
                                    ? colors.accent.base
                                    : Colors.transparent,
                                width: 2,
                              ),
                            ),
                          ),
                          child: Text(
                            screen.label,
                            style: typography.body.copyWith(
                              fontSize: 14,
                              color: current == screen
                                  ? colors.accent.base
                                  : colors.textMuted,
                            ),
                          ),
                        ),
                      ),
                    ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  @override
  Size get preferredSize => const Size.fromHeight(60);
}
