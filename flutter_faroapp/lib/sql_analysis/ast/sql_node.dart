/// Base type for every AST node. Depth is intentionally shallow — only
/// structural pieces (statements, FROM/JOIN, table refs, CTEs, select-list
/// items) get real nodes; anything that's "an expression" (WHERE/HAVING/ON/
/// the right-hand side of SET) is an opaque token range instead of a real
/// expression tree — see `expression_stub.dart`'s doc comment for why that's
/// the scope call that makes v1 tractable.
// Not `sealed` — subclasses live across several files (statements.dart,
// clauses.dart, table_ref.dart, select_item.dart, expression_stub.dart),
// and Dart only allows a sealed class's subtypes within its own library
// (file). `Statement` and `TableRef` are each `sealed` *within their own
// file* instead, where exhaustive switching actually matters (dispatching
// on statement/table-ref kind) — see statements.dart/table_ref.dart.
abstract class SqlNode {
  const SqlNode({required this.start, required this.end});

  /// Offsets relative to the single statement's own text that was lexed and
  /// parsed — NOT the full multi-statement script. `analysis/
  /// sql_script_analyzer.dart` is the one place that shifts these to
  /// absolute script offsets (by adding the statement's own `SqlStatementSpan
  /// .start`, from `sql_statement_resolver.dart`), done exactly once so no
  /// other code has to reason about the distinction.
  final int start;
  final int end;
}
