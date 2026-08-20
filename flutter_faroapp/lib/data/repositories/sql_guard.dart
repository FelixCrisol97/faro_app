/// Thrown when a database in "Solo lectura" mode is asked to run something
/// other than a SELECT. README.md: "esto debe ser exigido por el
/// backend/capa de consultas real, no solo por la UI" — this class is that
/// enforcement point, called by `QueryExecutionService` per-database (mode
/// lives on `DatabaseEntry`, not `Server` — see its doc comment) before any
/// connector touches the network.
class ReadOnlyViolationException implements Exception {
  const ReadOnlyViolationException(this.statement,
      {required this.recognizedMutation});

  final String statement;

  /// True when the statement was recognized as an actual mutating
  /// statement (a keyword like `insert`/`update`/`drop`/... was found) —
  /// false when the guard simply couldn't confirm the statement is a
  /// SELECT at all (e.g. a typo like `selectasd existencias`). Either way
  /// the statement is blocked — [SqlGuard] is a whitelist ("only let
  /// through what's recognizably SELECT"), not a blocklist, since
  /// rejecting anything unrecognized is safer than guessing it's harmless
  /// — but the message shouldn't claim "read-only mode caught a mutation"
  /// when really it just couldn't tell what the statement even was.
  final bool recognizedMutation;

  @override
  String toString() => recognizedMutation
      ? 'Esta base de datos está en modo Solo lectura: solo se permiten consultas SELECT.'
      : 'No se reconoce esta sentencia como una consulta SELECT válida — se bloqueó por seguridad (modo Solo lectura). Revisa la sintaxis.';
}

/// First-line-of-defense statement check. This is a syntactic guard, not a
/// substitute for real database-level read-only permissions (a dev/DBA
/// should also grant the app's DB user SELECT-only rights on read-only
/// servers) — belt and suspenders, not either/or.
class SqlGuard {
  const SqlGuard._();

  static final RegExp _leadingKeyword =
      RegExp(r'^\s*(\w+)', caseSensitive: false);

  static const _mutatingKeywords = {
    'insert',
    'update',
    'delete',
    'merge',
    'truncate',
    'drop',
    'alter',
    'create',
    'grant',
    'revoke',
    'exec',
    'execute',
    'call',
    // Not itself a statement-starter, but `SELECT ... INTO new_table FROM
    // ...` is a real mutation (creates a table) while still starting with
    // `select` — this is the only keyword here whose job is to be found by
    // the second (whole-statement) scan below, not the leading-keyword one.
    'into',
  };

  /// Blanks out every string literal (`'...'`), quoted identifier
  /// (`"..."` Postgres, `[...]` SQL Server), and comment (`--`/`/* */`) in
  /// [input], replacing each with spaces of the same length. Keeps the
  /// keyword regexes below from (a) matching a mutating word that only
  /// appears inside a literal/quoted name/comment (false block — e.g.
  /// `WHERE accion = 'update'`, `SELECT [Delete] FROM t`) and (b) missing
  /// the real leading keyword because a comment sits in front of it (false
  /// rejection of a legitimate SELECT). Never throws — an unterminated
  /// literal/comment just runs to the end of the string, same as
  /// `sql_tokenizer.dart`'s `tokenizeSql`.
  static String _maskProtectedSpans(String input) {
    final out = StringBuffer();
    var i = 0;
    while (i < input.length) {
      final ch = input[i];
      if (ch == "'" || ch == '"') {
        final quote = ch;
        final start = i;
        i++;
        while (i < input.length) {
          if (input[i] == quote) {
            if (i + 1 < input.length && input[i + 1] == quote) {
              i += 2; // escaped '' or "" inside the literal/identifier
              continue;
            }
            i++;
            break;
          }
          i++;
        }
        out.write(' ' * (i - start));
      } else if (ch == '[') {
        final start = i;
        i++;
        while (i < input.length && input[i] != ']') {
          i++;
        }
        if (i < input.length) i++; // consume ']'
        out.write(' ' * (i - start));
      } else if (ch == '-' && i + 1 < input.length && input[i + 1] == '-') {
        final start = i;
        final newline = input.indexOf('\n', i);
        i = newline == -1 ? input.length : newline;
        out.write(' ' * (i - start));
      } else if (ch == '/' && i + 1 < input.length && input[i + 1] == '*') {
        final start = i;
        final end = input.indexOf('*/', i + 2);
        i = end == -1 ? input.length : end + 2;
        out.write(' ' * (i - start));
      } else {
        out.write(ch);
        i++;
      }
    }
    return out.toString();
  }

  /// Throws [ReadOnlyViolationException] if [statement] isn't a SELECT.
  static void assertReadOnly(String statement) {
    final masked = _maskProtectedSpans(statement);
    final match = _leadingKeyword.firstMatch(masked);
    final keyword = match?.group(1)?.toLowerCase();
    if (keyword == null || keyword != 'select' && keyword != 'with') {
      throw ReadOnlyViolationException(statement,
          recognizedMutation:
              keyword != null && _mutatingKeywords.contains(keyword));
    }
    if (_mutatingKeywords.any((word) =>
        RegExp(r'\b' + word + r'\b', caseSensitive: false)
            .hasMatch(masked))) {
      throw ReadOnlyViolationException(statement, recognizedMutation: true);
    }
  }
}
