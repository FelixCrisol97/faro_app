import 'package:faro/features/consulta/application/sql_autocomplete.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('detectAutocompleteTrigger', () {
    test('triggers table suggestions after FROM with a partial word', () {
      final trigger = detectAutocompleteTrigger('SELECT * FROM prod');
      expect(trigger, isNotNull);
      expect(trigger!.target, AutocompleteTarget.table);
      expect(trigger.partial, 'prod');
      expect(trigger.replaceFrom, 'SELECT * FROM '.length);
    });

    test('triggers table suggestions after bare FROM with no partial yet', () {
      final trigger = detectAutocompleteTrigger('SELECT * FROM ');
      expect(trigger, isNotNull);
      expect(trigger!.target, AutocompleteTarget.table);
      expect(trigger.partial, '');
    });

    test('triggers column suggestions after WHERE', () {
      final trigger = detectAutocompleteTrigger('SELECT * FROM t WHERE cli');
      expect(trigger, isNotNull);
      expect(trigger!.target, AutocompleteTarget.column);
      expect(trigger.partial, 'cli');
    });

    test('triggers column suggestions after a comma inside a SELECT list', () {
      final trigger = detectAutocompleteTrigger('SELECT a, b, c');
      expect(trigger, isNotNull);
      expect(trigger!.target, AutocompleteTarget.column);
      expect(trigger.partial, 'c');
    });

    test(
        'does not trigger on a comma inside an open function call in a SELECT list',
        () {
      // Parens are unbalanced since the nearest SELECT/ORDER BY/GROUP BY —
      // this comma belongs to COALESCE's argument list, not the column list.
      final trigger = detectAutocompleteTrigger('SELECT COALESCE(a, ');
      expect(trigger, isNull);
    });

    test('does not trigger on a comma after WHERE (not a column-list clause)',
        () {
      final trigger = detectAutocompleteTrigger('SELECT * FROM t WHERE a IN (1, ');
      expect(trigger, isNull);
    });

    test('does not trigger mid-sentence with no recognizable keyword', () {
      final trigger = detectAutocompleteTrigger('SELECT * FROM productos ord');
      expect(trigger, isNull);
    });

    test('replaceFrom anchors at the partial word, not the trigger keyword',
        () {
      // Regression guard: `match.start` (start of "FROM ...") was used here
      // once by mistake and corrupted inserted text.
      const text = 'SELECT * FROM prod';
      final trigger = detectAutocompleteTrigger(text);
      expect(text.substring(trigger!.replaceFrom), 'prod');
    });

    test(
        'triggers table suggestions right after a schema-qualified prefix '
        '(real bug, 2026-08-03: "FROM dbo." used to never trigger at all)',
        () {
      final trigger = detectAutocompleteTrigger('SELECT * FROM dbo.');
      expect(trigger, isNotNull);
      expect(trigger!.target, AutocompleteTarget.table);
      expect(trigger.partial, '');
    });

    test('triggers table suggestions with a partial after a schema prefix',
        () {
      const text = 'SELECT * FROM dbo.cli';
      final trigger = detectAutocompleteTrigger(text);
      expect(trigger, isNotNull);
      expect(trigger!.target, AutocompleteTarget.table);
      expect(trigger.partial, 'cli');
      // Only the part after the schema prefix should be replaced.
      expect(text.substring(trigger.replaceFrom), 'cli');
    });
  });

  group('isInsideColumnList', () {
    test('true right after SELECT', () {
      expect(isInsideColumnList('SELECT '), isTrue);
    });

    test('true after a balanced function call in the SELECT list', () {
      expect(isInsideColumnList('SELECT COALESCE(a, b), '), isTrue);
    });

    test('false inside an open function call', () {
      expect(isInsideColumnList('SELECT COALESCE(a, '), isFalse);
    });

    test('false after WHERE', () {
      expect(isInsideColumnList('SELECT * FROM t WHERE '), isFalse);
    });

    test('false with no clause keyword at all', () {
      expect(isInsideColumnList('a, b, '), isFalse);
    });
  });

  group('referencedTablesKey', () {
    test('collects every FROM/JOIN target, lowercased and sorted', () {
      final key = referencedTablesKey(
          'SELECT * FROM Ventas JOIN Productos ON Ventas.id = Productos.id');
      expect(key, 'productos,ventas');
    });

    test('deduplicates repeated table references', () {
      final key =
          referencedTablesKey('SELECT * FROM ventas JOIN ventas ON 1=1');
      expect(key, 'ventas');
    });

    test('empty when nothing is referenced yet', () {
      expect(referencedTablesKey('SELECT '), '');
    });

    test('known gap: a FROM inside a string literal is misread as a real reference', () {
      // Documents the exact bug `referencedTablesKeySafe` below fixes —
      // this one has no idea it's inside a string.
      final key = referencedTablesKey(
          "SELECT * FROM ventas WHERE nota = 'importado desde FROM antiguo'");
      expect(key, 'antiguo,ventas');
    });

    test(
        'real bug fixed 2026-08-03: a schema-qualified table captures the '
        'table name, not the schema', () {
      final key = referencedTablesKey('SELECT * FROM dbo.clientes');
      expect(key, 'clientes');
    });

    test('schema-qualified tables in a JOIN also resolve to the table name',
        () {
      final key = referencedTablesKey(
          'SELECT * FROM dbo.ventas JOIN dbo.productos ON 1=1');
      expect(key, 'productos,ventas');
    });
  });

  group('referencedTablesKeySafe', () {
    test('same output as referencedTablesKey on ordinary input', () {
      const sql =
          'SELECT * FROM Ventas JOIN Productos ON Ventas.id = Productos.id';
      expect(referencedTablesKeySafe(sql), referencedTablesKey(sql));
    });

    test('a FROM/JOIN-looking word inside a string literal is not a table', () {
      final key = referencedTablesKeySafe(
          "SELECT * FROM ventas WHERE nota = 'importado desde FROM antiguo'");
      expect(key, 'ventas');
    });

    test('a FROM/JOIN-looking word inside a comment is not a table', () {
      final key = referencedTablesKeySafe(
          '-- ver también FROM productos_viejo\nSELECT * FROM ventas');
      expect(key, 'ventas');
    });

    test('deduplicates and sorts, same as referencedTablesKey', () {
      final key = referencedTablesKeySafe(
          'SELECT * FROM ventas JOIN ventas ON 1=1');
      expect(key, 'ventas');
    });

    test('empty when nothing is referenced yet', () {
      expect(referencedTablesKeySafe('SELECT '), '');
    });

    test('never throws on a script with unterminated strings/comments/dollar-quotes', () {
      expect(() => referencedTablesKeySafe("SELECT * FROM t WHERE x = 'never closed"),
          returnsNormally);
      expect(() => referencedTablesKeySafe('SELECT * FROM t /* never closed'),
          returnsNormally);
      expect(() => referencedTablesKeySafe(r'SELECT * FROM t; $$ never closed'),
          returnsNormally);
    });
  });

  group('referencedTablesKeySafe — performance data point (not yet wired into the live editor)', () {
    test('a large synthetic script completes well within a generous bound', () {
      final buffer = StringBuffer();
      for (var i = 0; i < 1000; i++) {
        buffer.writeln("SELECT a, b, c FROM tabla_$i WHERE x = 'valor $i' -- fila $i");
      }
      final sql = buffer.toString();
      final stopwatch = Stopwatch()..start();
      referencedTablesKeySafe(sql);
      stopwatch.stop();
      // Generous, informational bound — see referencedTablesKeySafe's doc
      // comment for why this isn't wired into sql_editor.dart's per-
      // keystroke path yet despite passing this: a synthetic microbenchmark
      // run once isn't the same as measuring real per-keystroke cost in
      // the actual debounced/undebounced call sites.
      expect(stopwatch.elapsedMilliseconds, lessThan(500));
    });
  });

  group('filterSuggestions', () {
    final names = ['clientes', 'Cliente_direcciones', 'ventas', 'productos'];

    test('filters by case-insensitive prefix', () {
      expect(filterSuggestions(names, 'cli', 50),
          ['clientes', 'Cliente_direcciones']);
    });

    test('returns everything for an empty partial', () {
      expect(filterSuggestions(names, '', 50), names);
    });

    test('caps results at maxResults', () {
      expect(filterSuggestions(names, '', 2), ['clientes', 'Cliente_direcciones']);
    });
  });

  group('wordAt', () {
    test('word the caret sits inside', () {
      expect(wordAt('SELECT clientes FROM t', 9), 'clientes');
    });

    test('word the caret sits right after', () {
      expect(wordAt('SELECT clientes ', 15), 'clientes');
    });

    test('null when the caret sits in the middle of a run of whitespace', () {
      // A single space still counts as "touching" the adjacent word (see
      // the class doc: clicking right after a word counts as being on it),
      // so this needs a wider gap to land on genuinely empty ground.
      expect(wordAt('a    b', 3), isNull);
    });

    test('null for empty text', () {
      expect(wordAt('', 0), isNull);
    });
  });
}
