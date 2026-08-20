import '../ast/statements.dart';
import 'diagnostic.dart';

/// Result of parsing one statement — [statement] is **always** non-null,
/// even for pathological input (worst case: a zero-length
/// [UnknownStatement]). `SqlParser.parse` never throws; a caller never
/// needs a try/catch around it.
class ParseResult {
  const ParseResult(this.statement, this.diagnostics);

  final Statement statement;
  final List<SqlDiagnostic> diagnostics;
}
