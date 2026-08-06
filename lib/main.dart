import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:window_manager/window_manager.dart';

import 'Faroapp.dart';
import 'data/providers/core_providers.dart';
import 'features/query_window/application/query_window_bootstrap.dart';

/// Below this, `QueryResultsSplit`'s own `_minPaneHeight` floor (160px,
/// forced regardless of how little space is actually available — see its
/// doc comment) and the sidebar's content stop having enough room and
/// overflow — real bug, user-reported with screenshots, 2026-08-02: this
/// app never set a minimum window size, so shrinking the OS window far
/// enough always eventually broke one panel or another no matter how many
/// individual widgets were hardened against it. A floor here is the fix
/// that actually addresses the cause instead of chasing every overflow one
/// widget at a time. Comfortably above every hardcoded panel minimum in the
/// Consulta layout (sidebar 180 + split's 160+160 + nav bar + paddings).
const _minWindowSize = Size(800, 560);

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // Every window (the one the OS launches the process with, and every
  // query window `openQueryWindow` spawns afterward) runs this same
  // `main()` in its own separate engine — `resolveWindowArguments` is how
  // each one tells which it is. `null` means "no arguments were attached,
  // this is the main window" — boots exactly as before this feature
  // existed. See `query_window_bootstrap.dart` for the query-window path.
  final queryWindowArgs = await resolveWindowArguments();
  if (queryWindowArgs != null) {
    await runQueryWindow(queryWindowArgs);
    return;
  }

  // Unlike a query window (created from scratch via `window_manager`'s own
  // `WindowOptions`, which takes `minimumSize` directly), this main window
  // already exists by the time Dart code runs — Flutter's default desktop
  // runner creates it before `main()` — so the minimum has to be applied
  // to the already-created window instead of set at creation time.
  await windowManager.ensureInitialized();
  await windowManager.setMinimumSize(_minWindowSize);

  final prefs = await SharedPreferences.getInstance();
  runApp(
    ProviderScope(
      overrides: [sharedPreferencesProvider.overrideWithValue(prefs)],
      child: const FaroApp(),
    ),
  );
}
