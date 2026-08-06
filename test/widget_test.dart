import 'package:faro/Faroapp.dart';
import 'package:faro/data/providers/core_providers.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  testWidgets('App boots to the Consulta screen with the nav bar visible',
      (tester) async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();

    await tester.pumpWidget(
      ProviderScope(
        overrides: [sharedPreferencesProvider.overrideWithValue(prefs)],
        child: const FaroApp(),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Consulta'), findsWidgets);
  });
}
