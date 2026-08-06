import 'package:faro/data/datasources/database_connection_config.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('parseHostPort', () {
    test('plain host with port', () {
      final result = parseHostPort('localhost:5432', 9999);
      expect(result.host, 'localhost');
      expect(result.port, 5432);
    });

    test('plain host with no port falls back to default', () {
      final result = parseHostPort('localhost', 9999);
      expect(result.host, 'localhost');
      expect(result.port, 9999);
    });

    test('bare IPv6 literal with no port is kept whole, not split', () {
      final result = parseHostPort('::1', 9999);
      expect(result.host, '::1');
      expect(result.port, 9999);
    });

    test('bare full IPv6 literal with no port is kept whole', () {
      final result = parseHostPort('2001:db8::1', 9999);
      expect(result.host, '2001:db8::1');
      expect(result.port, 9999);
    });

    test('bracketed IPv6 literal with port', () {
      final result = parseHostPort('[::1]:5432', 9999);
      expect(result.host, '::1');
      expect(result.port, 5432);
    });

    test('bracketed IPv6 literal without port falls back to default', () {
      final result = parseHostPort('[2001:db8::1]', 9999);
      expect(result.host, '2001:db8::1');
      expect(result.port, 9999);
    });

    test('empty host string falls back to default port', () {
      final result = parseHostPort('', 9999);
      expect(result.host, '');
      expect(result.port, 9999);
    });

    test('non-numeric port falls back to default', () {
      final result = parseHostPort('localhost:abc', 9999);
      expect(result.host, 'localhost');
      expect(result.port, 9999);
    });
  });
}
