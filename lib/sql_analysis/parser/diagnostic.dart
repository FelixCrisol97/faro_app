enum DiagnosticSeverity { error, warning, info }

/// A syntactic problem `SqlParser` found while parsing one statement —
/// never anything semantic (the parser never knows whether a table/column
/// actually exists; that would require a live catalog query, a separate
/// concern layered on top of `analysis/scope_resolver.dart` later, not
/// this module's job).
///
/// [start]/[end] are a half-open range relative to the statement's own
/// text (same convention as every `SqlNode` — see its doc comment). A
/// "missing token" diagnostic (e.g. `FROM` with nothing after it) is
/// width-zero, anchored right after the last token the parser actually
/// consumed — deliberately not swallowing whatever real token comes next,
/// since that token likely belongs to the next clause instead.
class SqlDiagnostic {
  const SqlDiagnostic({
    required this.severity,
    required this.message,
    required this.start,
    required this.end,
    required this.code,
  });

  final DiagnosticSeverity severity;

  /// User-facing, in Spanish (matches this app's other user-visible error
  /// text, e.g. `ReadOnlyViolationException`).
  final String message;

  final int start;
  final int end;

  /// A stable identifier (e.g. `'expected-table-name'`) — for tests and any
  /// future per-code suppression, never shown to the user directly.
  final String code;

  @override
  String toString() => '$code@$start-$end: $message';
}
