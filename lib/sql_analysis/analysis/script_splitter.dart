/// Splits a (possibly multi-statement) block of SQL text into individual
/// statement spans — shared by `sql_script_analyzer.dart` (this module,
/// needs one span per statement to lex+parse independently) and
/// `sql_statement_resolver.dart` (features layer, needs the same split to
/// decide what "run everything"/"run the selection" actually runs).
///
/// Real bug fixed 2026-08-04 (AUDITORIA_CODIGO.md): this used to live only
/// in `lib/features/consulta/application/sql_statement_resolver.dart`, with
/// `sql_script_analyzer.dart` (part of the supposedly self-contained
/// `lib/sql_analysis/` module) importing it from there — an inverted
/// dependency, the module reaching *out* to the features layer instead of
/// the other way around. Moved here so the module stays a leaf: features
/// code depends on `sql_analysis`, never the reverse.
library;

/// One SQL statement found in a larger block of text, with its original
/// (untrimmed) character range so a cursor position can be matched against it.
class SqlStatementSpan {
  const SqlStatementSpan(
      {required this.start, required this.end, required this.text});

  /// Offsets into the original, untrimmed input.
  final int start;
  final int end;

  /// The statement's own text, trimmed.
  final String text;
}

// A new statement starts at a line beginning with one of these — used only
// when the input has no `;` at all (otherwise that stays the delimiter, to
// not change behavior for already-`;`-terminated scripts).
final _statementStartPattern = RegExp(
  r'^\s*(SELECT|INSERT|UPDATE|DELETE|WITH|CREATE|ALTER|DROP|TRUNCATE|MERGE|EXPLAIN)\b',
  caseSensitive: false,
);

List<SqlStatementSpan> splitSqlStatements(String fullText) {
  return fullText.contains(';')
      ? _splitBySemicolon(fullText)
      : _splitByStatementKeywordLines(fullText);
}

List<SqlStatementSpan> _splitBySemicolon(String fullText) {
  final spans = <SqlStatementSpan>[];
  var searchStart = 0;
  while (true) {
    final semicolon = _nextTopLevelSemicolon(fullText, searchStart);
    final chunkEnd = semicolon == -1 ? fullText.length : semicolon;
    _addTrimmedSpan(spans, fullText, searchStart, chunkEnd);
    if (semicolon == -1) break;
    searchStart = semicolon + 1;
  }
  return spans;
}

// Matches a dollar-quote delimiter (`$$`, `$tag$`) at the very start of the
// match — used to find both the opening delimiter of a PL/pgSQL function
// body and, later, its matching close. Tag rules mirror Postgres identifiers
// (must start with a letter/underscore, no digit-only or `$1`-style
// parameter references misdetected as quotes).
final _dollarTagPattern = RegExp(r'\$([A-Za-z_][A-Za-z0-9_]*)?\$');

/// Finds the next `;` in [text] starting at [from] that is not inside a
/// `'...'`/`"..."` string, a `$tag$...$tag$` dollar-quoted body (Postgres
/// function/procedure definitions), a `--` line comment, or a `/* */` block
/// comment. Returns -1 if none remain.
///
/// **Real bug, 2026-07-22:** a client's `CREATE FUNCTION ... AS $_$ ...
/// END; $_$;` failed with "unterminated dollar-quoted string" — the naive
/// `indexOf(';')` split the function body apart at every internal `;`
/// (`nRet := 0;`, `PERFORM ...;`, etc.), sending only a fragment of the
/// dollar-quoted string per "statement". This scanner tracks state instead
/// of treating every `;` as a delimiter.
int _nextTopLevelSemicolon(String text, int from) {
  var i = from;
  while (i < text.length) {
    final ch = text[i];
    if (ch == "'" || ch == '"') {
      i = _skipQuoted(text, i, ch);
      continue;
    }
    if (ch == r'$') {
      final tagMatch = _dollarTagPattern.matchAsPrefix(text, i);
      if (tagMatch != null) {
        final tag = tagMatch.group(0)!;
        final close = text.indexOf(tag, tagMatch.end);
        i = close == -1 ? text.length : close + tag.length;
        continue;
      }
    }
    if (ch == '-' && i + 1 < text.length && text[i + 1] == '-') {
      final newline = text.indexOf('\n', i + 2);
      i = newline == -1 ? text.length : newline + 1;
      continue;
    }
    if (ch == '/' && i + 1 < text.length && text[i + 1] == '*') {
      final end = text.indexOf('*/', i + 2);
      i = end == -1 ? text.length : end + 2;
      continue;
    }
    if (ch == ';') return i;
    i++;
  }
  return -1;
}

/// Starting at [start] (where `text[start]` is the opening quote char),
/// returns the index right after the matching closing quote — `''`/`""`
/// doubling is treated as an escaped literal quote, not a close, matching
/// standard SQL string-literal escaping.
int _skipQuoted(String text, int start, String quoteChar) {
  var i = start + 1;
  while (i < text.length) {
    if (text[i] == quoteChar) {
      if (i + 1 < text.length && text[i + 1] == quoteChar) {
        i += 2;
        continue;
      }
      return i + 1;
    }
    i++;
  }
  return text.length;
}

/// Real bug fixed 2026-08-03 (AUDITORIA_CODIGO.md): this used to scan
/// purely line-by-line (`fullText.split` in spirit, checking every `\n`
/// unconditionally) with no idea a `\n` could sit *inside* a string
/// literal, comment, or dollar-quoted body — its sibling
/// `_nextTopLevelSemicolon` already tracks that state, this one didn't.
/// Concretely: a script with no `;` at all (the only case this function
/// ever runs for) containing a multi-line string whose continuation line
/// happens to start with a watched keyword — `SELECT 'primera\nSELECT
/// esto sigue siendo el mismo string' AS texto` — got split into two
/// "statements" right through the middle of the literal. Fixed by folding
/// in the exact same quote/dollar-quote/comment-skipping scan
/// `_nextTopLevelSemicolon` uses, so a `\n` inside any of those spans is
/// never visited by the line-boundary check at all (the skip functions
/// jump `i` straight past it).
List<SqlStatementSpan> _splitByStatementKeywordLines(String fullText) {
  final boundaries = <int>[0];
  var i = 0;
  // Real bug fixed 2026-08-05 (client-reported): a leading comment (`--` or
  // `/* */`) immediately followed by `\nSELECT ...` used to get split off
  // into its own fake, comment-only "statement" — nothing real had been
  // seen yet in the chunk, so the comment itself had no statement to
  // attach to. `SqlGuard.assertReadOnly` then rejected that fake statement
  // as "not a SELECT" (correctly, from its own narrow view — a bare
  // comment isn't one), and since a script's statements stop running at
  // the first one that fails, the real SELECT right after the comment
  // never ran at all. Tracks whether any real (non-comment,
  // non-whitespace) text has been seen since the last statement boundary:
  // when a comment is the *first* thing in the current chunk, its
  // trailing newline is swallowed too, so the boundary check below never
  // fires between the comment and whatever real statement follows it —
  // keeping them one statement, same as pasting the comment inline would.
  // When real content already precedes the comment (two genuinely
  // separate statements with only a comment between them, no `;`), the
  // trailing newline is left alone so the boundary check still correctly
  // splits the second statement off on its own.
  var sawContentSinceBoundary = false;
  while (i < fullText.length) {
    final ch = fullText[i];
    if (ch == "'" || ch == '"') {
      i = _skipQuoted(fullText, i, ch);
      sawContentSinceBoundary = true;
      continue;
    }
    if (ch == r'$') {
      final tagMatch = _dollarTagPattern.matchAsPrefix(fullText, i);
      if (tagMatch != null) {
        final tag = tagMatch.group(0)!;
        final close = fullText.indexOf(tag, tagMatch.end);
        i = close == -1 ? fullText.length : close + tag.length;
        sawContentSinceBoundary = true;
        continue;
      }
    }
    if (ch == '-' && i + 1 < fullText.length && fullText[i + 1] == '-') {
      final newline = fullText.indexOf('\n', i + 2);
      i = newline == -1 ? fullText.length : newline;
      if (!sawContentSinceBoundary && i < fullText.length) i++;
      continue;
    }
    if (ch == '/' && i + 1 < fullText.length && fullText[i + 1] == '*') {
      final end = fullText.indexOf('*/', i + 2);
      i = end == -1 ? fullText.length : end + 2;
      if (!sawContentSinceBoundary && i < fullText.length && fullText[i] == '\n') {
        i++;
      }
      continue;
    }
    if (ch == '\n') {
      final lineStart = i + 1;
      if (lineStart < fullText.length) {
        // A short bounded probe (not the whole rest of the text) is
        // plenty — the pattern only ever needs to see leading whitespace
        // plus its longest keyword, capped so this stays O(1) per
        // newline instead of re-scanning the remainder of a large script
        // at every line.
        final probeEnd = (lineStart + 32).clamp(0, fullText.length);
        if (_statementStartPattern
            .hasMatch(fullText.substring(lineStart, probeEnd))) {
          boundaries.add(lineStart);
          sawContentSinceBoundary = false;
        }
      }
      i = lineStart;
      continue;
    }
    if (!_isTrimmable(ch)) sawContentSinceBoundary = true;
    i++;
  }
  boundaries.add(fullText.length);

  final spans = <SqlStatementSpan>[];
  for (var i = 0; i < boundaries.length - 1; i++) {
    _addTrimmedSpan(spans, fullText, boundaries[i], boundaries[i + 1]);
  }
  return spans;
}

void _addTrimmedSpan(
    List<SqlStatementSpan> spans, String fullText, int rawStart, int rawEnd) {
  var start = rawStart;
  var end = rawEnd;
  while (start < end && _isTrimmable(fullText[start])) {
    start++;
  }
  while (end > start && _isTrimmable(fullText[end - 1])) {
    end--;
  }
  if (start == end) return;
  spans.add(SqlStatementSpan(
      start: start, end: end, text: fullText.substring(start, end)));
}

bool _isTrimmable(String ch) =>
    ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r';
