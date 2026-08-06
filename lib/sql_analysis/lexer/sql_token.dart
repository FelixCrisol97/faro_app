/// See `lib/sql_analysis/analysis/sql_script_analyzer.dart` for the module's
/// single external entry point — everything under `lexer/`/`ast/`/`parser/`
/// is an internal detail, not meant to be imported directly outside
/// `lib/sql_analysis/`.
enum SqlTokenType {
  /// An identifier whose uppercased text matched `keywords.dart`'s canonical
  /// set — reclassified from [identifier] by the lexer, not a separate
  /// per-word enum variant (there is no `SqlTokenType.select`). The parser
  /// still compares by text (`token.text.toUpperCase() == 'FROM'`); this
  /// type exists so a consumer (e.g. a future syntax highlighter migration)
  /// can ask "is this a keyword" without knowing which one.
  keyword,

  /// Unquoted identifier: `pedidos`, `p`, `columna_1`.
  identifier,

  /// `"Pedidos"` — case-sensitive, quotes included in [SqlToken.text].
  quotedIdentifier,

  /// `123`, `123.45`, `.5`, `1e10`.
  number,

  /// `'texto'`, with `''` and `\'` both treated as an escaped embedded quote
  /// (the latter covers Postgres's `E'...'` escape-string syntax without
  /// needing a separate token type for it — see `sql_lexer.dart`).
  string,

  /// `$$...$$` or `$tag$...$tag$` — a PL/pgSQL dollar-quoted body.
  dollarString,

  /// `$1`, `$2` — a positional parameter reference, never confused with
  /// [dollarString] (a dollar-quote tag can't start with a digit).
  parameter,

  /// `=`, `<>`, `!=`, `<=`, `>=`, `||`, `::`, `->`, `->>`, and similar.
  operatorToken,

  /// `(`, `)`, `,`, `.`, `;`, `[`, `]`.
  punctuation,

  lineComment,
  blockComment,
  whitespace,

  /// A character (or short run) the lexer couldn't classify — emitted
  /// instead of throwing, per this module's core constraint: the input is
  /// usually SQL mid-edit, never guaranteed valid. See [SqlToken.missing]
  /// for the parser's own synthetic use of this type.
  unknown,

  /// Synthetic end-of-input marker — always the last token `lexSql` returns,
  /// width zero, so the parser has a token to point `current` at without a
  /// null check at the end of the stream.
  eof,
}

/// One lexical token — a slice of the original source, never normalized
/// ([text] keeps original case/whitespace/quotes), plus its half-open
/// `[start, end)` character-offset range.
class SqlToken {
  const SqlToken(this.type, this.text, this.start, this.end);

  final SqlTokenType type;
  final String text;
  final int start;
  final int end;

  /// A width-zero synthetic token the parser inserts at [offset] when an
  /// expected token is missing from the input (e.g. `FROM` with nothing
  /// after it) — see `parser/sql_parser.dart`'s `expect`. Never produced by
  /// [lexSql] itself, only by the parser's error-recovery path.
  factory SqlToken.missing(int offset) =>
      SqlToken(SqlTokenType.unknown, '', offset, offset);

  bool get isEof => type == SqlTokenType.eof;

  /// Trivia the parser skips when building its significant-token view —
  /// kept out of the AST's own node ranges but still walkable from the raw
  /// token list for anything that wants it later (e.g. showing a comment on
  /// hover) without complicating the grammar itself.
  bool get isTrivia =>
      type == SqlTokenType.whitespace ||
      type == SqlTokenType.lineComment ||
      type == SqlTokenType.blockComment;

  @override
  String toString() {
    final preview = text.length > 24 ? '${text.substring(0, 24)}…' : text;
    return 'SqlToken($type, ${preview.isEmpty ? '<empty>' : "'$preview'"}, $start-$end)';
  }
}
