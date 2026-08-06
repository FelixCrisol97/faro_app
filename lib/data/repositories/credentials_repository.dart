import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../models/database_credentials.dart';

/// Secure, per-OS credential storage (Windows Credential Manager via DPAPI,
/// Keychain on macOS, libsecret on Linux) for database logins. Kept
/// separate from `ServersRepository`/`SharedPreferences`: usernames and
/// passwords must never land in that plaintext `servers.json` blob.
///
/// Two tiers: a default login set per servidor (applies to every database
/// in that group) with an optional per-database override for the case
/// where one bodega has a different login than its siblings. Unlike
/// `DatabaseEntry.host` (which has no server-level default, since IPs
/// genuinely differ per bodega), logins are shared across a servidor's
/// databases far more often than not, so a default here pulls its weight.
class CredentialsRepository {
  CredentialsRepository([FlutterSecureStorage? storage])
      : _storage = storage ?? const FlutterSecureStorage();

  final FlutterSecureStorage _storage;

  static String _serverUserKey(String serverId) =>
      'faro.creds.server.$serverId.username';
  static String _serverPassKey(String serverId) =>
      'faro.creds.server.$serverId.password';
  static String _dbUserKey(String databaseId) =>
      'faro.creds.db.$databaseId.username';
  static String _dbPassKey(String databaseId) =>
      'faro.creds.db.$databaseId.password';

  Future<DatabaseCredentials?> serverCredentials(String serverId) async {
    final username = await _storage.read(key: _serverUserKey(serverId));
    if (username == null) return null;
    final password = await _storage.read(key: _serverPassKey(serverId)) ?? '';
    return (username: username, password: password);
  }

  Future<void> setServerCredentials(
      String serverId, DatabaseCredentials credentials) async {
    await _storage.write(
        key: _serverUserKey(serverId), value: credentials.username);
    await _storage.write(
        key: _serverPassKey(serverId), value: credentials.password);
  }

  Future<void> deleteServerCredentials(String serverId) async {
    await _storage.delete(key: _serverUserKey(serverId));
    await _storage.delete(key: _serverPassKey(serverId));
  }

  Future<DatabaseCredentials?> databaseOverride(String databaseId) async {
    final username = await _storage.read(key: _dbUserKey(databaseId));
    if (username == null) return null;
    final password = await _storage.read(key: _dbPassKey(databaseId)) ?? '';
    return (username: username, password: password);
  }

  Future<void> setDatabaseOverride(
      String databaseId, DatabaseCredentials credentials) async {
    await _storage.write(
        key: _dbUserKey(databaseId), value: credentials.username);
    await _storage.write(
        key: _dbPassKey(databaseId), value: credentials.password);
  }

  Future<void> clearDatabaseOverride(String databaseId) async {
    await _storage.delete(key: _dbUserKey(databaseId));
    await _storage.delete(key: _dbPassKey(databaseId));
  }

  /// Resolves the login to use for one database: its own override if set,
  /// otherwise the parent servidor's default. Empty credentials if neither
  /// is configured — connectors will then fail on login, which surfaces as
  /// a normal connection error rather than a crash.
  Future<DatabaseCredentials> resolve(
      String serverId, String databaseId) async {
    final override = await databaseOverride(databaseId);
    if (override != null) return override;
    return await serverCredentials(serverId) ?? emptyCredentials;
  }
}
