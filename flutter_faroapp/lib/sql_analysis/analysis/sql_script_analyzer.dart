import '../ast/statements.dart';
import '../lexer/sql_lexer.dart';
import '../parser/diagnostic.dart';
import '../parser/sql_parser.dart';
import 'script_offset.dart';
import 'script_splitter.dart';

/// One statement out of a (possibly multi-statement) script, with its own
/// parsed [statement]/[diagnostics] plus where it sits in the *original*
/// script text.
///
/// **Coordinate spaces, the one thing every caller needs to get right:**
/// [diagnostics] are already shifted to absolute script offsets (a flat
/// list, cheap to shift, and diagnostics exist specifically to be drawn
/// directly in the editor — no reason to make every consumer redo that
/// arithmetic). [statement]'s own node offsets are **not** shifted —
/// they're relative to this one statement's text, exactly as `SqlParser`
/// produced them (see `ast/sql_node.dart`'s doc comment). Re-deriving a
/// full shifted AST would mean cloning every node type recursively for a
/// purely mechanical `+ scriptStart` translation; instead, use
/// [toAbsoluteOffset]/[toRelativeOffset] at the one or two places that
/// actually need to cross between the two spaces (e.g. converting an
/// editor cursor position before calling `scope_resolver.dart`'s
/// `resolveScopeAt`, which operates in the statement's own relative
/// space).
class AnalyzedStatement {
  const AnalyzedStatement({
    required this.statement,
    required this.diagnostics,
    required this.scriptStart,
    required this.scriptEnd,
  });

  final Statement statement;

  /// Already absolute — see the class doc comment. [SqlDiagnostic.start]/
  /// [SqlDiagnostic.end] stay plain `int` (that type is shared with
  /// `ParseResult`'s statement-relative diagnostics, produced before this
  /// class shifts them) — [AbsoluteOffset]/[RelativeOffset] exist for this
  /// class's own boundary specifically, not to retype every offset in the
  /// module.
  final List<SqlDiagnostic> diagnostics;

  /// This statement's own span within the full script text.
  final AbsoluteOffset scriptStart;
  final AbsoluteOffset scriptEnd;

  /// A statement-relative offset (as found on any node under [statement])
  /// → absolute script offset.
  AbsoluteOffset toAbsoluteOffset(RelativeOffset relativeOffset) =>
      AbsoluteOffset(relativeOffset.value + scriptStart.value);

  /// Absolute script offset → statement-relative — the one conversion
  /// needed before passing an editor cursor position into
  /// `scope_resolver.dart`'s `resolveScopeAt(statement, ...)`.
  RelativeOffset toRelativeOffset(AbsoluteOffset absoluteOffset) =>
      RelativeOffset(absoluteOffset.value - scriptStart.value);
}

/// Every statement found in [fullText], each independently lexed, parsed,
/// and diagnosed. Never throws — see `SqlParser.parse`'s contract, which
/// this only ever calls once per statement.
class SqlScriptAnalysis {
  const SqlScriptAnalysis(this.statements);

  final List<AnalyzedStatement> statements;

  List<SqlDiagnostic> get allDiagnostics =>
      [for (final s in statements) ...s.diagnostics];

  /// The statement whose script span contains [absoluteOffset], if any —
  /// the usual way a caller (the editor, keyed off the cursor position)
  /// picks which statement to run `scope_resolver.dart`'s `resolveScopeAt`
  /// against.
  AnalyzedStatement? statementAt(AbsoluteOffset absoluteOffset) {
    for (final s in statements) {
      if (absoluteOffset.value >= s.scriptStart.value &&
          absoluteOffset.value <= s.scriptEnd.value) {
        return s;
      }
    }
    return null;
  }
}

/// The module's single external entry point — everything under `lexer/`,
/// `ast/`, `parser/` is an internal implementation detail not meant to be
/// imported directly from outside `lib/sql_analysis/`.
///
/// Reuses `sql_statement_resolver.dart`'s `splitSqlStatements` for how a
/// script divides into statements (already handles dollar-quoting/quoted
/// strings/comments correctly, with its own dedicated tests) rather than
/// re-solving that problem a second, different way — this module only
/// needs to lex+parse *one* statement's text at a time.
SqlScriptAnalysis analyzeSqlScript(String fullText) {
  final spans = splitSqlStatements(fullText);
  final analyzed = <AnalyzedStatement>[
    for (final span in spans) _analyzeOne(span),
  ];
  return SqlScriptAnalysis(analyzed);
}

AnalyzedStatement _analyzeOne(SqlStatementSpan span) {
  final tokens = lexSql(span.text);
  final result = SqlParser.parse(tokens);
  final absoluteDiagnostics = [
    for (final d in result.diagnostics)
      SqlDiagnostic(
        severity: d.severity,
        message: d.message,
        start: d.start + span.start,
        end: d.end + span.start,
        code: d.code,
      ),
  ];
  return AnalyzedStatement(
    statement: result.statement,
    diagnostics: absoluteDiagnostics,
    scriptStart: AbsoluteOffset(span.start),
    scriptEnd: AbsoluteOffset(span.end),
  );
}
