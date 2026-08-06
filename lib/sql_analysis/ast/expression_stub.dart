import '../lexer/sql_token.dart';
import 'sql_node.dart';

/// An opaque, unparsed expression — WHERE/HAVING/ON conditions, GROUP BY/
/// ORDER BY items, LIMIT/OFFSET values, the right-hand side of a SET item.
/// v1 deliberately has no real expression grammar (precedence, function
/// arguments, CASE/WHEN as nodes, casts) — that's the single biggest scope
/// cut that keeps a hand-written parser tractable; see the module's plan
/// doc for the full reasoning. [tokens] is produced by a paren-balanced
/// scan (`SqlParser._scanExpressionStub`) that stops at the next clause
/// boundary or an unbalanced `)`, generalizing the same trick
/// `sql_autocomplete.dart`'s `isInsideColumnList` already uses.
class ExpressionStub extends SqlNode {
  const ExpressionStub(this.tokens, {required super.start, required super.end});

  final List<SqlToken> tokens;

  /// Reconstructs the original source slice — joins each token's own
  /// [SqlToken.text] with a single space, which does not preserve original
  /// whitespace exactly (this is a diagnostic/debugging convenience, not
  /// meant to round-trip the source).
  String get sourceText => tokens.map((t) => t.text).join(' ');
}
