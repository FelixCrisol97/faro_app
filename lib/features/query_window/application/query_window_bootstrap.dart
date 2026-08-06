import 'dart:convert';

import 'package:desktop_multi_window/desktop_multi_window.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:window_manager/window_manager.dart';

import '../../../data/providers/core_providers.dart';
import '../../../data/providers/servers_providers.dart';
import '../../../data/repositories/servers_repository.dart';
import '../presentation/query_window_app.dart';

/// A query window's `arguments` JSON — everything it needs to resolve which
/// database it's pinned to. Kept to just these two ids (not, say, the whole
/// [Server]/[DatabaseEntry] objects) so a stale window opened long ago
/// always re-resolves against whatever is *currently* on disk rather than
/// carrying a frozen snapshot.
const _typeKey = 'type';
const _typeQuery = 'query';
const _serverIdKey = 'serverId';
const _databaseIdKey = 'databaseId';

String encodeQueryWindowArguments({
  required String serverId,
  required String databaseId,
}) =>
    jsonEncode({
      _typeKey: _typeQuery,
      _serverIdKey: serverId,
      _databaseIdKey: databaseId,
    });

/// Every window — main or query — calls this once at startup to find out
/// which one it is. `WindowController.fromCurrentEngine().arguments` is
/// `''` for the window the OS launched the process with (the main window);
/// anything else is the JSON this file's own [encodeQueryWindowArguments]
/// produced for a query window `desktop_multi_window` spawned. Returns
/// `null` for the main-window case so `main.dart` falls through to booting
/// [FaroApp] exactly as before this feature existed.
Future<Map<String, Object?>?> resolveWindowArguments() async {
  final controller = await WindowController.fromCurrentEngine();
  if (controller.arguments.isEmpty) return null;
  return jsonDecode(controller.arguments) as Map<String, Object?>;
}

/// Boots a query window: resolves [args] (from [resolveWindowArguments])
/// against whatever servers are currently on disk, sets up this window's
/// own native title/size (via `window_manager` — `desktop_multi_window`
/// itself only creates/destroys the native window, it has no title/size
/// API of its own, confirmed by reading both packages' actual installed
/// source rather than assuming from docs), and runs [QueryWindowApp] in a
/// `ProviderScope` permanently pinned to that one database.
///
/// **No live state sharing with the main window or any other query
/// window** — this is its own isolate/engine/`ProviderScope`, seeded once
/// here from disk (`SharedPreferences`/`ServersRepository`, the same files
/// every window reads). See `favoritos_providers.dart`'s doc comment for
/// the read-merge-write mitigation that keeps Favoritos/Historial safe
/// across several windows writing independently; server config itself is
/// read-only from a query window (nothing here ever calls
/// `ServersNotifier`'s mutators), so there's no equivalent write-race to
/// worry about on that side.
Future<void> runQueryWindow(Map<String, Object?> args) async {
  final serverId = args[_serverIdKey] as String?;
  final databaseId = args[_databaseIdKey] as String?;

  await windowManager.ensureInitialized();

  final prefs = await SharedPreferences.getInstance();
  final servers = ServersRepository(prefs).load();
  final server = servers.where((s) => s.id == serverId).firstOrNull;
  final database =
      server?.databases.where((d) => d.id == databaseId).firstOrNull;

  if (server == null || database == null) {
    // The database was deleted from Administración (in another window,
    // possibly before this one even finished opening) between the window
    // being created and this bootstrap running — show a small explanation
    // instead of crashing on a null target.
    await _showAndRun(const _MissingDatabaseWindow());
    return;
  }

  final target = (server: server, database: database);
  await _showAndRun(
    ProviderScope(
      overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
        selectedQueryTargetsProvider.overrideWithValue([target]),
      ],
      child: QueryWindowApp(target: target),
    ),
    title: 'Faro — ${database.name}',
  );
}

Future<void> _showAndRun(Widget app, {String title = 'Faro'}) async {
  // Same `QueryResultsSplit` layout as the main window's Consulta screen,
  // same overflow risk if this window gets resized too small — see
  // `main.dart`'s `_minWindowSize` doc comment for the full story.
  final options = WindowOptions(
    size: const Size(1000, 700),
    minimumSize: const Size(800, 560),
    center: true,
    title: title,
  );
  windowManager.waitUntilReadyToShow(options, () async {
    await windowManager.show();
    await windowManager.focus();
  });
  runApp(app);
}

class _MissingDatabaseWindow extends StatelessWidget {
  const _MissingDatabaseWindow();

  @override
  Widget build(BuildContext context) {
    return const MaterialApp(
      debugShowCheckedModeBanner: false,
      home: Scaffold(
        body: Center(
          child: Padding(
            padding: EdgeInsets.all(24),
            child: Text(
              'Esta base de datos ya no existe — puede que se haya '
              'eliminado desde Administración. Cierra esta ventana.',
              textAlign: TextAlign.center,
            ),
          ),
        ),
      ),
    );
  }
}
