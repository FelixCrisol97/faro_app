import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'core/theme/app_theme.dart';
import 'data/providers/settings_providers.dart';
import 'shared/navigation/app_shell.dart';

/// README.md "Interactions & behavior": "Theme and accent changes transition
/// smoothly (~250ms color/background fade) instead of snapping" — `AnimatedTheme`
/// gives us that for free on top of `AppTheme.build`.
class FaroApp extends ConsumerWidget {
  const FaroApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final isDark = ref.watch(themeIsDarkProvider);
    final accent = ref.watch(accentProvider);
    final theme = AppTheme.build(
      brightness: isDark ? Brightness.dark : Brightness.light,
      accent: accent,
    );

    return MaterialApp(
      title: 'Faro',
      debugShowCheckedModeBanner: false,
      theme: theme,
      builder: (context, child) => AnimatedTheme(
        data: theme,
        duration: const Duration(milliseconds: 250),
        child: child!,
      ),
      home: const AppShell(),
    );
  }
}
