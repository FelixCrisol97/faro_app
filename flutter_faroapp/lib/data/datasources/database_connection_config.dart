import '../../core/constants/db_engine.dart';
import '../models/database_credentials.dart';
import '../models/database_entry.dart';

/// Everything a [DbConnector] needs to open a connection to one database.
/// Built per-database from just a `DatabaseEntry` (splitting
/// `DatabaseEntry.host` into host/port) — connectors themselves stay
/// stateless. No `Server` needed (2026-08-13: `engine` moved off `Server`
/// onto `DatabaseEntry` — a database connects on its own, whether or not
/// it's grouped into a servidor).
class DatabaseConnectionConfig {
  const DatabaseConnectionConfig({
    required this.engine,
    required this.host,
    required this.port,
    required this.databaseName,
    required this.username,
    required this.password,
  });

  /// Splits [DatabaseEntry.host] into host/port (falling back to the
  /// engine's default port when absent) and pairs it with already-resolved
  /// [credentials] — the common assembly needed by every call site that
  /// builds a connection for one database (running a query, "Probar
  /// conexión", table-name introspection).
  factory DatabaseConnectionConfig.forDatabase({
    required DatabaseEntry database,
    required DatabaseCredentials credentials,
  }) {
    final hostPort = parseHostPort(database.host, database.engine.defaultPort);
    return DatabaseConnectionConfig(
      engine: database.engine,
      host: hostPort.host,
      port: hostPort.port,
      databaseName: database.databaseName,
      username: credentials.username,
      password: credentials.password,
    );
  }

  final DbEngine engine;
  final String host;
  final int port;
  final String databaseName;
  final String username;
  final String password;
}

/// Splits a `host[:port]` string into its parts — used anywhere a
/// [DatabaseEntry.host]/discovery-dialog host string needs its port
/// pulled out, not just here (see [DatabaseConnectionConfig.forDatabase]
/// and `discover_databases_dialog.dart`).
///
/// Real bug fixed 2026-08-03 (AUDITORIA_CODIGO.md): a naive `host.split(
/// ':')` breaks on an IPv6 literal, which contains `:` itself. This
/// handles it explicitly:
/// - Bracketed IPv6 (`[::1]:5432`, `[2001:db8::1]`) — the RFC 3986
///   convention for disambiguating an IPv6 literal from a following port —
///   is parsed directly: everything inside `[...]` is the host, an
///   optional `:port` after the closing bracket is the port.
/// - An *unbracketed* address with more than one `:` (`::1`,
///   `2001:db8::1`) is assumed to be a bare IPv6 literal with no port —
///   there's no reliable way to tell where the address ends and a port
///   would begin without brackets, so the whole string is kept as the
///   host rather than guessing wrong.
/// - Otherwise (0 or 1 `:`), it's the ordinary `host:port`/bare-host case.
({String host, int port}) parseHostPort(String raw, int defaultPort) {
  if (raw.startsWith('[')) {
    final closeBracket = raw.indexOf(']');
    if (closeBracket != -1) {
      final host = raw.substring(1, closeBracket);
      final rest = raw.substring(closeBracket + 1);
      final port =
          rest.startsWith(':') ? int.tryParse(rest.substring(1)) : null;
      return (host: host, port: port ?? defaultPort);
    }
  }
  final colonCount = raw.split(':').length - 1;
  if (colonCount >= 2) {
    return (host: raw, port: defaultPort);
  }
  final hostPort = raw.split(':');
  final port = hostPort.length > 1 ? int.tryParse(hostPort[1]) : null;
  return (host: hostPort.first, port: port ?? defaultPort);
}
