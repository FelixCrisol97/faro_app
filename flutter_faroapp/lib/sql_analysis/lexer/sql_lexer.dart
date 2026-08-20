import 'keywords.dart';
import 'sql_token.dart';

/// Tokenizes [source] into a flat, complete token stream — every character
/// belongs to exactly one token, including whitespace/comments (trivia);
/// the parser is what skips trivia when it builds its significant-token
/// view, not the lexer (see [SqlToken.isTrivia]). Always ends with one
/// [SqlTokenType.eof] token at `source.length`.
///
/// **Never throws, regardless of how malformed [source] is** — this module
/// exists specifically to run against SQL mid-edit, which is routinely
/// incomplete or invalid. An unterminated string/quoted-identifier/
/// block-comment/dollar-quoted body runs to end-of-input and becomes one
/// token instead of raising; an unrecognized character becomes a
/// width-one [SqlTokenType.unknown] token and scanning continues right
/// after it. Deterministic and pure — same input always produces the same
/// token list, no external state.
List<SqlToken> lexSql(String source) {
  final tokens = <SqlToken>[];
  final length = source.length;
  var i = 0;

  while (i < length) {
    final start = i;
    final ch = source[i];

    if (_isWhitespace(ch)) {
      i = _scanWhitespace(source, i);
      tokens.add(SqlToken(SqlTokenType.whitespace, source.substring(start, i), start, i));
      continue;
    }

    if (ch == '-' && i + 1 < length && source[i + 1] == '-') {
      i = _scanLineComment(source, i);
      tokens.add(SqlToken(SqlTokenType.lineComment, source.substring(start, i), start, i));
      continue;
    }

    if (ch == '/' && i + 1 < length && source[i + 1] == '*') {
      i = _scanBlockComment(source, i);
      tokens.add(SqlToken(SqlTokenType.blockComment, source.substring(start, i), start, i));
      continue;
    }

    if (ch == "'") {
      i = _scanString(source, i);
      tokens.add(SqlToken(SqlTokenType.string, source.substring(start, i), start, i));
      continue;
    }

    if (ch == '"') {
      i = _scanQuotedIdentifier(source, i);
      tokens.add(SqlToken(SqlTokenType.quotedIdentifier, source.substring(start, i), start, i));
      continue;
    }

    if (ch == r'$') {
      final result = _scanDollar(source, i);
      tokens.add(result);
      i = result.end;
      continue;
    }

    if (_isDigit(ch) || (ch == '.' && i + 1 < length && _isDigit(source[i + 1]))) {
      i = _scanNumber(source, i);
      tokens.add(SqlToken(SqlTokenType.number, source.substring(start, i), start, i));
      continue;
    }

    if (_isIdentifierStart(ch)) {
      i = _scanIdentifier(source, i);
      final text = source.substring(start, i);
      final type = kSqlKeywords.contains(text.toUpperCase())
          ? SqlTokenType.keyword
          : SqlTokenType.identifier;
      tokens.add(SqlToken(type, text, start, i));
      continue;
    }

    if (_punctuationChars.contains(ch)) {
      i++;
      tokens.add(SqlToken(SqlTokenType.punctuation, ch, start, i));
      continue;
    }

    if (_operatorChars.contains(ch)) {
      i = _scanOperator(source, i);
      tokens.add(SqlToken(SqlTokenType.operatorToken, source.substring(start, i), start, i));
      continue;
    }

    // Unrecognized character (e.g. a stray non-ASCII symbol) — emitted as
    // its own token instead of thrown, per this function's core contract.
    i++;
    tokens.add(SqlToken(SqlTokenType.unknown, ch, start, i));
  }

  tokens.add(SqlToken(SqlTokenType.eof, '', length, length));
  return tokens;
}

bool _isWhitespace(String ch) =>
    ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r';

int _scanWhitespace(String s, int i) {
  while (i < s.length && _isWhitespace(s[i])) {
    i++;
  }
  return i;
}

// Stops right before the newline (matching `sql_statement_resolver.dart`'s
// own line-comment scan) — the newline becomes its own whitespace token on
// the next iteration rather than being swallowed into the comment.
int _scanLineComment(String s, int i) {
  final newline = s.indexOf('\n', i + 2);
  return newline == -1 ? s.length : newline;
}

int _scanBlockComment(String s, int i) {
  final end = s.indexOf('*/', i + 2);
  return end == -1 ? s.length : end + 2;
}

// Handles both `''` doubling (standard SQL string-literal escaping) and a
// backslash before any character (covers Postgres's `E'...'` escape-string
// syntax) — no separate token type for `E'...'`, the `E` itself just lexes
// as an ordinary identifier immediately before the string.
int _scanString(String s, int i) {
  i++; // opening '
  while (i < s.length) {
    final ch = s[i];
    if (ch == r'\' && i + 1 < s.length) {
      i += 2;
      continue;
    }
    if (ch == "'") {
      if (i + 1 < s.length && s[i + 1] == "'") {
        i += 2;
        continue;
      }
      return i + 1;
    }
    i++;
  }
  return s.length; // unterminated — runs to EOF, never throws
}

// `""` doubling only — unlike string literals, quoted identifiers don't
// use backslash escaping in Postgres.
int _scanQuotedIdentifier(String s, int i) {
  i++; // opening "
  while (i < s.length) {
    if (s[i] == '"') {
      if (i + 1 < s.length && s[i + 1] == '"') {
        i += 2;
        continue;
      }
      return i + 1;
    }
    i++;
  }
  return s.length;
}

// Same tag rule already proven in `sql_statement_resolver.dart`
// (`_dollarTagPattern`): a tag can't start with a digit, so `$1` is never
// misread as the start of a `$tag$...$tag$` body.
final _dollarTagPattern = RegExp(r'\$([A-Za-z_][A-Za-z0-9_]*)?\$');
final _digitsPattern = RegExp(r'\d+');

SqlToken _scanDollar(String s, int start) {
  final tagMatch = _dollarTagPattern.matchAsPrefix(s, start);
  if (tagMatch != null) {
    final tag = tagMatch.group(0)!;
    final closeIndex = s.indexOf(tag, tagMatch.end);
    final end = closeIndex == -1 ? s.length : closeIndex + tag.length;
    return SqlToken(SqlTokenType.dollarString, s.substring(start, end), start, end);
  }
  final paramMatch = _digitsPattern.matchAsPrefix(s, start + 1);
  if (paramMatch != null) {
    return SqlToken(
        SqlTokenType.parameter, s.substring(start, paramMatch.end), start, paramMatch.end);
  }
  // A lone '$' matching neither shape — emitted as unknown rather than
  // guessed at.
  return SqlToken(SqlTokenType.unknown, r'$', start, start + 1);
}

bool _isDigit(String ch) {
  final c = ch.codeUnitAt(0);
  return c >= 0x30 && c <= 0x39;
}

// Covers integers, decimals (`123.45`), leading-dot decimals (`.5`), and
// exponents (`1e10`, `1.5e-10`) — an `e`/`E` not actually followed by a
// (optionally signed) digit is left untouched for the next token instead of
// being swallowed, so `1e` without a real exponent doesn't eat a following
// identifier that happens to start with `e`.
int _scanNumber(String s, int i) {
  if (s[i] == '.') {
    i++;
  } else {
    while (i < s.length && _isDigit(s[i])) {
      i++;
    }
    if (i < s.length && s[i] == '.') {
      i++;
    }
  }
  while (i < s.length && _isDigit(s[i])) {
    i++;
  }
  if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
    var j = i + 1;
    if (j < s.length && (s[j] == '+' || s[j] == '-')) {
      j++;
    }
    if (j < s.length && _isDigit(s[j])) {
      while (j < s.length && _isDigit(s[j])) {
        j++;
      }
      i = j;
    }
  }
  return i;
}

bool _isIdentifierStart(String ch) {
  final c = ch.codeUnitAt(0);
  return (c >= 0x41 && c <= 0x5A) || (c >= 0x61 && c <= 0x7A) || c == 0x5F;
}

bool _isIdentifierPart(String ch) => _isIdentifierStart(ch) || _isDigit(ch);

int _scanIdentifier(String s, int i) {
  while (i < s.length && _isIdentifierPart(s[i])) {
    i++;
  }
  return i;
}

const _punctuationChars = '(),.;[]';

// Tried longest-first so e.g. `->>` never lexes as `->` + `>`. Operators are
// opaque content wherever they appear in v1 (inside an `ExpressionStub`) —
// this only needs to produce *some* stable token boundary, not resolve
// operator semantics.
const _threeCharOperators = ['->>', '#>>', '!~*'];
const _twoCharOperators = [
  '<=', '>=', '<>', '!=', '||', '::', '->', '#>', '@>', '<@', '~*', '!~'
];
const _operatorChars = '=<>+-*/%^&|~!@#?:';

int _scanOperator(String s, int i) {
  for (final op in _threeCharOperators) {
    if (_matchesAt(s, i, op)) return i + op.length;
  }
  for (final op in _twoCharOperators) {
    if (_matchesAt(s, i, op)) return i + op.length;
  }
  return i + 1;
}

bool _matchesAt(String s, int i, String op) =>
    i + op.length <= s.length && s.substring(i, i + op.length) == op;
