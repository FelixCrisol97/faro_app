import 'clauses.dart';
import 'expression_stub.dart';
import 'select_item.dart';
import 'sql_node.dart';
import 'table_ref.dart';

/// One parsed SQL statement — `sealed` *within this file* (unlike the
/// shared [SqlNode] base, which can't be, since its subtypes span several
/// files) so `analysis/scope_resolver.dart` and anything else dispatching
/// on statement kind can `switch` exhaustively.
sealed class Statement extends SqlNode {
  const Statement({required super.start, required super.end});
}

enum SetOperationType { union, unionAll, intersect, except }

/// The right-hand side of a `UNION`/`UNION ALL`/`INTERSECT`/`EXCEPT` —
/// [right] is itself a full [SelectStatement] (which can chain its own
/// [setOperation], so `a UNION b UNION c` parses as a right-leaning chain).
class SetOperation extends SqlNode {
  const SetOperation({
    required this.type,
    required this.right,
    required super.start,
    required super.end,
  });

  final SetOperationType type;
  final SelectStatement right;
}

class SelectStatement extends Statement {
  const SelectStatement({
    this.withClause,
    required this.distinct,
    required this.selectList,
    this.fromClause,
    this.whereClause,
    required this.groupBy,
    this.having,
    required this.orderBy,
    this.limitClause,
    this.offsetClause,
    this.setOperation,
    required super.start,
    required super.end,
  });

  final WithClause? withClause;
  final bool distinct;
  final List<SelectItem> selectList;
  final FromClause? fromClause;
  final ExpressionStub? whereClause;
  final List<ExpressionStub> groupBy;
  final ExpressionStub? having;
  final List<OrderByItem> orderBy;
  final ExpressionStub? limitClause;
  final ExpressionStub? offsetClause;
  final SetOperation? setOperation;
}

/// One column in an `INSERT INTO t (a, b)` column list.
class ColumnRef extends SqlNode {
  const ColumnRef(this.name, {required super.start, required super.end});

  final String name;
}

class InsertStatement extends Statement {
  const InsertStatement({
    this.target,
    required this.columns,
    this.sourceQuery,
    this.opaqueBodyStart,
    this.opaqueBodyEnd,
    required super.start,
    required super.end,
  });

  final NamedTableRef? target;
  final List<ColumnRef> columns;

  /// Populated when the source is `INSERT ... SELECT ...` — structurally
  /// parsed like any other SELECT, so a subquery/CTE inside it still
  /// resolves scope normally.
  final SelectStatement? sourceQuery;

  /// Populated instead of [sourceQuery] for `INSERT ... VALUES (...)` — the
  /// row values aren't modeled structurally in v1 (see the module plan's
  /// scope notes), just kept as an opaque range.
  final int? opaqueBodyStart;
  final int? opaqueBodyEnd;
}

class UpdateStatement extends Statement {
  const UpdateStatement({
    this.target,
    required this.setItems,
    this.fromClause,
    this.whereClause,
    required super.start,
    required super.end,
  });

  final NamedTableRef? target;
  final List<SetItem> setItems;

  /// Postgres's `UPDATE t SET ... FROM other WHERE ...` — [other] joins the
  /// scope alongside [target] for WHERE/SET-value resolution.
  final FromClause? fromClause;
  final ExpressionStub? whereClause;
}

class DeleteStatement extends Statement {
  const DeleteStatement({
    this.target,
    this.usingClause,
    this.whereClause,
    required super.start,
    required super.end,
  });

  final NamedTableRef? target;

  /// Postgres's `DELETE FROM t USING other WHERE ...` — same role as
  /// [UpdateStatement.fromClause].
  final FromClause? usingClause;
  final ExpressionStub? whereClause;
}

/// Any statement whose leading keyword isn't `SELECT`/`WITH`/`INSERT`/
/// `UPDATE`/`DELETE` — `CREATE`/`ALTER`/`DROP`/`TRUNCATE`/`GRANT`/`BEGIN`/
/// `EXPLAIN`/`COPY`/`DO` blocks/PL-pgSQL function bodies, etc. Still lexed
/// (so an unterminated string/dollar-quote inside one still produces a
/// lexical diagnostic) but never parsed structurally — see the module
/// plan's v1 scope for why DDL depth is explicitly deferred.
class UnknownStatement extends Statement {
  const UnknownStatement({required super.start, required super.end});
}
