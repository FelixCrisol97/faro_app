import 'dart:convert';

import 'package:faro/core/constants/db_engine.dart';
import 'package:faro/data/models/database_entry.dart';
import 'package:faro/data/models/server.dart';
import 'package:faro/data/models/servers_state.dart';
import 'package:faro/data/repositories/servers_repository.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

const _legacyKey = 'faro.servers';
const _newKey = 'faro.servers.v2';

Server _server(String id) => Server(
      id: id,
      name: 'S-$id',
      databases: [
        DatabaseEntry(
            id: 'db-$id',
            name: 'db',
            host: 'localhost',
            databaseName: 'db',
            engine: DbEngine.postgres),
      ],
    );

void main() {
  group(
      'ServersRepository — v2 migration (real risk: this holds every '
      "user's server config, must never lose data on upgrade)", () {
    test('reads a legacy (pre-"Sin grupo") blob with no v2 key present',
        () async {
      SharedPreferences.setMockInitialValues({
        _legacyKey: jsonEncode([_server('1').toJson()]),
      });
      final prefs = await SharedPreferences.getInstance();
      final state = ServersRepository(prefs).load();

      expect(state.servers.single.id, '1');
      expect(state.ungroupedDatabases, isEmpty);
    });

    test(
        'a legacy blob with a per-server "engine" key (pre-2026-08-13) '
        "falls through to every one of that server's databases", () async {
      final legacyJson = {
        'id': '1',
        'name': 'S-1',
        'engine': 'sqlServer',
        'databases': [
          {
            'id': 'db-1',
            'alias': 'db',
            'host': 'localhost',
            'databaseName': 'db',
          },
        ],
      };
      SharedPreferences.setMockInitialValues({
        _legacyKey: jsonEncode([legacyJson]),
      });
      final prefs = await SharedPreferences.getInstance();
      final state = ServersRepository(prefs).load();

      expect(state.servers.single.databases.single.engine, DbEngine.sqlServer);
    });

    test('prefers the v2 key over the legacy key when both are present',
        () async {
      SharedPreferences.setMockInitialValues({
        _legacyKey: jsonEncode([_server('legacy').toJson()]),
        _newKey: jsonEncode(ServersState(servers: [_server('v2')]).toJson()),
      });
      final prefs = await SharedPreferences.getInstance();
      final state = ServersRepository(prefs).load();

      expect(state.servers.single.id, 'v2');
    });

    test('reads ungroupedDatabases from a v2 blob', () async {
      const ungrouped = DatabaseEntry(
          id: 'loose',
          name: 'loose',
          host: '',
          databaseName: 'loose',
          engine: DbEngine.postgres);
      SharedPreferences.setMockInitialValues({
        _newKey: jsonEncode(
            const ServersState(ungroupedDatabases: [ungrouped]).toJson()),
      });
      final prefs = await SharedPreferences.getInstance();
      final state = ServersRepository(prefs).load();

      expect(state.servers, isEmpty);
      expect(state.ungroupedDatabases.single.id, 'loose');
    });

    test('returns an empty ServersState when neither key exists', () async {
      SharedPreferences.setMockInitialValues({});
      final prefs = await SharedPreferences.getInstance();
      final state = ServersRepository(prefs).load();

      expect(state.servers, isEmpty);
      expect(state.ungroupedDatabases, isEmpty);
    });

    test('a corrupt v2 blob falls back to the legacy key instead of throwing',
        () async {
      SharedPreferences.setMockInitialValues({
        _newKey: 'not valid json{{{',
        _legacyKey: jsonEncode([_server('fallback').toJson()]),
      });
      final prefs = await SharedPreferences.getInstance();
      final state = ServersRepository(prefs).load();

      expect(state.servers.single.id, 'fallback');
    });

    test(
        'a corrupt v2 blob with no legacy key returns empty instead of throwing',
        () async {
      SharedPreferences.setMockInitialValues({_newKey: 'not valid json{{{'});
      final prefs = await SharedPreferences.getInstance();
      final state = ServersRepository(prefs).load();

      expect(state.servers, isEmpty);
      expect(state.ungroupedDatabases, isEmpty);
    });

    test('saveAll writes only the v2 key, leaving the legacy key untouched',
        () async {
      SharedPreferences.setMockInitialValues({
        _legacyKey: jsonEncode([_server('pre-upgrade').toJson()]),
      });
      final prefs = await SharedPreferences.getInstance();
      final repo = ServersRepository(prefs);

      await repo.saveAll(ServersState(servers: [_server('post-upgrade')]));

      // The legacy key is exactly what it was before — a hypothetical
      // downgrade to an older Faro build would still read it and work,
      // just missing whatever was added after the upgrade.
      final legacyRaw = prefs.getString(_legacyKey);
      expect(jsonDecode(legacyRaw!), [_server('pre-upgrade').toJson()]);

      final reloaded = ServersRepository(prefs).load();
      expect(reloaded.servers.single.id, 'post-upgrade');
    });
  });
}
