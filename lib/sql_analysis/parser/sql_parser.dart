import '../ast/clauses.dart';
import '../ast/expression_stub.dart';
import '../ast/select_item.dart';
import '../ast/statements.dart';
import '../ast/table_ref.dart';
import '../lexer/sql_token.dart';
import 'diagnostic.dart';
import 'parse_result.dart';

/// Hand-written recursive descent, no parser-combinator/generator
/// dependency — the error recovery this needs is ad-hoc by construction
/// ("if FROM has no table name, keep going with an empty one and try
/// WHERE next"), easier to write function-by-function than to force onto
/// generic backtracking. Same idiom `sql_tokenizer.dart`/
/// `sql_statement_resolver.dart` already use (hand-written scanners), and
/// matches `sql_formatter.dart`'s own note that no maintained Postgres/SQL
/// Server parser package exists for Dart.
///
/// **Core contract, non-negotiable — this parses SQL that's routinely
/// mid-edit:** [parse] never throws and never hangs. Every `_parseX`
/// function that loops on a delimiter (comma, JOIN keyword) only continues
/// the loop after actually consuming that delimiter, so a loop can't spin
/// without making progress; [_expect]-style helpers never consume a token
/// that doesn't match (see [_expectKeyword]'s doc comment for why that
/// specific detail matters), so a missing token becomes one diagnostic
/// instead of corrupting whatever comes next.
class SqlParser {
  SqlParser._(this._tokens);

  /// Significant tokens only — [SqlToken.isTrivia] ones are filtered out
  /// before parsing even starts, so every `_parseX` function only ever
  /// looks at meaningful tokens. Always ends with one [SqlTokenType.eof].
  final List<SqlToken> _tokens;
  int _pos = 0;
  final List<SqlDiagnostic> _diagnostics = [];

  // How many `(...)`-nested subquery/CTE bodies deep the parser currently
  // is — bounds recursion so a pathological paste (thousands of nested
  // parens) can't blow the call stack. Past this depth, a parenthesized
  // body is treated as opaque (balanced-paren skip, not recursively
  // parsed) instead of failing outright.
  int _subqueryDepth = 0;
  static const _maxSubqueryDepth = 64;

  static ParseResult parse(List<SqlToken> allTokens) {
    final significant = allTokens.where((t) => !t.isTrivia).toList();
    if (significant.isEmpty) {
      return const ParseResult(UnknownStatement(start: 0, end: 0), []);
    }
    final parser = SqlParser._(significant);
    final statement = parser._parseStatement();
    // Real bug fixed 2026-08-04 (AUDITORIA_CODIGO.md): nothing used to check
    // whether the statement parser actually reached the end of input.
    // Every `_parseX` here is built to recover from a missing/unexpected
    // token by leaving it right where it is (see `_expectKeyword`'s doc
    // comment) rather than throwing — a sound strategy for one malformed
    // clause, but it means a bug that stops a `_parseX` short (e.g. the
    // implicit-alias gap this same session fixed in
    // `_parseSimpleTableTarget`, which used to leave `p WHERE id = 1`
    // entirely unconsumed after `DELETE FROM pedidos`) silently returned a
    // statement missing a whole tail of real SQL, with zero diagnostic
    // pointing at it. This is the safety net: whatever [_pos] didn't reach
    // gets flagged, independent of which specific parsing gap caused it.
    if (!parser._current.isEof) {
      parser._addDiagnostic(
        code: 'unconsumed-tokens',
        message:
            'No se reconoció el resto de la instrucción — puede haber una sintaxis no soportada.',
      );
    }
    return ParseResult(statement, parser._diagnostics);
  }

  // --- Token stream primitives ----------------------------------------

  SqlToken get _current => _tokens[_pos];

  SqlToken _peek(int offset) {
    final idx = _pos + offset;
    return idx < _tokens.length ? _tokens[idx] : _tokens.last; // last is eof
  }

  /// The end offset of the last token actually consumed — where a
  /// "missing token" diagnostic gets anchored (width zero), and where a
  /// synthetic node's own range starts/ends when nothing real was found.
  int get _lastConsumedEnd => _pos > 0 ? _tokens[_pos - 1].end : 0;

  void _advance() {
    if (_pos < _tokens.length - 1) _pos++; // never advance past the eof slot
  }

  bool _isKeyword(String kw) =>
      _current.type == SqlTokenType.keyword &&
      _current.text.toUpperCase() == kw;

  bool _isPunct(String p) =>
      _current.type == SqlTokenType.punctuation && _current.text == p;

  bool _isOperator(String op) =>
      _current.type == SqlTokenType.operatorToken && _current.text == op;

  /// Accepts an unquoted identifier, a quoted identifier, or (deliberately
  /// lenient) a keyword-classified token that isn't [_structuralKeywords]
  /// — Postgres has a large "unreserved keyword" set usable as ordinary
  /// identifiers (`type`, `date`, `key`, ...), and this parser doesn't
  /// model the full reserved-vs-unreserved distinction. Given this
  /// module's explicit product-feel bias ("false negatives are safer than
  /// false positives" — under-reporting beats annoying red squiggles while
  /// someone's mid-type), accepting keyword-shaped names here trades a
  /// small amount of missed-error precision for meaningfully fewer bogus
  /// diagnostics on ordinary schemas.
  ///
  /// **[_structuralKeywords] is the one hard exclusion, and it's not
  /// optional:** without it, `SELECT a FROM WHERE x = 1` (table name
  /// missing) would happily consume `WHERE` itself as the table name with
  /// no diagnostic at all, then have no `WHERE` left to parse the actual
  /// condition — exactly the "wrong token consumed, cascading nonsense"
  /// failure mode `_expectKeyword`'s doc comment warns about, just via a
  /// different path ([_expectName] instead of a plain keyword mismatch).
  bool _isNameToken(SqlToken t) {
    if (t.type == SqlTokenType.identifier || t.type == SqlTokenType.quotedIdentifier) {
      return true;
    }
    return t.type == SqlTokenType.keyword &&
        !_structuralKeywords.contains(t.text.toUpperCase());
  }

  /// Every keyword this parser's grammar itself checks for structurally
  /// (`_isKeyword`/`_matchKeyword`/`_expectKeyword` call sites) — see
  /// [_isNameToken]'s doc comment for why these can never double as a name.
  static const _structuralKeywords = {
    'SELECT', 'WITH', 'RECURSIVE', 'INSERT', 'INTO', 'VALUES', 'UPDATE',
    'SET', 'DELETE', 'FROM', 'WHERE', 'GROUP', 'BY', 'HAVING', 'ORDER',
    'LIMIT', 'OFFSET', 'JOIN', 'INNER', 'LEFT', 'RIGHT', 'FULL', 'CROSS',
    'OUTER', 'ON', 'USING', 'AS', 'UNION', 'INTERSECT', 'EXCEPT', 'ALL',
    'DISTINCT', 'ASC', 'DESC', 'NULLS', 'FIRST', 'LAST', 'RETURNING',
    'DEFAULT',
  };

  String _nameText(SqlToken t) {
    if (t.type == SqlTokenType.quotedIdentifier && t.text.length >= 2) {
      return t.text.substring(1, t.text.length - 1).replaceAll('""', '"');
    }
    return t.text;
  }

  SqlToken? _matchKeyword(String kw) {
    if (_isKeyword(kw)) {
      final t = _current;
      _advance();
      return t;
    }
    return null;
  }

  SqlToken? _matchPunct(String p) {
    if (_isPunct(p)) {
      final t = _current;
      _advance();
      return t;
    }
    return null;
  }

  /// **The single most important primitive in this file.** On success,
  /// behaves like [_matchKeyword] but records a diagnostic on failure — and
  /// critically, on failure it does **not** consume [_current]. Consuming
  /// the wrong token here (e.g. treating `WHERE` as the missing table name
  /// after `FROM`) is exactly the kind of single error that used to cascade
  /// into a wall of nonsense diagnostics in naive recovery schemes; leaving
  /// it untouched means the caller one level up still sees it and parses it
  /// normally as the next real clause.
  SqlToken _expectKeyword(String kw,
      {required String code, required String message}) {
    final matched = _matchKeyword(kw);
    if (matched != null) return matched;
    _addDiagnostic(code: code, message: message);
    return SqlToken.missing(_lastConsumedEnd);
  }

  SqlToken _expectPunct(String p,
      {required String code, required String message}) {
    final matched = _matchPunct(p);
    if (matched != null) return matched;
    _addDiagnostic(code: code, message: message);
    return SqlToken.missing(_lastConsumedEnd);
  }

  SqlToken _expectOperator(String op,
      {required String code, required String message}) {
    if (_isOperator(op)) {
      final t = _current;
      _advance();
      return t;
    }
    _addDiagnostic(code: code, message: message);
    return SqlToken.missing(_lastConsumedEnd);
  }

  /// Same non-consuming-on-failure contract as [_expectKeyword], for a
  /// name position (table/column/alias/CTE name).
  SqlToken _expectName({required String code, required String message}) {
    if (_isNameToken(_current)) {
      final t = _current;
      _advance();
      return SqlToken(SqlTokenType.identifier, _nameText(t), t.start, t.end);
    }
    _addDiagnostic(code: code, message: message);
    return SqlToken.missing(_lastConsumedEnd);
  }

  void _addDiagnostic({required String code, required String message}) {
    _diagnostics.add(SqlDiagnostic(
      severity: DiagnosticSeverity.error,
      message: message,
      start: _lastConsumedEnd,
      end: _lastConsumedEnd,
      code: code,
    ));
  }

  // --- Shared boundary keyword sets ------------------------------------

  static const _clauseKeywords = {
    'FROM', 'WHERE', 'GROUP', 'HAVING', 'ORDER', 'LIMIT', 'OFFSET',
    'UNION', 'INTERSECT', 'EXCEPT',
  };
  static const _joinKeywords = {'JOIN', 'INNER', 'LEFT', 'RIGHT', 'FULL', 'CROSS'};
  static const _orderByModifierKeywords = {'ASC', 'DESC', 'NULLS', 'FIRST', 'LAST'};

  /// The shared building block behind every opaque [ExpressionStub] scan
  /// (WHERE/HAVING/ON, GROUP BY/ORDER BY items, LIMIT/OFFSET, SET values) —
  /// a generalization of the same balanced-parens trick
  /// `sql_autocomplete.dart`'s `isInsideColumnList` already uses. Consumes
  /// tokens (tracking its own paren depth so a function call's/subquery's
  /// internal commas and parens don't confuse it) until: end of input, a
  /// [stopKeywords] keyword at depth zero, a depth-zero comma (only when
  /// [stopAtComma]), or — critically — an **unbalanced** `)` at depth
  /// zero, which is never consumed here: it belongs to whatever enclosing
  /// `(...)` this expression is nested inside (a subquery, a CTE body), not
  /// to this expression.
  ExpressionStub _scanExpressionStub({
    required Set<String> stopKeywords,
    bool stopAtComma = false,
  }) {
    final start = _current.start;
    final tokens = <SqlToken>[];
    var depth = 0;
    while (true) {
      final tok = _current;
      if (tok.isEof) break;
      if (depth == 0) {
        if (tok.type == SqlTokenType.punctuation &&
            (tok.text == ')' || tok.text == ';')) {
          break;
        }
        if (stopAtComma && tok.type == SqlTokenType.punctuation && tok.text == ',') {
          break;
        }
        if (tok.type == SqlTokenType.keyword &&
            stopKeywords.contains(tok.text.toUpperCase())) {
          break;
        }
      }
      if (tok.type == SqlTokenType.punctuation && tok.text == '(') depth++;
      if (tok.type == SqlTokenType.punctuation && tok.text == ')') depth--;
      tokens.add(tok);
      _advance();
    }
    final end = tokens.isEmpty ? start : tokens.last.end;
    return ExpressionStub(tokens, start: start, end: end);
  }

  /// Consumes a balanced `(...)` body whose opening `(` was already
  /// consumed by the caller — used wherever v1 deliberately doesn't parse
  /// what's inside (a function call's arguments in FROM, an INSERT's
  /// VALUES rows).
  void _skipBalancedParens() {
    var depth = 1;
    while (depth > 0 && !_current.isEof) {
      if (_isPunct('(')) depth++;
      if (_isPunct(')')) depth--;
      _advance();
    }
  }

  // --- Statement dispatch ------------------------------------------------

  Statement _parseStatement() {
    final start = _current.start;
    if (_isKeyword('WITH') || _isKeyword('SELECT')) {
      return _parseSelectStatementWithOptionalWith(start);
    }
    if (_isKeyword('INSERT')) return _parseInsertStatement(start);
    if (_isKeyword('UPDATE')) return _parseUpdateStatement(start);
    if (_isKeyword('DELETE')) return _parseDeleteStatement(start);
    return _parseUnknownStatement(start);
  }

  Statement _parseUnknownStatement(int start) {
    while (!_current.isEof) {
      _advance();
    }
    return UnknownStatement(start: start, end: _lastConsumedEnd);
  }

  // --- SELECT --------------------------------------------------------

  SelectStatement _parseSelectStatementWithOptionalWith(int start) {
    return _parseSetOperationChain(_parseSingleSelectStatement(start));
  }

  /// Parses one `[WITH ...] SELECT ...` unit — everything [_parseSelectCore]
  /// handles except following a trailing `UNION`/`INTERSECT`/`EXCEPT`, which
  /// [_parseSetOperationChain] takes over from here.
  SelectStatement _parseSingleSelectStatement(int start) {
    WithClause? withClause;
    if (_isKeyword('WITH')) {
      withClause = _parseWithClause();
    }
    return _parseSelectCore(start, withClause);
  }

  /// Iteratively parses any `UNION`/`UNION ALL`/`INTERSECT`/`EXCEPT`
  /// elements following [first] and folds them into [first]'s right-nested
  /// `setOperation` chain. Real bug fixed 2026-08-04 (AUDITORIA_CODIGO.md):
  /// this used to recurse through `_parseSelectStatementWithOptionalWith`
  /// once per chain element — `_subqueryDepth` already bounds *nested*
  /// (parenthesized) SELECTs, but a flat `SELECT ... UNION ALL SELECT ...`
  /// chain needs no parentheses at all, so a script with many thousands of
  /// them pasted together (a real shape — bulk-generated `UNION ALL`
  /// scripts exist) could exhaust the Dart call stack. Folding via a loop
  /// over a plain list instead removes the native-stack dependency entirely
  /// — it's bounded only by heap memory, same as parsing a long flat
  /// `selectList`.
  SelectStatement _parseSetOperationChain(SelectStatement first) {
    final opTypes = <SetOperationType>[];
    final selects = <SelectStatement>[first];
    while (true) {
      final opType = _tryMatchSetOperationType();
      if (opType == null) break;
      final rightStart = _current.start;
      opTypes.add(opType);
      selects.add(_parseSingleSelectStatement(rightStart));
    }
    if (opTypes.isEmpty) return first;

    // Fold from the tail backward: the rightmost SELECT keeps no
    // `setOperation` of its own; each one before it gets a `setOperation`
    // pointing at the (already-folded) remainder of the chain — the exact
    // same right-nested shape the old recursive version produced.
    var acc = selects.last;
    for (var i = selects.length - 2; i >= 0; i--) {
      final left = selects[i];
      acc = SelectStatement(
        withClause: left.withClause,
        distinct: left.distinct,
        selectList: left.selectList,
        fromClause: left.fromClause,
        whereClause: left.whereClause,
        groupBy: left.groupBy,
        having: left.having,
        orderBy: left.orderBy,
        limitClause: left.limitClause,
        offsetClause: left.offsetClause,
        setOperation: SetOperation(
            type: opTypes[i], right: acc, start: acc.start, end: acc.end),
        start: left.start,
        end: acc.end,
      );
    }
    return acc;
  }

  WithClause _parseWithClause() {
    final start = _current.start;
    _matchKeyword('WITH');
    final recursive = _matchKeyword('RECURSIVE') != null;
    final ctes = <CteDefinition>[_parseCteDefinition()];
    while (_matchPunct(',') != null) {
      ctes.add(_parseCteDefinition());
    }
    return WithClause(
        recursive: recursive, ctes: ctes, start: start, end: _lastConsumedEnd);
  }

  CteDefinition _parseCteDefinition() {
    final start = _current.start;
    final nameToken = _expectName(
        code: 'expected-cte-name', message: 'Se esperaba el nombre de la CTE.');

    List<String>? columnAliases;
    if (_matchPunct('(') != null) {
      columnAliases = [];
      if (!_isPunct(')')) {
        columnAliases.add(_expectName(
                code: 'expected-column-name',
                message: 'Se esperaba un nombre de columna.')
            .text);
        while (_matchPunct(',') != null) {
          columnAliases.add(_expectName(
                  code: 'expected-column-name',
                  message: 'Se esperaba un nombre de columna.')
              .text);
        }
      }
      _expectPunct(')', code: 'expected-close-paren', message: 'Se esperaba ")".');
    }

    _expectKeyword('AS', code: 'expected-as', message: 'Se esperaba AS.');
    final bodyStart = _current.start;
    final query = _parseParenthesizedSelect();
    final bodyEnd = _lastConsumedEnd;

    return CteDefinition(
      name: nameToken.text,
      nameStart: nameToken.start,
      nameEnd: nameToken.end,
      columnAliases: columnAliases,
      query: query,
      bodyStart: bodyStart,
      bodyEnd: bodyEnd,
      start: start,
      end: bodyEnd,
    );
  }

  /// Parses a `(SELECT ...)`/`(WITH ...)` body — shared by CTE definitions
  /// and subquery table refs. Returns null (and leaves the body only as an
  /// opaque skip) when the `(` is missing, the body isn't recognizably a
  /// SELECT/WITH, or [_maxSubqueryDepth] was hit.
  SelectStatement? _parseParenthesizedSelect() {
    if (_matchPunct('(') == null) {
      _addDiagnostic(
          code: 'expected-open-paren', message: 'Se esperaba "(".');
      return null;
    }
    if (_subqueryDepth >= _maxSubqueryDepth) {
      _skipBalancedParens();
      return null;
    }
    if (!_isKeyword('SELECT') && !_isKeyword('WITH')) {
      _addDiagnostic(
          code: 'expected-select', message: 'Se esperaba SELECT o WITH.');
      _skipBalancedParens();
      return null;
    }
    _subqueryDepth++;
    final query = _parseSelectStatementWithOptionalWith(_current.start);
    _subqueryDepth--;
    _expectPunct(')', code: 'expected-close-paren', message: 'Se esperaba ")".');
    return query;
  }

  SelectStatement _parseSelectCore(int start, WithClause? withClause) {
    _expectKeyword('SELECT', code: 'expected-select', message: 'Se esperaba SELECT.');
    final distinct = _matchKeyword('DISTINCT') != null;
    if (!distinct) _matchKeyword('ALL');

    final selectList = <SelectItem>[];
    if (!_isKeyword('FROM') && !_current.isEof && !_isPunct(')')) {
      selectList.add(_parseSelectItem());
      while (_matchPunct(',') != null) {
        selectList.add(_parseSelectItem());
      }
    }
    if (selectList.isEmpty) {
      _addDiagnostic(
        code: 'expected-select-item',
        message: 'Se esperaba al menos una columna después de SELECT.',
      );
    }

    FromClause? fromClause;
    if (_isKeyword('FROM')) {
      fromClause = _parseFromClause();
    }

    ExpressionStub? whereClause;
    if (_matchKeyword('WHERE') != null) {
      whereClause = _scanExpressionStub(stopKeywords: _clauseKeywords);
    }

    final groupBy = _parseGroupByClause();

    ExpressionStub? having;
    if (_matchKeyword('HAVING') != null) {
      having = _scanExpressionStub(stopKeywords: _clauseKeywords);
    }

    final orderBy = _parseOrderByClause();
    final limitOffset = _parseLimitOffset();

    // Any trailing UNION/INTERSECT/EXCEPT is folded in by
    // [_parseSetOperationChain] (called from [_parseSelectStatementWithOptionalWith]),
    // not here — see its doc comment for why.
    return SelectStatement(
      withClause: withClause,
      distinct: distinct,
      selectList: selectList,
      fromClause: fromClause,
      whereClause: whereClause,
      groupBy: groupBy,
      having: having,
      orderBy: orderBy,
      limitClause: limitOffset.limit,
      offsetClause: limitOffset.offset,
      setOperation: null,
      start: start,
      end: _lastConsumedEnd,
    );
  }

  /// Parses a `GROUP BY expr, expr, ...` clause. Returns the empty list
  /// (not null) when there's no `GROUP BY` at all, matching
  /// [SelectStatement.groupBy]'s shape — called with the cursor positioned
  /// right where `GROUP` would start, a no-op otherwise.
  List<ExpressionStub> _parseGroupByClause() {
    final groupBy = <ExpressionStub>[];
    if (_matchKeyword('GROUP') != null) {
      _expectKeyword('BY',
          code: 'expected-by', message: 'Se esperaba BY después de GROUP.');
      groupBy.add(
          _scanExpressionStub(stopKeywords: _clauseKeywords, stopAtComma: true));
      while (_matchPunct(',') != null) {
        groupBy.add(_scanExpressionStub(
            stopKeywords: _clauseKeywords, stopAtComma: true));
      }
    }
    return groupBy;
  }

  /// Parses an `ORDER BY item, item, ...` clause. Returns the empty list
  /// (not null) when there's no `ORDER BY` at all, matching
  /// [SelectStatement.orderBy]'s shape.
  List<OrderByItem> _parseOrderByClause() {
    final orderBy = <OrderByItem>[];
    if (_matchKeyword('ORDER') != null) {
      _expectKeyword('BY',
          code: 'expected-by', message: 'Se esperaba BY después de ORDER.');
      orderBy.add(_parseOrderByItem());
      while (_matchPunct(',') != null) {
        orderBy.add(_parseOrderByItem());
      }
    }
    return orderBy;
  }

  /// Parses trailing `LIMIT expr` / `OFFSET expr` clauses, in that fixed
  /// order (Postgres-only parser, per this module's scope — T-SQL's
  /// `OFFSET ... FETCH` isn't handled here). Either, both, or neither may
  /// be present.
  ({ExpressionStub? limit, ExpressionStub? offset}) _parseLimitOffset() {
    ExpressionStub? limitClause;
    if (_matchKeyword('LIMIT') != null) {
      limitClause = _scanExpressionStub(stopKeywords: _clauseKeywords);
    }
    ExpressionStub? offsetClause;
    if (_matchKeyword('OFFSET') != null) {
      offsetClause = _scanExpressionStub(stopKeywords: _clauseKeywords);
    }
    return (limit: limitClause, offset: offsetClause);
  }

  SetOperationType? _tryMatchSetOperationType() {
    if (_isKeyword('UNION')) {
      _advance();
      return _matchKeyword('ALL') != null
          ? SetOperationType.unionAll
          : SetOperationType.union;
    }
    if (_isKeyword('INTERSECT')) {
      _advance();
      return SetOperationType.intersect;
    }
    if (_isKeyword('EXCEPT')) {
      _advance();
      return SetOperationType.except;
    }
    return null;
  }

  /// End of input, a depth-zero `,`/`)`, or `AS`/a [_clauseKeywords] entry
  /// — used only by the implicit-alias lookahead in [_parseSelectItem].
  bool _isSelectItemBoundary(SqlToken t) {
    if (t.isEof) return true;
    if (t.type == SqlTokenType.punctuation && (t.text == ',' || t.text == ')')) {
      return true;
    }
    if (t.type == SqlTokenType.keyword) {
      final upper = t.text.toUpperCase();
      return upper == 'AS' || _clauseKeywords.contains(upper);
    }
    return false;
  }

  SelectItem _parseSelectItem() {
    final itemStart = _current.start;

    // `alias.*` — lookahead: name '.' '*'.
    if (_isNameToken(_current) &&
        _peek(1).type == SqlTokenType.punctuation &&
        _peek(1).text == '.' &&
        _peek(2).type == SqlTokenType.operatorToken &&
        _peek(2).text == '*') {
      final qualifier = _nameText(_current);
      _advance();
      _advance();
      _advance();
      return SelectItem(
          isStar: true,
          starQualifier: qualifier,
          start: itemStart,
          end: _lastConsumedEnd);
    }

    // Bare `*`.
    if (_isOperator('*')) {
      _advance();
      return SelectItem(isStar: true, start: itemStart, end: _lastConsumedEnd);
    }

    // Unambiguous implicit-alias shape, checked via lookahead *before* the
    // general scan below even starts: a single bare name immediately
    // followed by another bare name, with nothing else before the next
    // item boundary (`SELECT a x FROM t`). This has to be decided upfront
    // — a forward-only scan has no way to know in hindsight that it should
    // have stopped one token earlier to leave room for an implicit alias.
    // Anything more complex without `AS` (`SELECT a + b c FROM t`) is
    // deliberately left unresolved: v1 has no real expression grammar, so
    // "is this trailing name an alias or part of the expression" is
    // genuinely ambiguous beyond this one simple case (see the module
    // plan's expression-grammar scope note).
    if (_isNameToken(_current) && _isNameToken(_peek(1)) && _isSelectItemBoundary(_peek(2))) {
      final exprTok = _current;
      _advance();
      final aliasTok = _current;
      _advance();
      return SelectItem(
        isStar: false,
        expression: ExpressionStub([exprTok], start: exprTok.start, end: exprTok.end),
        alias: _nameText(aliasTok),
        aliasStart: aliasTok.start,
        aliasEnd: aliasTok.end,
        start: itemStart,
        end: _lastConsumedEnd,
      );
    }

    // General expression — scanned by hand (not via _scanExpressionStub)
    // because AS/the item boundary keywords need to stop it, same shape as
    // that helper otherwise.
    final exprTokens = <SqlToken>[];
    var depth = 0;
    while (true) {
      final tok = _current;
      if (tok.isEof) break;
      if (depth == 0) {
        if (tok.type == SqlTokenType.punctuation && (tok.text == ')' || tok.text == ',')) {
          break;
        }
        if (tok.type == SqlTokenType.keyword &&
            (tok.text.toUpperCase() == 'AS' || _clauseKeywords.contains(tok.text.toUpperCase()))) {
          break;
        }
      }
      if (tok.type == SqlTokenType.punctuation && tok.text == '(') depth++;
      if (tok.type == SqlTokenType.punctuation && tok.text == ')') depth--;
      exprTokens.add(tok);
      _advance();
    }
    final exprEnd = exprTokens.isEmpty ? itemStart : exprTokens.last.end;
    final expression =
        exprTokens.isEmpty ? null : ExpressionStub(exprTokens, start: itemStart, end: exprEnd);
    if (exprTokens.isEmpty) {
      // Reached here (not the empty-whole-list check in _parseSelectCore)
      // specifically for a *dangling* comma (`SELECT a, FROM t`) — this
      // item was expected but nothing usable was there before the next
      // boundary.
      _addDiagnostic(
          code: 'expected-select-item', message: 'Se esperaba una columna.');
    }

    String? alias;
    int? aliasStart, aliasEnd;
    if (_matchKeyword('AS') != null) {
      final t = _expectName(
          code: 'expected-alias', message: 'Se esperaba un alias después de AS.');
      alias = t.text.isEmpty ? null : t.text;
      aliasStart = t.start;
      aliasEnd = t.end;
    }

    return SelectItem(
      isStar: false,
      expression: expression,
      alias: alias,
      aliasStart: aliasStart,
      aliasEnd: aliasEnd,
      start: itemStart,
      end: _lastConsumedEnd,
    );
  }

  OrderByItem _parseOrderByItem() {
    final start = _current.start;
    final expr = _scanExpressionStub(
      stopKeywords: {..._clauseKeywords, ..._orderByModifierKeywords},
      stopAtComma: true,
    );
    bool? descending;
    if (_matchKeyword('ASC') != null) {
      descending = false;
    } else if (_matchKeyword('DESC') != null) {
      descending = true;
    }
    _matchKeyword('NULLS');
    if (_isKeyword('FIRST') || _isKeyword('LAST')) _advance();
    return OrderByItem(expression: expr, descending: descending, start: start, end: _lastConsumedEnd);
  }

  // --- FROM / JOIN ------------------------------------------------------

  FromClause _parseFromClause() {
    final start = _current.start;
    _matchKeyword('FROM');
    return _parseFromItemList(start);
  }

  FromClause _parseUsingClause() {
    final start = _current.start;
    return _parseFromItemList(start);
  }

  FromClause _parseFromItemList(int start) {
    final items = <FromItem>[_parseFromItem(JoinType.none)];
    while (true) {
      if (_matchPunct(',') != null) {
        items.add(_parseFromItem(JoinType.comma));
        continue;
      }
      final joinType = _tryMatchJoinType();
      if (joinType == null) break;
      items.add(_parseFromItem(joinType));
    }
    return FromClause(items, start: start, end: _lastConsumedEnd);
  }

  JoinType? _tryMatchJoinType() {
    if (_isKeyword('JOIN')) {
      _advance();
      return JoinType.inner;
    }
    if (_isKeyword('INNER')) {
      _advance();
      _expectKeyword('JOIN', code: 'expected-join', message: 'Se esperaba JOIN.');
      return JoinType.inner;
    }
    if (_isKeyword('LEFT')) {
      _advance();
      _matchKeyword('OUTER');
      _expectKeyword('JOIN', code: 'expected-join', message: 'Se esperaba JOIN.');
      return JoinType.left;
    }
    if (_isKeyword('RIGHT')) {
      _advance();
      _matchKeyword('OUTER');
      _expectKeyword('JOIN', code: 'expected-join', message: 'Se esperaba JOIN.');
      return JoinType.right;
    }
    if (_isKeyword('FULL')) {
      _advance();
      _matchKeyword('OUTER');
      _expectKeyword('JOIN', code: 'expected-join', message: 'Se esperaba JOIN.');
      return JoinType.full;
    }
    if (_isKeyword('CROSS')) {
      _advance();
      _expectKeyword('JOIN', code: 'expected-join', message: 'Se esperaba JOIN.');
      return JoinType.cross;
    }
    return null;
  }

  static const _fromItemBoundaryKeywords = {
    ..._clauseKeywords,
    ..._joinKeywords,
  };

  FromItem _parseFromItem(JoinType joinType) {
    final start = _current.start;
    final ref = _parseTableRef();
    ExpressionStub? onCondition;
    List<String>? usingColumns;
    if (_matchKeyword('ON') != null) {
      onCondition =
          _scanExpressionStub(stopKeywords: _fromItemBoundaryKeywords, stopAtComma: true);
    } else if (_matchKeyword('USING') != null) {
      usingColumns = [];
      _expectPunct('(', code: 'expected-open-paren', message: 'Se esperaba "(".');
      if (!_isPunct(')')) {
        usingColumns.add(_expectName(
                code: 'expected-column-name', message: 'Se esperaba un nombre de columna.')
            .text);
        while (_matchPunct(',') != null) {
          usingColumns.add(_expectName(
                  code: 'expected-column-name', message: 'Se esperaba un nombre de columna.')
              .text);
        }
      }
      _expectPunct(')', code: 'expected-close-paren', message: 'Se esperaba ")".');
    }
    return FromItem(
      joinType: joinType,
      ref: ref,
      onCondition: onCondition,
      usingColumns: usingColumns,
      start: start,
      end: _lastConsumedEnd,
    );
  }

  /// Parses `name` or `schema.name` — required (via `_expectName`) — and
  /// returns the resolved `(schema, nameToken)` pair. Shared by
  /// [_parseTableRef] (FROM/JOIN) and [_parseSimpleTableTarget]
  /// (INSERT/UPDATE/DELETE), which used to duplicate this exact "read a
  /// name, peek '.', read another name" sequence. [afterDotMessage] lets
  /// the identifier right after the dot report a more specific message;
  /// defaults to [message] when the caller doesn't need that distinction.
  (String?, SqlToken) _parseQualifiedName({
    required String code,
    required String message,
    String? afterDotMessage,
  }) {
    final first = _expectName(code: code, message: message);
    if (_isPunct('.')) {
      _advance();
      final nameToken =
          _expectName(code: code, message: afterDotMessage ?? message);
      return (first.text, nameToken);
    }
    return (null, first);
  }

  TableRef _parseTableRef() {
    final start = _current.start;

    if (_isPunct('(')) {
      final bodyStart = _current.start;
      final subquery = _parseParenthesizedSelect();
      final bodyEnd = _lastConsumedEnd;
      final alias = _parseOptionalTableAlias();
      return SubqueryTableRef(
        subquery: subquery,
        bodyStart: bodyStart,
        bodyEnd: bodyEnd,
        alias: alias.$1,
        aliasStart: alias.$2,
        aliasEnd: alias.$3,
        start: start,
        end: _lastConsumedEnd,
      );
    }

    final (schema, nameToken) = _parseQualifiedName(
      code: 'expected-table-name',
      message: 'Se esperaba un nombre de tabla.',
      afterDotMessage: 'Se esperaba un nombre de tabla después de ".".',
    );

    if (_isPunct('(')) {
      // A set-returning function call, e.g. `generate_series(1, 10)` — not
      // a real table name, opaque body.
      final bodyStart = start;
      _advance(); // consume '('
      _skipBalancedParens();
      final bodyEnd = _lastConsumedEnd;
      final alias = _parseOptionalTableAlias();
      return FunctionTableRef(
        bodyStart: bodyStart,
        bodyEnd: bodyEnd,
        alias: alias.$1,
        aliasStart: alias.$2,
        aliasEnd: alias.$3,
        start: start,
        end: _lastConsumedEnd,
      );
    }

    final alias = _parseOptionalTableAlias();
    return NamedTableRef(
      schema: schema,
      name: nameToken.text,
      nameStart: nameToken.start,
      nameEnd: nameToken.end,
      alias: alias.$1,
      aliasStart: alias.$2,
      aliasEnd: alias.$3,
      start: start,
      end: _lastConsumedEnd,
    );
  }

  /// `AS alias`, or a bare implicit alias — but only when the next token
  /// isn't itself a structural keyword (otherwise `FROM t WHERE ...` would
  /// swallow `WHERE` as `t`'s alias) — [_isNameToken] already excludes
  /// every [_structuralKeywords] entry, so no separate boundary check is
  /// needed here.
  (String?, int?, int?) _parseOptionalTableAlias() {
    if (_matchKeyword('AS') != null) {
      final t = _expectName(
          code: 'expected-alias', message: 'Se esperaba un alias después de AS.');
      return t.text.isEmpty ? (null, null, null) : (t.text, t.start, t.end);
    }
    if (_isNameToken(_current)) {
      final t = _current;
      _advance();
      return (_nameText(t), t.start, t.end);
    }
    return (null, null, null);
  }

  // --- INSERT / UPDATE / DELETE -----------------------------------------

  /// Target table for INSERT/UPDATE/DELETE — schema-qualified name plus an
  /// optional alias, explicit (`AS x`) or implicit (`pedidos p`), via the
  /// same [_parseOptionalTableAlias] FROM/JOIN already uses.
  ///
  /// Real bug fixed 2026-08-04 (AUDITORIA_CODIGO.md): this used to accept
  /// *only* `AS x`, on the theory that reusing [_parseOptionalTableAlias]
  /// here would risk swallowing the keyword that routinely follows an
  /// UPDATE/DELETE target (`SET`, `USING`, `WHERE`) as an implicit alias.
  /// That theory didn't hold: [_parseOptionalTableAlias]'s implicit-alias
  /// branch guards on [_isNameToken], and `SET`/`USING`/`WHERE` are already
  /// hard-excluded from [_isNameToken] via [_structuralKeywords] — the same
  /// exclusion that makes it safe for FROM/JOIN. Without this, `DELETE FROM
  /// pedidos p WHERE id = 1` left `p` unconsumed and never actually reached
  /// `WHERE`, since nothing between here and there skips a stray token.
  NamedTableRef _parseSimpleTableTarget(
      {required String code, required String message}) {
    final start = _current.start;
    final (schema, nameToken) = _parseQualifiedName(code: code, message: message);
    final alias = _parseOptionalTableAlias();
    return NamedTableRef(
      schema: schema,
      name: nameToken.text,
      nameStart: nameToken.start,
      nameEnd: nameToken.end,
      alias: alias.$1,
      aliasStart: alias.$2,
      aliasEnd: alias.$3,
      start: start,
      end: _lastConsumedEnd,
    );
  }

  InsertStatement _parseInsertStatement(int start) {
    _expectKeyword('INSERT', code: 'expected-insert', message: 'Se esperaba INSERT.');
    _expectKeyword('INTO', code: 'expected-into', message: 'Se esperaba INTO después de INSERT.');
    final target = _parseSimpleTableTarget(
        code: 'expected-table-name', message: 'Se esperaba el nombre de la tabla.');

    final columns = <ColumnRef>[];
    if (_matchPunct('(') != null) {
      if (!_isPunct(')')) {
        columns.add(_parseColumnRef());
        while (_matchPunct(',') != null) {
          columns.add(_parseColumnRef());
        }
      }
      _expectPunct(')', code: 'expected-close-paren', message: 'Se esperaba ")".');
    }

    SelectStatement? sourceQuery;
    int? opaqueStart, opaqueEnd;
    if (_isKeyword('SELECT') || _isKeyword('WITH')) {
      sourceQuery = _parseSelectStatementWithOptionalWith(_current.start);
    } else if (_matchKeyword('VALUES') != null) {
      opaqueStart = _lastConsumedEnd;
      final tokens = <SqlToken>[];
      while (!_current.isEof && !_isKeyword('RETURNING')) {
        tokens.add(_current);
        _advance();
      }
      opaqueEnd = tokens.isEmpty ? opaqueStart : tokens.last.end;
    } else if (!_matchKeywordPhrase(['DEFAULT', 'VALUES'])) {
      _addDiagnostic(
          code: 'expected-insert-source', message: 'Se esperaba VALUES, DEFAULT VALUES o SELECT.');
    }

    if (_matchKeyword('RETURNING') != null) {
      while (!_current.isEof) {
        _advance();
      }
    }

    return InsertStatement(
      target: target,
      columns: columns,
      sourceQuery: sourceQuery,
      opaqueBodyStart: opaqueStart,
      opaqueBodyEnd: opaqueEnd,
      start: start,
      end: _lastConsumedEnd,
    );
  }

  bool _matchKeywordPhrase(List<String> keywords) {
    for (var i = 0; i < keywords.length; i++) {
      if (_peek(i).type != SqlTokenType.keyword ||
          _peek(i).text.toUpperCase() != keywords[i]) {
        return false;
      }
    }
    for (var i = 0; i < keywords.length; i++) {
      _advance();
    }
    return true;
  }

  ColumnRef _parseColumnRef() {
    final t = _expectName(
        code: 'expected-column-name', message: 'Se esperaba un nombre de columna.');
    return ColumnRef(t.text, start: t.start, end: t.end);
  }

  UpdateStatement _parseUpdateStatement(int start) {
    _expectKeyword('UPDATE', code: 'expected-update', message: 'Se esperaba UPDATE.');
    final target = _parseSimpleTableTarget(
        code: 'expected-table-name', message: 'Se esperaba el nombre de la tabla.');
    _expectKeyword('SET', code: 'expected-set', message: 'Se esperaba SET.');

    final setItems = <SetItem>[_parseSetItem()];
    while (_matchPunct(',') != null) {
      setItems.add(_parseSetItem());
    }

    FromClause? fromClause;
    if (_isKeyword('FROM')) {
      fromClause = _parseFromClause();
    }

    ExpressionStub? whereClause;
    if (_matchKeyword('WHERE') != null) {
      whereClause = _scanExpressionStub(stopKeywords: _clauseKeywords);
    }

    if (_matchKeyword('RETURNING') != null) {
      while (!_current.isEof) {
        _advance();
      }
    }

    return UpdateStatement(
      target: target,
      setItems: setItems,
      fromClause: fromClause,
      whereClause: whereClause,
      start: start,
      end: _lastConsumedEnd,
    );
  }

  static const _setItemStopKeywords = {..._clauseKeywords, 'SET'};

  SetItem _parseSetItem() {
    final start = _current.start;
    final col = _expectName(
        code: 'expected-column-name', message: 'Se esperaba un nombre de columna.');
    _expectOperator('=', code: 'expected-equals', message: 'Se esperaba "=".');
    final value =
        _scanExpressionStub(stopKeywords: _setItemStopKeywords, stopAtComma: true);
    return SetItem(
      column: col.text,
      columnStart: col.start,
      columnEnd: col.end,
      value: value,
      start: start,
      end: _lastConsumedEnd,
    );
  }

  DeleteStatement _parseDeleteStatement(int start) {
    _expectKeyword('DELETE', code: 'expected-delete', message: 'Se esperaba DELETE.');
    _expectKeyword('FROM', code: 'expected-from', message: 'Se esperaba FROM después de DELETE.');
    final target = _parseSimpleTableTarget(
        code: 'expected-table-name', message: 'Se esperaba el nombre de la tabla.');

    FromClause? usingClause;
    if (_matchKeyword('USING') != null) {
      usingClause = _parseUsingClause();
    }

    ExpressionStub? whereClause;
    if (_matchKeyword('WHERE') != null) {
      whereClause = _scanExpressionStub(stopKeywords: _clauseKeywords);
    }

    if (_matchKeyword('RETURNING') != null) {
      while (!_current.isEof) {
        _advance();
      }
    }

    return DeleteStatement(
      target: target,
      usingClause: usingClause,
      whereClause: whereClause,
      start: start,
      end: _lastConsumedEnd,
    );
  }
}
