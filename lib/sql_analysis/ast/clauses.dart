import 'expression_stub.dart';
import 'sql_node.dart';
import 'statements.dart';
import 'table_ref.dart';

/// `.none` only for a FROM-clause's very first item — everything after it
/// is either comma-joined (old-style implicit cross join) or an explicit
/// `JOIN` variant.
enum JoinType { none, comma, inner, left, right, full, cross }

/// One item in a FROM/USING list, with however it's joined to the
/// preceding one.
class FromItem extends SqlNode {
  const FromItem({
    required this.joinType,
    required this.ref,
    this.onCondition,
    this.usingColumns,
    required super.start,
    required super.end,
  });

  final JoinType joinType;
  final TableRef ref;
  final ExpressionStub? onCondition;
  final List<String>? usingColumns;
}

class FromClause extends SqlNode {
  const FromClause(this.items, {required super.start, required super.end});

  final List<FromItem> items;
}

/// One `name [(col, ...)] AS (query)` entry in a `WITH` clause.
class CteDefinition extends SqlNode {
  const CteDefinition({
    required this.name,
    required this.nameStart,
    required this.nameEnd,
    this.columnAliases,
    this.query,
    required this.bodyStart,
    required this.bodyEnd,
    required super.start,
    required super.end,
  });

  final String name;
  final int nameStart;
  final int nameEnd;
  final List<String>? columnAliases;

  /// Null when the parenthesized body wasn't recognizably a SELECT/WITH, or
  /// the parser gave up on recursion depth — [bodyStart]/[bodyEnd] are
  /// always populated regardless, as an opaque fallback.
  final SelectStatement? query;
  final int bodyStart;
  final int bodyEnd;
}

class WithClause extends SqlNode {
  const WithClause({
    required this.recursive,
    required this.ctes,
    required super.start,
    required super.end,
  });

  final bool recursive;
  final List<CteDefinition> ctes;
}
