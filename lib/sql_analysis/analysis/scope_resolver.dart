import '../ast/clauses.dart';
import '../ast/statements.dart';
import '../ast/table_ref.dart';
import '../lexer/sql_token.dart';

/// Which real/pseudo table an alias (or bare table name, when there's no
/// alias) refers to at some point in a query — the answer to "what does
/// `p.` mean here", which is the whole reason this module exists (see the
/// plan's #1 priority: alias-aware autocomplete).
class TableBinding {
  const TableBinding({
    this.alias,
    required this.effectiveName,
    this.schema,
    this.tableName,
    required this.isCte,
    required this.isSubquery,
    this.knownColumns,
  });

  /// Explicit or implicit alias, if the ref had one.
  final String? alias;

  /// What a qualifier like `x.column` needs to match — [alias] when
  /// present, otherwise [tableName] (a bare `FROM pedidos` is referenced as
  /// `pedidos.col`, not just `.col`).
  final String effectiveName;

  final String? schema;

  /// The real catalog table name — null for a CTE, subquery, or function
  /// call in FROM position (none of those are real tables a schema lookup
  /// would find).
  final String? tableName;

  final bool isCte;
  final bool isSubquery;

  /// Populated only when statically resolvable — a CTE with an explicit
  /// `(col, ...)` list, or every item in a subquery's/CTE's own SELECT
  /// list has a determinable name (an alias, or a bare single-identifier
  /// expression). Null means "ask `table_columns_provider.dart` instead"
  /// for a real table, or "genuinely unknown" for a subquery/CTE whose
  /// column names can't be worked out without real expression parsing
  /// (e.g. it projects `SELECT a + b FROM ...` with no alias).
  final List<String>? knownColumns;
}

class ScopeInfo {
  const ScopeInfo({required this.visibleTables});
  final List<TableBinding> visibleTables;
}

/// Finds every table/CTE/subquery binding visible at [cursorOffset] inside
/// [statement] — the answer to "if the user typed `x.` right here, what
/// could `x` be, and what came from it".
///
/// **[cursorOffset] must be in the same coordinate space [statement]'s own
/// node offsets are in** — relative to the single statement's text, not a
/// full multi-statement script (see [SqlNode.start]'s doc comment, and
/// `sql_script_analyzer.dart`'s `AnalyzedStatement.toRelativeOffset` for
/// the one place a caller converts an absolute editor cursor position into
/// this coordinate space).
///
/// Respects lexical visibility, with two deliberate simplifications (see
/// the module plan's risk notes for why — in short, both fail toward
/// "suggests nothing" rather than "suggests something wrong"):
/// - A CTE only sees CTEs declared *before* it in the same `WITH` (plus
///   itself when `RECURSIVE`) — never CTEs declared after it.
/// - A subquery in FROM position does **not** inherit its enclosing
///   query's table bindings (no correlated-subquery support) — only CTEs
///   are visible across nesting levels (they're visible to the whole
///   `WITH`-statement, not lexically scoped to one FROM the way table
///   aliases are).
ScopeInfo resolveScopeAt(Statement statement, int cursorOffset) {
  final bindings = switch (statement) {
    SelectStatement s => _resolveSelect(s, cursorOffset, const []),
    InsertStatement s => _resolveInsert(s, cursorOffset),
    UpdateStatement s => _resolveUpdate(s, cursorOffset),
    DeleteStatement s => _resolveDelete(s, cursorOffset),
    UnknownStatement _ => const <TableBinding>[],
  };
  return ScopeInfo(visibleTables: bindings);
}

List<TableBinding> _resolveSelect(
    SelectStatement select, int cursor, List<TableBinding> ambientCtes) {
  final ownCtes = <TableBinding>[];
  final withClause = select.withClause;
  if (withClause != null) {
    for (final cte in withClause.ctes) {
      if (cte.query != null && cte.bodyStart <= cursor && cursor <= cte.bodyEnd) {
        final visibleSoFar = [...ambientCtes, ...ownCtes];
        if (withClause.recursive) visibleSoFar.add(_cteBinding(cte));
        return _resolveSelect(cte.query!, cursor, visibleSoFar);
      }
      ownCtes.add(_cteBinding(cte));
    }
  }
  final allCtes = [...ambientCtes, ...ownCtes];

  final fromClause = select.fromClause;
  if (fromClause != null) {
    for (final item in fromClause.items) {
      final ref = item.ref;
      if (ref is SubqueryTableRef &&
          ref.subquery != null &&
          ref.bodyStart <= cursor &&
          cursor <= ref.bodyEnd) {
        // Deliberately `allCtes` only, not this level's own FROM bindings
        // — see this function's doc comment on the correlated-subquery
        // module plan doc's doc comment on `resolveScopeAt`.
        return _resolveSelect(ref.subquery!, cursor, allCtes);
      }
    }
  }

  final setOperation = select.setOperation;
  if (setOperation != null &&
      setOperation.right.start <= cursor &&
      cursor <= setOperation.right.end) {
    return _resolveSelect(setOperation.right, cursor, allCtes);
  }

  // Deliberately *not* appending `allCtes` as extra standalone entries —
  // a CTE is only actually "bound" (and thus a real answer to "what does
  // `x.` mean here") once something in FROM references it by name. The
  // `_tableRefBinding` call above already resolves each `NamedTableRef`
  // against `allCtes` and returns the CTE's own binding when the name
  // matches, so a referenced CTE is already represented exactly once.
  final bindings = <TableBinding>[];
  if (fromClause != null) {
    for (final item in fromClause.items) {
      bindings.add(_tableRefBinding(item.ref, allCtes));
    }
  }
  return bindings;
}

List<TableBinding> _resolveInsert(InsertStatement stmt, int cursor) {
  final sourceQuery = stmt.sourceQuery;
  if (sourceQuery != null && sourceQuery.start <= cursor && cursor <= sourceQuery.end) {
    return _resolveSelect(sourceQuery, cursor, const []);
  }
  final target = stmt.target;
  return target == null ? const [] : [_tableRefBinding(target, const [])];
}

List<TableBinding> _resolveUpdate(UpdateStatement stmt, int cursor) {
  final bindings = <TableBinding>[];
  final target = stmt.target;
  if (target != null) bindings.add(_tableRefBinding(target, const []));
  final fromClause = stmt.fromClause;
  if (fromClause != null) {
    for (final item in fromClause.items) {
      bindings.add(_tableRefBinding(item.ref, const []));
    }
  }
  return bindings;
}

List<TableBinding> _resolveDelete(DeleteStatement stmt, int cursor) {
  final bindings = <TableBinding>[];
  final target = stmt.target;
  if (target != null) bindings.add(_tableRefBinding(target, const []));
  final usingClause = stmt.usingClause;
  if (usingClause != null) {
    for (final item in usingClause.items) {
      bindings.add(_tableRefBinding(item.ref, const []));
    }
  }
  return bindings;
}

TableBinding _cteBinding(CteDefinition cte) => TableBinding(
      effectiveName: cte.name,
      isCte: true,
      isSubquery: false,
      knownColumns: cte.columnAliases ??
          (cte.query == null ? null : _simpleSelectListColumns(cte.query!)),
    );

/// [ctesInScope] lets a plain `NamedTableRef` (e.g. `FROM cte`) resolve to
/// the CTE it's actually referencing — a CTE name is only structurally a
/// "table name" as far as the parser knows (it has no notion of `WITH`
/// bindings), so this is the one place that distinction gets made. A
/// schema-qualified reference (`FROM public.cte`) never matches — a real
/// CTE can't be schema-qualified, so this correctly falls through to the
/// "real table" case instead.
TableBinding _tableRefBinding(TableRef ref, List<TableBinding> ctesInScope) {
  return switch (ref) {
    NamedTableRef r => _namedTableRefBinding(r, ctesInScope),
    SubqueryTableRef r => TableBinding(
        alias: r.alias,
        effectiveName: r.alias ?? '',
        isCte: false,
        isSubquery: true,
        knownColumns: r.subquery == null ? null : _simpleSelectListColumns(r.subquery!),
      ),
    FunctionTableRef r => TableBinding(
        alias: r.alias,
        effectiveName: r.alias ?? '',
        isCte: false,
        isSubquery: false,
      ),
  };
}

TableBinding _namedTableRefBinding(NamedTableRef r, List<TableBinding> ctesInScope) {
  if (r.schema == null) {
    for (final cte in ctesInScope) {
      // Real bug fixed 2026-08-04 (AUDITORIA_CODIGO.md): compared case-
      // sensitively, so a CTE declared `Totales` referenced as `totales`
      // (or vice versa) fell through to "unknown table" instead of
      // resolving — both Postgres (unquoted identifiers fold to lowercase)
      // and SQL Server (case-insensitive collation by default) treat these
      // as the same name.
      if (cte.effectiveName.toLowerCase() == r.name.toLowerCase()) {
        return TableBinding(
          alias: r.alias,
          effectiveName: r.alias ?? r.name,
          isCte: true,
          isSubquery: false,
          knownColumns: cte.knownColumns,
        );
      }
    }
  }
  return TableBinding(
    alias: r.alias,
    effectiveName: r.alias ?? r.name,
    schema: r.schema,
    tableName: r.name,
    isCte: false,
    isSubquery: false,
  );
}

/// Column names for a SELECT only when *every* item has one that's
/// statically determinable — a bare `*`/`alias.*` (could be anything) or
/// an un-aliased computed expression makes the whole result null rather
/// than a partial list, since a partial column list would look complete to
/// a caller (autocomplete) with no way to tell it isn't.
List<String>? _simpleSelectListColumns(SelectStatement select) {
  final names = <String>[];
  for (final item in select.selectList) {
    if (item.isStar) return null;
    final alias = item.alias;
    if (alias != null) {
      names.add(alias);
      continue;
    }
    final tokens = item.expression?.tokens;
    if (tokens != null &&
        tokens.length == 1 &&
        (tokens.single.type == SqlTokenType.identifier ||
            tokens.single.type == SqlTokenType.quotedIdentifier)) {
      names.add(tokens.single.text);
      continue;
    }
    return null;
  }
  return names;
}
