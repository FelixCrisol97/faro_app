import 'sql_node.dart';
import 'statements.dart';

/// One thing appearing in a FROM/JOIN/USING list — a real table, a
/// subquery, or a function call. `sealed` *within this file* (unlike the
/// shared [SqlNode] base) so a `switch` over a [TableRef] can be exhaustive
/// — this is exactly the kind of dispatch `analysis/scope_resolver.dart`
/// needs (resolve a table binding differently per variant).
sealed class TableRef extends SqlNode {
  const TableRef({
    this.alias,
    this.aliasStart,
    this.aliasEnd,
    required super.start,
    required super.end,
  });

  /// The alias this ref is known by within its query — explicit (`AS x` or
  /// bare `x`) or null if none was given, in which case scope resolution
  /// falls back to the real table name (see [NamedTableRef.name]).
  final String? alias;
  final int? aliasStart;
  final int? aliasEnd;
}

/// `schema.name` or bare `name` — the common case, and the only shape
/// INSERT/UPDATE/DELETE's target can be (you can't insert into a
/// subquery).
class NamedTableRef extends TableRef {
  const NamedTableRef({
    this.schema,
    required this.name,
    required this.nameStart,
    required this.nameEnd,
    super.alias,
    super.aliasStart,
    super.aliasEnd,
    required super.start,
    required super.end,
  });

  final String? schema;
  final String name;
  final int nameStart;
  final int nameEnd;
}

/// `FROM (SELECT ...) AS x` — [subquery] is null when the parenthesized
/// body wasn't recognizably a SELECT/WITH, or recursion got deep enough
/// that `SqlParser` gave up parsing it structurally (see the parser's
/// `_maxSubqueryDepth`) — [bodyStart]/[bodyEnd] are always populated
/// regardless, as an opaque fallback.
class SubqueryTableRef extends TableRef {
  const SubqueryTableRef({
    this.subquery,
    required this.bodyStart,
    required this.bodyEnd,
    super.alias,
    super.aliasStart,
    super.aliasEnd,
    required super.start,
    required super.end,
  });

  final SelectStatement? subquery;
  final int bodyStart;
  final int bodyEnd;
}

/// `FROM generate_series(1, 10)`, `FROM jsonb_each(x)` — set-returning
/// function calls in FROM position. Opaque in v1 (no argument parsing);
/// only [alias] matters for scope resolution, same as the other variants.
class FunctionTableRef extends TableRef {
  const FunctionTableRef({
    required this.bodyStart,
    required this.bodyEnd,
    super.alias,
    super.aliasStart,
    super.aliasEnd,
    required super.start,
    required super.end,
  });

  final int bodyStart;
  final int bodyEnd;
}
