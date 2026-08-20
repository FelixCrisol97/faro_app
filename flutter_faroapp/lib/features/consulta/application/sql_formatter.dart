import 'sql_tokenizer.dart';

/// "Formatear": uppercases major clause keywords and puts each on its own
/// line, indenting nested content by parenthesis depth (subqueries/CTEs).
///
/// Unlike the naive keyword-substitution version this replaced, the input
/// is tokenized first (see `sql_tokenizer.dart`, shared with
/// `sql_syntax_highlighter.dart`) so string literals and comments are never
/// touched even if their contents happen to look like a keyword (e.g. a
/// WHERE clause matching `'from the archive'`, or a comment mentioning
/// `select`) — that was the two failure modes explicitly called out
/// before. This is still not a full SQL grammar parser (no
/// actively-maintained package targeting Postgres/SQL Server — as opposed
/// to SQLite-only `sqlparser`, which also has no formatting output —
/// exists for Dart), so unusual syntax may still format imperfectly, but
/// it no longer corrupts strings, comments, or subquery structure the way
/// the old version did.
String formatSql(String input) {
  final tokens = tokenizeSql(input);
  final state = _FormatterState();

  for (final token in tokens) {
    if (token.kind != SqlTokenKind.code) {
      state.writeVerbatim(token.text);
      continue;
    }
    var lastEnd = 0;
    for (final match in _clauseKeywordPattern.allMatches(token.text)) {
      state.writeCode(token.text.substring(lastEnd, match.start));
      state.newLine();
      state.writeVerbatim(match.group(0)!.toUpperCase());
      lastEnd = match.end;
    }
    state.writeCode(token.text.substring(lastEnd));
  }

  final buffer = StringBuffer();
  var wroteLine = false;
  for (final line in state.lines) {
    final text = line.text;
    if (text.isEmpty) continue;
    if (wroteLine) buffer.write('\n');
    buffer.write('  ' * line.depth);
    buffer.write(text);
    wroteLine = true;
  }
  return buffer.toString();
}

// Longer variants listed before the single-word keywords they contain
// (`LEFT JOIN` before `JOIN`, `UNION ALL` before `UNION`) so the regex
// consumes the whole phrase as one clause break instead of splitting it.
const _clauseKeywords = [
  'GROUP BY',
  'ORDER BY',
  'LEFT JOIN',
  'RIGHT JOIN',
  'FULL JOIN',
  'INNER JOIN',
  'CROSS JOIN',
  'UNION ALL',
  'UNION',
  'SELECT',
  'FROM',
  'WHERE',
  'HAVING',
  'JOIN',
  'LIMIT',
  'OFFSET',
  'WITH',
];

final _clauseKeywordPattern = RegExp(
  r'\b(' +
      _clauseKeywords.map((k) => k.replaceAll(' ', r'\s+')).join('|') +
      r')\b',
  caseSensitive: false,
);

class _FormatterState {
  final List<_Line> lines = [_Line(0)];
  int _depth = 0;

  _Line get _current => lines.last;

  void newLine() => lines.add(_Line(_depth));

  /// Writes ordinary code text: collapses runs of whitespace to a single
  /// space (matching the old formatter's whitespace-normalizing behavior)
  /// and tracks parenthesis depth for subquery/CTE indentation.
  void writeCode(String chunk) {
    for (var i = 0; i < chunk.length; i++) {
      final ch = chunk[i];
      if (ch == '(') {
        _current.write(ch);
        _depth++;
      } else if (ch == ')') {
        _depth = _depth > 0 ? _depth - 1 : 0;
        _current.write(ch);
      } else if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') {
        _current.writeSpace();
      } else {
        _current.write(ch);
      }
    }
  }

  /// Writes [text] exactly as-is — used for the uppercased keyword itself
  /// and for string/comment tokens, which must never be whitespace-collapsed.
  void writeVerbatim(String text) => _current.writeRaw(text);
}

class _Line {
  _Line(this.depth);
  final int depth;
  final StringBuffer _buffer = StringBuffer();
  bool _lastWasSpace = true;

  void write(String ch) {
    _buffer.write(ch);
    _lastWasSpace = false;
  }

  void writeSpace() {
    if (!_lastWasSpace) _buffer.write(' ');
    _lastWasSpace = true;
  }

  void writeRaw(String text) {
    _buffer.write(text);
    _lastWasSpace = false;
  }

  String get text => _buffer.toString().trim();
}
