/// Splits a SQL string into code vs. "protected" (string literal / quoted
/// identifier / line or block comment) spans — shared by
/// `sql_formatter.dart` (so it never reflows/uppercases inside one) and
/// `sql_syntax_highlighter.dart` (so it never colors keyword-looking text
/// inside one either).
enum SqlTokenKind { code, string, quotedIdentifier, lineComment, blockComment }

class SqlToken {
  const SqlToken(this.kind, this.text);
  final SqlTokenKind kind;
  final String text;
}

List<SqlToken> tokenizeSql(String input) {
  final tokens = <SqlToken>[];
  final code = StringBuffer();
  var i = 0;

  void flushCode() {
    if (code.isNotEmpty) {
      tokens.add(SqlToken(SqlTokenKind.code, code.toString()));
      code.clear();
    }
  }

  while (i < input.length) {
    final ch = input[i];
    if (ch == "'" || ch == '"') {
      // Real bug fixed 2026-08-03 (AUDITORIA_CODIGO.md): `"..."` (a
      // double-quoted, case-sensitive Postgres identifier) used to fall
      // straight through to the `code` branch below, so a keyword-looking
      // quoted identifier — `SELECT "from" FROM tabla;` — got reflowed and
      // recapitalized by `sql_formatter.dart` right along with real code,
      // corrupting the identifier. Same escape rule as a string literal
      // (`''`/`""` doubling), just a different quote character and token
      // kind so callers can still tell the two apart if they ever need to.
      final quote = ch;
      final kind =
          quote == "'" ? SqlTokenKind.string : SqlTokenKind.quotedIdentifier;
      flushCode();
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
      tokens.add(SqlToken(kind, input.substring(start, i)));
    } else if (ch == '-' && i + 1 < input.length && input[i + 1] == '-') {
      flushCode();
      final start = i;
      final newline = input.indexOf('\n', i);
      i = newline == -1 ? input.length : newline;
      tokens.add(SqlToken(SqlTokenKind.lineComment, input.substring(start, i)));
    } else if (ch == '/' && i + 1 < input.length && input[i + 1] == '*') {
      flushCode();
      final start = i;
      final end = input.indexOf('*/', i + 2);
      i = end == -1 ? input.length : end + 2;
      tokens
          .add(SqlToken(SqlTokenKind.blockComment, input.substring(start, i)));
    } else {
      code.write(ch);
      i++;
    }
  }
  flushCode();
  return tokens;
}
