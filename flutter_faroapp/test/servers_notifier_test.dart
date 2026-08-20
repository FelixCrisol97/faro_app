import 'package:faro/core/constants/db_engine.dart';
import 'package:faro/data/models/database_entry.dart';
import 'package:faro/data/models/server.dart';
import 'package:faro/data/providers/core_providers.dart';
import 'package:faro/data/providers/servers_providers.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

DatabaseEntry _db(String id, {DbEngine engine = DbEngine.postgres}) =>
    DatabaseEntry(
        id: id, name: id, host: 'h-$id', databaseName: id, engine: engine);

Server _server(String id, List<DatabaseEntry> databases) => Server(
      id: id,
      name: id,
      databases: databases,
    );

Future<ProviderContainer> _container() async {
  SharedPreferences.setMockInitialValues({});
  final prefs = await SharedPreferences.getInstance();
  final container = ProviderContainer(
    overrides: [sharedPreferencesProvider.overrideWithValue(prefs)],
  );
  addTearDown(container.dispose);
  return container;
}

void main() {
  group('ServersNotifier — drag-and-drop mutators (2026-08-12)', () {
    test('reorderServer moves a server to any position, not just ±1', () async {
      final container = await _container();
      final notifier = container.read(serversProvider.notifier);
      notifier.addServer(_server('a', []));
      notifier.addServer(_server('b', []));
      notifier.addServer(_server('c', []));

      notifier.reorderServer('c', 0);

      expect(
          container.read(serverListProvider).map((s) => s.id), ['c', 'a', 'b']);
    });

    test('reorderServer is a no-op for an id that no longer exists', () async {
      final container = await _container();
      final notifier = container.read(serversProvider.notifier);
      notifier.addServer(_server('a', []));

      notifier.reorderServer('missing', 0);

      expect(container.read(serverListProvider).map((s) => s.id), ['a']);
    });

    test('reorderDatabaseWithinServer moves a database inside its own server',
        () async {
      final container = await _container();
      final notifier = container.read(serversProvider.notifier);
      notifier.addServer(_server('s1', [_db('x'), _db('y'), _db('z')]));

      notifier.reorderDatabaseWithinServer('s1', 'z', 0);

      final databases = container.read(serverListProvider).single.databases;
      expect(databases.map((d) => d.id), ['z', 'x', 'y']);
    });

    test('moveDatabaseToServer moves a database from one server to another',
        () async {
      final container = await _container();
      final notifier = container.read(serversProvider.notifier);
      notifier.addServer(_server('s1', [_db('x')]));
      notifier.addServer(_server('s2', [_db('y')]));

      notifier.moveDatabaseToServer('s1', 'x', 's2');

      final servers = {
        for (final s in container.read(serverListProvider)) s.id: s
      };
      expect(servers['s1']!.databases, isEmpty);
      expect(servers['s2']!.databases.map((d) => d.id), ['y', 'x']);
    });

    test('moveDatabaseToServer respects targetIndex in the destination',
        () async {
      final container = await _container();
      final notifier = container.read(serversProvider.notifier);
      notifier.addServer(_server('s1', [_db('x')]));
      notifier.addServer(_server('s2', [_db('a'), _db('b')]));

      notifier.moveDatabaseToServer('s1', 'x', 's2', targetIndex: 0);

      final servers = {
        for (final s in container.read(serverListProvider)) s.id: s
      };
      expect(servers['s2']!.databases.map((d) => d.id), ['x', 'a', 'b']);
    });

    test(
        'moveDatabaseToServer is a same-server no-op (fromServerId == toServerId)',
        () async {
      final container = await _container();
      final notifier = container.read(serversProvider.notifier);
      notifier.addServer(_server('s1', [_db('x'), _db('y')]));

      notifier.moveDatabaseToServer('s1', 'x', 's1');

      expect(
          container.read(serverListProvider).single.databases.map((d) => d.id),
          ['x', 'y']);
    });

    test(
        'moveDatabaseToServer moves a database out of "Sin grupo" '
        '(fromServerId null)', () async {
      final container = await _container();
      final notifier = container.read(serversProvider.notifier);
      notifier.addServer(_server('s1', []));
      notifier.addUngroupedDatabase(_db('loose'));

      notifier.moveDatabaseToServer(null, 'loose', 's1');

      expect(container.read(ungroupedDatabasesProvider), isEmpty);
      expect(container.read(serverListProvider).single.databases.single.id,
          'loose');
    });

    test('moveDatabaseToUngrouped returns a database to "Sin grupo"', () async {
      final container = await _container();
      final notifier = container.read(serversProvider.notifier);
      notifier.addServer(_server('s1', [_db('x')]));

      notifier.moveDatabaseToUngrouped('s1', 'x');

      expect(container.read(serverListProvider).single.databases, isEmpty);
      expect(container.read(ungroupedDatabasesProvider).single.id, 'x');
    });

    test(
        'createServerFromTwoUngroupedDatabases creates a new server with '
        'both databases and clears them from "Sin grupo"', () async {
      final container = await _container();
      final notifier = container.read(serversProvider.notifier);
      notifier.addUngroupedDatabase(_db('a', engine: DbEngine.sqlServer));
      notifier.addUngroupedDatabase(_db('b'));

      final newServerId =
          notifier.createServerFromTwoUngroupedDatabases('a', 'b');

      expect(newServerId, isNotNull);
      expect(container.read(ungroupedDatabasesProvider), isEmpty);
      final servers = container.read(serverListProvider);
      expect(servers.single.id, newServerId);
      expect(servers.single.databases.map((d) => d.id), ['b', 'a']);
      // No server-level engine to guess/merge — each database keeps
      // whichever engine it already had before the merge.
      final byId = {for (final d in servers.single.databases) d.id: d};
      expect(byId['a']!.engine, DbEngine.sqlServer);
      expect(byId['b']!.engine, DbEngine.postgres);
    });

    test(
        'createServerFromTwoUngroupedDatabases is a no-op when dropped on itself',
        () async {
      final container = await _container();
      final notifier = container.read(serversProvider.notifier);
      notifier.addUngroupedDatabase(_db('a'));

      final result = notifier.createServerFromTwoUngroupedDatabases('a', 'a');

      expect(result, isNull);
      expect(container.read(ungroupedDatabasesProvider).single.id, 'a');
      expect(container.read(serverListProvider), isEmpty);
    });

    test(
        'createServerFromTwoUngroupedDatabases is a no-op when either id is '
        'no longer in "Sin grupo" (a drop computed against a stale list)',
        () async {
      final container = await _container();
      final notifier = container.read(serversProvider.notifier);
      notifier.addUngroupedDatabase(_db('a'));

      final result =
          notifier.createServerFromTwoUngroupedDatabases('a', 'gone');

      expect(result, isNull);
      expect(container.read(ungroupedDatabasesProvider).single.id, 'a');
    });

    test('reorderUngroupedDatabase reorders within "Sin grupo"', () async {
      final container = await _container();
      final notifier = container.read(serversProvider.notifier);
      notifier.addUngroupedDatabase(_db('a'));
      notifier.addUngroupedDatabase(_db('b'));
      notifier.addUngroupedDatabase(_db('c'));

      notifier.reorderUngroupedDatabase('c', 0);

      expect(container.read(ungroupedDatabasesProvider).map((d) => d.id),
          ['c', 'a', 'b']);
    });

    test('setDatabaseEngine changes only that database\'s engine', () async {
      final container = await _container();
      final notifier = container.read(serversProvider.notifier);
      notifier.addServer(_server('s1', [_db('x'), _db('y')]));

      notifier.setDatabaseEngine('s1', 'x', DbEngine.sqlServer);

      final databases = container.read(serverListProvider).single.databases;
      final byId = {for (final d in databases) d.id: d};
      expect(byId['x']!.engine, DbEngine.sqlServer);
      expect(byId['y']!.engine, DbEngine.postgres);
    });

    test('setDatabaseEngine works on a "Sin grupo" database (serverId null)',
        () async {
      final container = await _container();
      final notifier = container.read(serversProvider.notifier);
      notifier.addUngroupedDatabase(_db('loose'));

      notifier.setDatabaseEngine(null, 'loose', DbEngine.sqlServer);

      expect(container.read(ungroupedDatabasesProvider).single.engine,
          DbEngine.sqlServer);
    });

    test('every mutation here persists — reloading from disk matches state',
        () async {
      final container = await _container();
      final notifier = container.read(serversProvider.notifier);
      notifier.addServer(_server('s1', [_db('x')]));
      notifier.addUngroupedDatabase(_db('loose'));
      notifier.moveDatabaseToServer(null, 'loose', 's1', targetIndex: 0);

      final reloaded = container.read(serversRepositoryProvider).load();
      expect(
          reloaded.servers.single.databases.map((d) => d.id), ['loose', 'x']);
      expect(reloaded.ungroupedDatabases, isEmpty);
    });
  });
}
