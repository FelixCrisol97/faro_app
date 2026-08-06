import 'package:desktop_multi_window/desktop_multi_window.dart';

import '../../features/query_window/application/query_window_bootstrap.dart';

/// The one entry point every "Abrir en nueva ventana" action goes through
/// (schema object context menu and database row context menu in
/// `server_sidebar.dart`) — spawns a new native window, pinned to the given
/// database, via `desktop_multi_window`. That window's own `main()` (see
/// `main.dart`) detects it's a query window and boots it through
/// `query_window_bootstrap.dart`'s `runQueryWindow`, which is what actually
/// resolves the ids below against the current on-disk server config, sets
/// the window title/size, and shows it once it's actually ready.
///
/// Deliberately does NOT call `controller.show()` here — the new window
/// starts `hiddenAtLaunch`, and `runQueryWindow` reveals it itself via
/// `windowManager.waitUntilReadyToShow` once its title/size are set,
/// avoiding a visible flash of a default-sized, untitled window first
/// (the pattern the package's own example follows for a freshly-created
/// window, confirmed by reading its source — `.show()` from the launcher
/// side is only for re-showing a window that already exists and finished
/// initializing).
Future<void> openQueryWindow({
  required String serverId,
  required String databaseId,
}) async {
  await WindowController.create(
    WindowConfiguration(
      hiddenAtLaunch: true,
      arguments: encodeQueryWindowArguments(
        serverId: serverId,
        databaseId: databaseId,
      ),
    ),
  );
}
