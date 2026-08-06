import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../../data/providers/servers_providers.dart';
import '../../../data/providers/settings_providers.dart';
import 'query_window_screen.dart';

/// Mirrors `FaroApp` (`lib/app.dart`) — same theme/accent loading, same
/// `AnimatedTheme` transition — but `home` is [QueryWindowScreen] instead
/// of the 5-tab [AppShell]. `title` becomes this window's native titlebar
/// text (via `window_manager`, set once at startup in
/// `query_window_bootstrap.dart` — `MaterialApp.title` alone isn't enough
/// on Windows desktop).
class QueryWindowApp extends ConsumerWidget {
  const QueryWindowApp({super.key, required this.target});

  final QueryTarget target;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final isDark = ref.watch(themeIsDarkProvider);
    final accent = ref.watch(accentProvider);
    final theme = AppTheme.build(
      brightness: isDark ? Brightness.dark : Brightness.light,
      accent: accent,
    );

    return MaterialApp(
      title: 'Faro — ${target.database.name}',
      debugShowCheckedModeBanner: false,
      theme: theme,
      builder: (context, child) => AnimatedTheme(
        data: theme,
        duration: const Duration(milliseconds: 250),
        child: child!,
      ),
      home: QueryWindowScreen(target: target),
    );
  }
}
