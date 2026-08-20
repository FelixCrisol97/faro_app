import 'package:faro/Faroapp.dart';
import 'package:faro/data/providers/core_providers.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  testWidgets(
      'App boots with the tree visible and no servers configured yet '
      '(2026-08-12: the tree is now the permanent left pane — see '
      '`AppShell` — replacing the old 5-tab nav bar this test used to '
      'check for)', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();

    await tester.pumpWidget(
      ProviderScope(
        overrides: [sharedPreferencesProvider.overrideWithValue(prefs)],
        child: const FaroApp(),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('+ Agregar base de datos'), findsOneWidget);
  });
}
