/// The two database engines Faro talks to, configured once per servidor
/// (README.md / PROYECTO_DEFINICION.md — transparent to the end user after
/// that one-time setup in Administración).
enum DbEngine {
  postgres,
  sqlServer;

  String get label => switch (this) {
        DbEngine.postgres => 'PostgreSQL',
        DbEngine.sqlServer => 'SQL Server',
      };

  /// Used when a database's `host` string has no explicit `:puerto`.
  int get defaultPort => switch (this) {
        DbEngine.postgres => 5432,
        DbEngine.sqlServer => 1433,
      };

  /// The always-present administrative database — used to bootstrap a
  /// connection before any real `DatabaseEntry` is known yet, e.g.
  /// discovering what databases exist on a host (`database_discovery_service
  /// .dart`).
  String get maintenanceDatabase => switch (this) {
        DbEngine.postgres => 'postgres',
        DbEngine.sqlServer => 'master',
      };
}

/// A server's read protection mode.
enum ServerMode {
  readOnly,
  development;

  String get label => switch (this) {
        ServerMode.readOnly => 'Solo lectura',
        ServerMode.development => 'Consultas sin restricciones',
      };
}
