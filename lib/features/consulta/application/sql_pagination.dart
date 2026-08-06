import '../../../core/constants/db_engine.dart';
import 'sql_tokenizer.dart';

/// How many rows a single page brings back. Fetched as `pageSize + 1` (see
/// [wrapForPage]) so "is there another page?" comes for free from the count
/// of rows actually returned, without a separate `COUNT(*)` round-trip.
const paginationPageSize = 5000;

final _leadingKeyword = RegExp(r'^\s*(\w+)', caseSensitive: false);
final _pagingKeyword =
    RegExp(r'\b(limit|offset|top)\b', caseSensitive: false);

/// Whether [statement] is safe to wrap in a `LIMIT`/`OFFSET` page —
/// recognizably a `SELECT`/`WITH` (same leading-keyword idea as
/// `SqlGuard._leadingKeyword`, not shared directly since that class is
/// about a different concern — read-only enforcement, not pagination), and
/// not already paginated by the user themselves. [tokenizeSql] (already
/// used by `sql_formatter.dart`/`sql_syntax_highlighter.dart`) strips out
/// string literals and comments first, so a column named `top_customer` or
/// a comment mentioning "limit" doesn't false-positive as "already
/// paginated."
///
/// Real bug fixed 2026-08-03 (AUDITORIA_CODIGO.md): the leading-keyword
/// check used to run against the raw [statement] *before* stripping
/// comments — a script starting with a header comment (`-- Reporte de
/// ventas\nSELECT ...`, a common pattern) has no word character at all
/// right after `^\s*`, so it never matched, `isPaginableSelect` returned
/// `false`, and the whole point of this module (not fetching every row of
/// a large result up front) silently didn't apply to the most ordinary
/// case of a commented script. Fixed by tokenizing first and checking the
/// leading keyword against the comment-free `code`, same as the
/// already-paginated check right below it.
bool isPaginableSelect(String statement) {
  final code = tokenizeSql(statement)
      .where((t) => t.kind == SqlTokenKind.code)
      .map((t) => t.text)
      .join();
  final leading = _leadingKeyword.firstMatch(code)?.group(1)?.toLowerCase();
  if (leading != 'select' && leading != 'with') return false;

  return !_pagingKeyword.hasMatch(code);
}

/// Wraps [statement] (assumed already [isPaginableSelect]) as a subquery
/// fetching one page — [offset]/[fetchCount] are always app-computed
/// integers (never raw user input), so interpolating them directly into the
/// SQL text is safe; no need for `DbConnector.runQuery` to grow parameter
/// support just for this.
String wrapForPage(
  String statement,
  DbEngine engine, {
  required int offset,
  required int fetchCount,
}) {
  final inner = 'SELECT * FROM (\n$statement\n) AS _faro_page';
  return switch (engine) {
    // No ORDER BY from the app itself — see the module doc comment above
    // for the accepted "page boundaries aren't guaranteed stable without
    // one" caveat.
    DbEngine.postgres => '$inner LIMIT $fetchCount OFFSET $offset',
    // T-SQL's OFFSET/FETCH requires an ORDER BY; `ORDER BY (SELECT NULL)`
    // is the standard trick to page without imposing a real order.
    DbEngine.sqlServer =>
      '$inner ORDER BY (SELECT NULL) OFFSET $offset ROWS FETCH NEXT $fetchCount ROWS ONLY',
  };
}
