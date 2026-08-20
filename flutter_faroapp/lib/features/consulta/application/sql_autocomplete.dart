// Pure, unit-testable autocomplete logic for `sql_editor.dart` — trigger
// detection, the "inside a column list" heuristic, and suggestion
// filtering. No Flutter/Riverpod dependency; `_SqlEditorState` is the only
// caller, and only wires this up to the real provider data + overlay UI.
// Same "pure function, no ProviderContainer needed" philosophy as
// `sql_statement_resolver.dart`.

import '../../../sql_analysis/lexer/sql_lexer.dart';
import '../../../sql_analysis/lexer/sql_token.dart';

/// Which kind of name an [AutocompleteTrigger] wants suggested.
enum AutocompleteTarget { table, column }

/// A detected autocomplete opportunity at the cursor.
class AutocompleteTrigger {
  const AutocompleteTrigger({
    required this.target,
    required this.replaceFrom,
    required this.partial,
  });

  final AutocompleteTarget target;

  /// Start offset of [partial] within the scanned text — NOT the start of
  /// the trigger keyword itself. Every trigger regex is anchored at the
  /// end (`$`), so this is `match.end - partial.length`. Using the match's
  /// own start here instead was a real past bug: it replaced the keyword
  /// (and whatever followed it) instead of just the partial word.
  final int replaceFrom;
  final String partial;
}

// Real bug fixed 2026-08-03 (AUDITORIA_CODIGO.md): `\w*` doesn't match `.`,
// so this never fired at all once a schema-qualified prefix was typed
// (`FROM dbo.` / `FROM dbo.cli`) — table-name autocomplete went silent
// right after the exact `schema.table` pattern this app's own
// `sql_script_generator.dart` always generates. The optional non-capturing
// `(?:\w+\.)?` absorbs a single `schema.` prefix so the capture group
// stays just the table-name partial being typed — same scope as
// [_referencedTablePattern] below (one qualifier level, not a full
// `database.schema.table` chain).
final _fromPartial =
    RegExp(r'FROM\s+(?:\w+\.)?(\w*)$', caseSensitive: false);

// Second autocomplete trigger: suggest column names (instead of table
// names) after any of these — all clearly "expecting a column/expression
// next" positions.
final _columnPartial = RegExp(
    r'(?:WHERE|AND|OR|ON|HAVING|ORDER BY|GROUP BY|SELECT)\s+(\w*)$',
    caseSensitive: false);

// Third trigger: a comma inside a column list (SELECT's own list, or
// ORDER BY/GROUP BY's) — e.g. `SELECT a, <partial>` or `ORDER BY a,
// <partial>`. Deliberately conservative: only fires when the *nearest*
// preceding clause keyword ([isInsideColumnList]) is SELECT/ORDER BY/GROUP
// BY specifically (not FROM/WHERE/JOIN/ON/HAVING) *and* parens are balanced
// since that keyword — the second half is what rules out a comma sitting
// inside an open function call like `SELECT COALESCE(a, <partial>`.
final _columnListCommaPartial = RegExp(r',\s*(\w*)$');

final _clauseKeyword = RegExp(
    r'\b(SELECT|FROM|WHERE|JOIN|ON|HAVING|ORDER BY|GROUP BY)\b',
    caseSensitive: false);
const _columnListClauses = {'select', 'order by', 'group by'};

/// Which table(s) to suggest columns from — scans the *whole* query text
/// (not just up to the cursor, unlike the triggers above) for every
/// FROM/JOIN target, same regex-only sophistication those triggers use (no
/// real SQL parsing, no protection from string literals/comments).
/// Multiple tables (a JOIN) get their columns unioned rather than
/// disambiguated per-table.
// Real bug fixed 2026-08-03 (AUDITORIA_CODIGO.md): `FROM dbo.clientes`
// used to capture "dbo" (the schema) instead of "clientes" (the actual
// table) — `\w+` right after `FROM\s+` simply stopped at the first `.`.
// The optional non-capturing `(?:\w+\.)?` absorbs a single schema prefix
// so group 1 stays the real table name; deliberately scoped to one
// qualifier level (`schema.table`), not a full `database.schema.table`
// chain — the reported/generated case is always the former.
final _referencedTablePattern =
    RegExp(r'(?:FROM|JOIN)\s+(?:\w+\.)?(\w+)', caseSensitive: false);

final _wordChar = RegExp(r'[A-Za-z0-9_]');

/// True when [upToCursor] ends inside a SELECT/ORDER BY/GROUP BY column
/// list (not e.g. a function call's argument list) — see
/// [_columnListCommaPartial]'s doc comment.
bool isInsideColumnList(String upToCursor) {
  final matches = _clauseKeyword.allMatches(upToCursor);
  if (matches.isEmpty) return false;
  final last = matches.last;
  if (!_columnListClauses.contains(last.group(1)!.toLowerCase())) {
    return false;
  }
  final sinceClause = upToCursor.substring(last.start);
  final opens = '('.allMatches(sinceClause).length;
  final closes = ')'.allMatches(sinceClause).length;
  return opens == closes;
}

/// Detects which (if any) autocomplete trigger the cursor currently sits
/// in, given the text up to the cursor. `FROM <partial>` (table names) is
/// checked first, then the column-name triggers.
AutocompleteTrigger? detectAutocompleteTrigger(String upToCursor) {
  final fromMatch = _fromPartial.firstMatch(upToCursor);
  if (fromMatch != null) {
    final partial = fromMatch.group(1) ?? '';
    return AutocompleteTrigger(
      target: AutocompleteTarget.table,
      replaceFrom: fromMatch.end - partial.length,
      partial: partial,
    );
  }

  final columnMatch = _columnPartial.firstMatch(upToCursor) ??
      (_columnListCommaPartial.hasMatch(upToCursor) &&
              isInsideColumnList(upToCursor)
          ? _columnListCommaPartial.firstMatch(upToCursor)
          : null);
  if (columnMatch != null) {
    final partial = columnMatch.group(1) ?? '';
    return AutocompleteTrigger(
      target: AutocompleteTarget.column,
      replaceFrom: columnMatch.end - partial.length,
      partial: partial,
    );
  }

  return null;
}

/// Every distinct table referenced via `FROM`/`JOIN` anywhere in [text],
/// lowercased and sorted, joined into one `String` — used both as the
/// `columnNamesProvider`/`tabColumnNamesProvider` family key (a String
/// rather than a List/Set so Riverpod's equality check actually matches an
/// unchanged reference set across rebuilds) and to decide whether there's
/// anything to suggest columns from at all.
///
/// Still the same regex-only sophistication as before (no real SQL
/// parsing here — see [referencedTablesKeySafe] in this same file for a
/// tokenizer-based fix to this function's one known gap: a `FROM`/`JOIN`
/// inside a string literal or comment gets misread as a real reference).
/// Kept as the version actually wired into `sql_editor.dart` for now,
/// deliberately — see that function's doc comment for why.
String referencedTablesKey(String text) {
  final tables = <String>{
    for (final m in _referencedTablePattern.allMatches(text))
      m.group(1)!.toLowerCase(),
  };
  return (tables.toList()..sort()).join(',');
}

/// Same contract as [referencedTablesKey] — same regex, same output shape
/// (lowercased, sorted, comma-joined table names) — but tokenizes [text]
/// first (`lib/sql_analysis/lexer`'s `lexSql`) and only scans the
/// non-string/non-comment tokens' text, so a `FROM`/`JOIN`-looking word
/// inside a string literal or comment (e.g. `WHERE nombre = 'importado
/// desde FROM antiguo'`) is never misread as a real table reference — a
/// real, demonstrated gap in [referencedTablesKey], which has no idea
/// where strings/comments start or end.
///
/// **Deliberately not yet wired into `sql_editor.dart` in place of
/// [referencedTablesKey].** Both of `sql_editor.dart`'s call sites run
/// this kind of extraction on every keystroke — one directly gates the
/// actual autocomplete popup, the other proactively "prewarms"
/// `columnNamesProvider` in `build()` so the data's already cached by the
/// time the user reaches a trigger position. `sql_syntax_highlighter.dart`
/// (2026-07-24) already hit — and had to explicitly benchmark and fix — a
/// real performance regression from an undebounced per-keystroke text
/// analysis at 1000-line script scale; this function does strictly more
/// work per character than that fix's simple 4-kind tokenizer
/// (`sql_tokenizer.dart`), so wiring it into the same hot, undebounced
/// path without first measuring against a comparably large script would
/// be repeating that exact mistake, not avoiding it. Safe to promote once
/// that's actually measured (and, if needed, given the same debounce
/// `HighlightingController` already uses for syntax coloring) — tracked
/// as the next step for this module, not done blind here.
String referencedTablesKeySafe(String text) {
  final codeOnly = StringBuffer();
  for (final token in lexSql(text)) {
    switch (token.type) {
      case SqlTokenType.string:
      case SqlTokenType.dollarString:
      case SqlTokenType.lineComment:
      case SqlTokenType.blockComment:
        break;
      default:
        codeOnly
          ..write(token.text)
          ..write(' ');
    }
  }
  final tables = <String>{
    for (final m in _referencedTablePattern.allMatches(codeOnly.toString()))
      m.group(1)!.toLowerCase(),
  };
  return (tables.toList()..sort()).join(',');
}

/// Case-insensitive prefix filter, capped at [maxResults] — a database with
/// thousands of tables (a real client scale, not hypothetical) can match
/// hundreds/thousands of names against a short prefix like `t_`. Nobody
/// scrolls a 5000-item suggestion popup anyway — narrowing further by
/// typing is the actual workflow — so this both caps real allocation cost
/// and is better UX on its own.
List<String> filterSuggestions(
    List<String> names, String partial, int maxResults) {
  final lowerPartial = partial.toLowerCase();
  return names
      .where((n) => n.toLowerCase().startsWith(lowerPartial))
      .take(maxResults)
      .toList();
}

/// The identifier touching [offset] in [text] — including when the caret
/// sits right after it (clicking at the end of a word should still count
/// as being "on" it, matching how most editors define word-under-caret).
String? wordAt(String text, int offset) {
  if (text.isEmpty) return null;
  bool isWordChar(int i) =>
      i >= 0 && i < text.length && _wordChar.hasMatch(text[i]);

  var start = offset;
  if (!isWordChar(start) && isWordChar(start - 1)) start -= 1;
  if (!isWordChar(start)) return null;
  while (start > 0 && isWordChar(start - 1)) {
    start--;
  }

  var end = offset;
  while (end < text.length && isWordChar(end)) {
    end++;
  }

  final word = text.substring(start, end);
  return word.isEmpty ? null : word;
}
