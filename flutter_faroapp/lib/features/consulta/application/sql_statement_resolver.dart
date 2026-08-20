import '../../../sql_analysis/analysis/script_splitter.dart'
    show splitSqlStatements;

export '../../../sql_analysis/analysis/script_splitter.dart'
    show SqlStatementSpan, splitSqlStatements;

/// README.md "Main — toolbar card": "Si el desarrollador selecciona un
/// rango de texto en el editor, esa selección exacta es lo que se ejecuta."
///
/// Extended: when the editor holds several full statements typed one per
/// line *without* semicolons (e.g. pasting a handful of one-off SELECTs),
/// splitting only on `;` used to treat the whole thing as a single
/// statement — concatenating unrelated SELECTs into one invalid query that
/// silently failed on every database. [splitSqlStatements] now also
/// recognizes a new statement starting at a line that begins with a
/// top-level SQL keyword.
///
/// **No selection = run every statement in the box, in order** (real
/// behavior change, 2026-07-22, user-requested). This used to instead run
/// just the one statement the cursor happened to be inside (or the last
/// one, if the cursor wasn't clearly inside any) — matching how some SQL
/// IDEs decide "run current statement." The user found that surprising and
/// unsafe with several unrelated statements in one editor (clicking into
/// one to inspect it and hitting Play could silently run just that one,
/// not the whole script they meant to run) and asked for the SSMS/pgAdmin
/// convention instead, where bare F5/Play always runs the entire script. A
/// selection is now the only way to run fewer than all of them.
///
/// **A selection spanning several statements runs all of them, in order**
/// (`QueryExecutionService` stops at the first one that fails, per
/// database) — [splitSqlStatements] is applied to the selection itself
/// instead of treating it as one opaque blob, so a single-statement
/// selection still resolves to a list of exactly one.
///
/// Returns an empty list if there's nothing runnable.
List<String> resolveStatementsToRun(
    {required String fullText, String? selectedText}) {
  if (selectedText != null && selectedText.trim().isNotEmpty) {
    final trimmedSelection = selectedText.trim();
    final statements = splitSqlStatements(trimmedSelection);
    // Falls back to the raw trimmed selection if splitting somehow finds
    // nothing (shouldn't happen for non-blank text, but safer than
    // silently discarding a real selection) — same "run it as typed"
    // behavior this function always had for a selection before this
    // change.
    return statements.isEmpty
        ? [trimmedSelection]
        : statements.map((s) => s.text).toList();
  }

  return splitSqlStatements(fullText).map((s) => s.text).toList();
}
