import 'package:faro/data/datasources/cancellation_token.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('CancellationToken', () {
    test('isCancelled is false until cancel is called', () {
      final token = CancellationToken();
      expect(token.isCancelled, isFalse);
      token.cancel();
      expect(token.isCancelled, isTrue);
    });

    test('runs a listener registered before cancel', () {
      final token = CancellationToken();
      var called = false;
      token.onCancel(() => called = true);
      expect(called, isFalse);
      token.cancel();
      expect(called, isTrue);
    });

    test('runs a listener registered after cancel immediately', () {
      final token = CancellationToken();
      token.cancel();
      var called = false;
      token.onCancel(() => called = true);
      expect(called, isTrue);
    });

    test('runs every registered listener exactly once', () {
      final token = CancellationToken();
      var firstCount = 0;
      var secondCount = 0;
      token.onCancel(() => firstCount++);
      token.onCancel(() => secondCount++);
      token.cancel();
      token.cancel();
      expect(firstCount, 1);
      expect(secondCount, 1);
    });
  });
}
