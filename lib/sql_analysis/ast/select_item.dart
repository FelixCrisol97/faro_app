import 'expression_stub.dart';
import 'sql_node.dart';

/// One entry in a SELECT list: `expr`, `expr AS alias`, `alias sin AS`
/// (only detected for the unambiguous case — see
/// `SqlParser._parseSelectItem`'s doc comment), `*`, or `alias.*`.
class SelectItem extends SqlNode {
  const SelectItem({
    required this.isStar,
    this.starQualifier,
    this.expression,
    this.alias,
    this.aliasStart,
    this.aliasEnd,
    required super.start,
    required super.end,
  });

  final bool isStar;

  /// `alias` in `alias.*` — null for a bare `*`.
  final String? starQualifier;

  /// Null exactly when [isStar] is true.
  final ExpressionStub? expression;

  final String? alias;
  final int? aliasStart;
  final int? aliasEnd;
}

/// One `column = expr` entry in an UPDATE's SET list — [column] has its own
/// position (unlike the opaque [value]) since it's what scope
/// resolution/autocomplete cares about.
class SetItem extends SqlNode {
  const SetItem({
    required this.column,
    required this.columnStart,
    required this.columnEnd,
    required this.value,
    required super.start,
    required super.end,
  });

  final String column;
  final int columnStart;
  final int columnEnd;
  final ExpressionStub value;
}

class OrderByItem extends SqlNode {
  const OrderByItem({
    required this.expression,
    this.descending,
    required super.start,
    required super.end,
  });

  final ExpressionStub expression;

  /// Null when unspecified (defaults to ascending).
  final bool? descending;
}
