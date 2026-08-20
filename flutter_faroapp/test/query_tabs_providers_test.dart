import 'package:faro/features/consulta/application/query_tabs_providers.dart';
import 'package:flutter_test/flutter_test.dart';

QueryTabMeta _tab(String id) =>
    QueryTabMeta(id: id, serverId: 'server', databaseId: 'db-$id');

void main() {
  group('nextActiveTabIdAfterClosing', () {
    test('returns null (home tab) when closing the only open tab', () {
      final before = [_tab('a')];
      final result = nextActiveTabIdAfterClosing('a', before, const []);
      expect(result, isNull);
    });

    test('activates the tab that falls into the closed one\'s spot', () {
      final before = [_tab('a'), _tab('b'), _tab('c')];
      final after = [_tab('a'), _tab('c')];
      final result = nextActiveTabIdAfterClosing('b', before, after);
      expect(result, 'c');
    });

    test('closing the last tab activates the new last tab', () {
      final before = [_tab('a'), _tab('b'), _tab('c')];
      final after = [_tab('a'), _tab('b')];
      final result = nextActiveTabIdAfterClosing('c', before, after);
      expect(result, 'b');
    });

    test('closing the first tab activates the new first tab', () {
      final before = [_tab('a'), _tab('b'), _tab('c')];
      final after = [_tab('b'), _tab('c')];
      final result = nextActiveTabIdAfterClosing('a', before, after);
      expect(result, 'b');
    });
  });
}
