import 'package:flutter/foundation.dart' show listEquals;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_lucide/flutter_lucide.dart';
import 'package:two_dimensional_scrollables/two_dimensional_scrollables.dart';

import '../../../../../core/theme/app_theme.dart';
import '../../../../../shared/widgets/context_menu_label.dart';

/// Replaces the stock `DataTable` (removed 2026-07-18) — that widget builds
/// every row as a real widget up front with no virtualization, which gets
/// visibly slow once a result set reaches the thousand-row range (verified
/// against a Docker test env seeded with 10k+ rows per bodega). A first cut
/// of this (`ListView.builder` nested inside a horizontal
/// `SingleChildScrollView`) virtualized rows fine but couldn't give the
/// vertical scrollbar a fixed position in the visible viewport — since the
/// `ListView` lived inside the horizontally-scrolled content, its Scrollbar
/// decorated the edge of the *full column width*, which is off-screen for
/// any wide result set. `TableView` (the Flutter team's own
/// `two_dimensional_scrollables` package) is a real 2D-virtualized grid, so
/// both axes get their own always-on-screen `Scrollbar`, same as a native
/// grid control. The header is `pinnedRowCount: 1` (row 0), so it stays put
/// while the body scrolls under it.
class VirtualizedTable extends StatefulWidget {
  const VirtualizedTable({super.key, required this.columns, required this.rows});

  final List<String> columns;

  /// Positional, same order as [columns] — see
  /// `RawQueryResult.rows`'s doc comment for why this isn't a
  /// `Map<String,Object?>` per row.
  final List<List<Object?>> rows;

  static const _rowHeight = 36.0;
  // Tall enough for two lines: the column name (as before) plus the
  // inferred type label under it.
  static const _headerHeight = 52.0;
  static const _minColumnWidth = 90.0;
  static const _maxColumnWidth = 260.0;
  static const _charWidth = 7.5;

  /// Bounds for a manual drag-resize (via [_ColumnResizeHandle]) — wider
  /// than the auto-sized [_minColumnWidth]/[_maxColumnWidth] range above,
  /// which only bounds the *initial guess* from sampled content. The user
  /// asked to be able to make a column "más estirada o no" than that
  /// guess, in either direction.
  static const _manualMinColumnWidth = 40.0;
  static const _manualMaxColumnWidth = 900.0;

  /// How far down (in px) before the "scroll to top" button appears —
  /// no point showing it over the first couple of screenfuls.
  static const _scrollToTopThreshold = 400.0;

  @override
  State<VirtualizedTable> createState() => _VirtualizedTableState();
}

class _VirtualizedTableState extends State<VirtualizedTable> {
  final _verticalController = ScrollController();
  final _horizontalController = ScrollController();
  bool _showScrollToTop = false;
  late List<double> _widths;
  late List<String> _types;

  @override
  void initState() {
    super.initState();
    _widths = _columnWidths();
    _types = _columnTypes();
    _verticalController.addListener(() {
      final show = _verticalController.hasClients &&
          _verticalController.offset > VirtualizedTable._scrollToTopThreshold;
      if (show != _showScrollToTop) setState(() => _showScrollToTop = show);
    });
  }

  @override
  void didUpdateWidget(covariant VirtualizedTable oldWidget) {
    super.didUpdateWidget(oldWidget);
    // Real bug fixed 2026-08-03 (AUDITORIA_CODIGO.md): resetting `_widths`
    // whenever `rows` isn't `identical` used to wipe out a manually
    // drag-resized column every time "Cargar más" appended a page —
    // `loadNextPage` (`consulta_providers.dart`) always builds a brand new
    // `[...existing.rows, ...newRows]` list, so identity never survives a
    // page load even though the columns (and therefore the widths that
    // make sense for them) haven't changed at all. Comparing `columns` by
    // content instead of `rows` by identity distinguishes "more pages of
    // the same query" (columns unchanged — keep the user's widths) from
    // "a genuinely different query ran" (columns changed — recompute).
    // `_types` still recomputes on any row change regardless — it's a
    // cheap 30-row sample and, unlike widths, isn't something the user
    // ever manually overrides.
    final columnsChanged = !listEquals(oldWidget.columns, widget.columns);
    if (columnsChanged) {
      _widths = _columnWidths();
    }
    if (columnsChanged || !identical(oldWidget.rows, widget.rows)) {
      _types = _columnTypes();
    }
  }

  @override
  void dispose() {
    _verticalController.dispose();
    _horizontalController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = context.appTheme.colors;
    final typography = context.appTheme.typography;

    return SizedBox(
      width: double.infinity,
      child: Stack(
        children: [
          // Two independent Scrollbars around one 2D scrollable: each is
          // told (via notificationPredicate) to only react to its own axis,
          // the standard pattern for giving a TwoDimensionalScrollView a
          // persistent scrollbar on both edges at once.
          Scrollbar(
            controller: _verticalController,
            thumbVisibility: true,
            trackVisibility: true,
            notificationPredicate: (notification) =>
                notification.metrics.axis == Axis.vertical,
            child: Scrollbar(
              controller: _horizontalController,
              thumbVisibility: true,
              trackVisibility: true,
              notificationPredicate: (notification) =>
                  notification.metrics.axis == Axis.horizontal,
              child: TableView.builder(
                pinnedRowCount: 1,
                verticalDetails:
                    ScrollableDetails.vertical(controller: _verticalController),
                horizontalDetails: ScrollableDetails.horizontal(
                    controller: _horizontalController),
                columnCount: widget.columns.length,
                columnBuilder: (index) =>
                    TableSpan(extent: FixedTableSpanExtent(_widths[index])),
                rowCount: widget.rows.length + 1,
                rowBuilder: (index) => TableSpan(
                  extent: FixedTableSpanExtent(index == 0
                      ? VirtualizedTable._headerHeight
                      : VirtualizedTable._rowHeight),
                  backgroundDecoration: TableSpanDecoration(
                    border: TableSpanBorder(
                      trailing: BorderSide(
                        color: index == 0
                            ? colors.border
                            : colors.border.withValues(alpha: 0.5),
                      ),
                    ),
                  ),
                ),
                cellBuilder: (context, vicinity) {
                  if (vicinity.row == 0) {
                    return TableViewCell(
                      child: Stack(
                        children: [
                          Padding(
                            padding: const EdgeInsets.symmetric(
                                horizontal: 8, vertical: 6),
                            child: Column(
                              mainAxisAlignment: MainAxisAlignment.center,
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  widget.columns[vicinity.column],
                                  overflow: TextOverflow.ellipsis,
                                  style: typography.caption,
                                ),
                                Text(
                                  _types[vicinity.column],
                                  overflow: TextOverflow.ellipsis,
                                  style: typography.caption.copyWith(
                                    fontSize: 10,
                                    fontWeight: FontWeight.w500,
                                    color:
                                        colors.textMuted.withValues(alpha: 0.7),
                                  ),
                                ),
                              ],
                            ),
                          ),
                          // Drag to widen/narrow just this column — user
                          // request: sampled auto-widths (_columnWidths
                          // below) are a starting guess, not final.
                          Positioned(
                            top: 0,
                            right: 0,
                            bottom: 0,
                            child: _ColumnResizeHandle(
                              onDrag: (dx) => setState(() {
                                _widths[vicinity.column] =
                                    (_widths[vicinity.column] + dx).clamp(
                                        VirtualizedTable._manualMinColumnWidth,
                                        VirtualizedTable
                                            ._manualMaxColumnWidth);
                              }),
                            ),
                          ),
                        ],
                      ),
                    );
                  }

                  final rowIndex = vicinity.row - 1;
                  final text = '${widget.rows[rowIndex][vicinity.column] ?? ''}';
                  // Declared fresh per cellBuilder call, same as the schema
                  // tree's per-row tapPosition — each call is its own
                  // closure scope, so this doesn't leak across cells.
                  Offset? tapPosition;
                  return TableViewCell(
                    child: GestureDetector(
                      behavior: HitTestBehavior.opaque,
                      onSecondaryTapDown: (details) =>
                          tapPosition = details.globalPosition,
                      onSecondaryTap: () => _showCellMenu(
                          context, tapPosition!, rowIndex, vicinity.column),
                      child: Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 8),
                        child: Align(
                          alignment: Alignment.centerLeft,
                          child: Text(
                            text,
                            overflow: TextOverflow.ellipsis,
                            style: typography.bodySmall,
                          ),
                        ),
                      ),
                    ),
                  );
                },
              ),
            ),
          ),
          if (_showScrollToTop)
            Positioned(
              right: 12,
              bottom: 12,
              child: Material(
                color: colors.accent.base,
                elevation: 4,
                shape: const CircleBorder(),
                child: InkWell(
                  customBorder: const CircleBorder(),
                  onTap: () => _verticalController.animateTo(
                    0,
                    duration: const Duration(milliseconds: 300),
                    curve: Curves.easeOut,
                  ),
                  child: const Padding(
                    padding: EdgeInsets.all(8),
                    child: Icon(LucideIcons.arrow_up,
                        size: 16, color: Colors.white),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }

  /// Sized from the header text plus up to the first 30 rows — enough to
  /// look reasonable without scanning a result set that could have
  /// thousands of rows just to measure text.
  List<double> _columnWidths() {
    final sample = widget.rows.take(30).toList();
    return [
      for (var i = 0; i < widget.columns.length; i++)
        _widthFor(widget.columns[i], i, sample)
    ];
  }

  double _widthFor(String column, int columnIndex, List<List<Object?>> sample) {
    var maxLen = column.length;
    for (final row in sample) {
      final len = '${row[columnIndex] ?? ''}'.length;
      if (len > maxLen) maxLen = len;
    }
    return (maxLen * VirtualizedTable._charWidth).clamp(
        VirtualizedTable._minColumnWidth, VirtualizedTable._maxColumnWidth);
  }

  /// Same 30-row sample as `_columnWidths` — there's no real column-type
  /// metadata to read (Postgres has it, SQL Server's driver doesn't; see
  /// the plan doc), so both engines get the same by-value inference instead.
  List<String> _columnTypes() {
    final sample = widget.rows.take(30).toList();
    return [
      for (var i = 0; i < widget.columns.length; i++) _typeFor(i, sample)
    ];
  }

  String _typeFor(int columnIndex, List<List<Object?>> sample) {
    for (final row in sample) {
      final value = row[columnIndex];
      if (value == null) continue;
      if (value is int) return 'entero';
      if (value is double || value is num) return 'decimal';
      if (value is bool) return 'booleano';
      if (value is DateTime) return 'fecha/hora';
      if (value is String) return 'texto';
      return '?';
    }
    return '?';
  }

  Future<void> _showCellMenu(BuildContext context, Offset globalPosition,
      int rowIndex, int columnIndex) async {
    final action = await showPositionedMenu<_CellMenuAction>(
      context,
      globalPosition,
      const [
        PopupMenuItem(
            value: _CellMenuAction.copyCell,
            child: ContextMenuLabel(LucideIcons.copy, 'Copiar celda')),
        PopupMenuItem(
            value: _CellMenuAction.copyRow,
            child: ContextMenuLabel(LucideIcons.rows_3, 'Copiar fila')),
        PopupMenuItem(
            value: _CellMenuAction.copyTable,
            child: ContextMenuLabel(LucideIcons.table, 'Copiar tabla completa')),
      ],
    );
    if (action == null || !mounted) return;
    switch (action) {
      case _CellMenuAction.copyCell:
        await _copy(_cellText(rowIndex, columnIndex), 'Celda copiada al portapapeles');
      case _CellMenuAction.copyRow:
        await _copy(_rowText(rowIndex), 'Fila copiada al portapapeles');
      case _CellMenuAction.copyTable:
        await _copy(_tableText(), 'Tabla copiada al portapapeles');
    }
  }

  String _cellText(int rowIndex, int columnIndex) {
    return '${widget.rows[rowIndex][columnIndex] ?? ''}';
  }

  // Tab-separated: pastes as real columns into Excel/Sheets instead of one
  // run-on string, same convention as the CSV export just above.
  String _rowText(int rowIndex) {
    final row = widget.rows[rowIndex];
    return row.map((v) => '${v ?? ''}').join('\t');
  }

  String _tableText() {
    final header = widget.columns.join('\t');
    final body =
        widget.rows.map((row) => row.map((v) => '${v ?? ''}').join('\t'));
    return [header, ...body].join('\n');
  }

  Future<void> _copy(String text, String message) async {
    await Clipboard.setData(ClipboardData(text: text));
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message), duration: const Duration(seconds: 2)),
    );
  }
}

enum _CellMenuAction { copyCell, copyRow, copyTable }

/// Drag handle at a header cell's right edge to manually widen/narrow that
/// one column — same hover-highlight + wider-than-visible hit target
/// pattern as the sidebar's own panel-width resize handle
/// (`widgets/sidebar/server_sidebar.dart`'s `_ResizeHandle`), just not
/// shared with it: that one drags a whole panel horizontally with a
/// vertical-line look, this one lives inside a single grid header cell —
/// different enough hosts that forcing one shared widget over both would
/// add more parameterization than it'd save.
class _ColumnResizeHandle extends StatefulWidget {
  const _ColumnResizeHandle({required this.onDrag});

  final ValueChanged<double> onDrag;

  @override
  State<_ColumnResizeHandle> createState() => _ColumnResizeHandleState();
}

class _ColumnResizeHandleState extends State<_ColumnResizeHandle> {
  bool _hovering = false;

  @override
  Widget build(BuildContext context) {
    final colors = context.appTheme.colors;
    return MouseRegion(
      cursor: SystemMouseCursors.resizeLeftRight,
      onEnter: (_) => setState(() => _hovering = true),
      onExit: (_) => setState(() => _hovering = false),
      child: GestureDetector(
        behavior: HitTestBehavior.translucent,
        onHorizontalDragUpdate: (details) => widget.onDrag(details.delta.dx),
        child: SizedBox(
          width: 8,
          child: Center(
            child: Container(
              width: 2,
              color: _hovering ? colors.accent.base : Colors.transparent,
            ),
          ),
        ),
      ),
    );
  }
}
