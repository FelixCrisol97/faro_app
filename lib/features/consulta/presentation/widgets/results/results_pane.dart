import 'dart:io';
import 'dart:isolate';

import 'package:csv/csv.dart';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_lucide/flutter_lucide.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../../core/theme/app_radii.dart';
import '../../../../../core/theme/app_spacing.dart';
import '../../../../../core/theme/app_theme.dart';
import '../../../../../data/models/query_result.dart';
import '../../../../../shared/utils/file_paths.dart';
import '../../../../../shared/widgets/app_button.dart';
import '../../../../../shared/widgets/app_tag.dart';
import '../../../../../shared/widgets/centered_scrollable.dart';
import '../../../application/query_tabs_providers.dart';
import 'virtualized_table.dart';

/// The "results ready" state of [ResultsCard] — per-database outcome
/// pills, CSV export, and the results grid itself.
class ResultsPane extends ConsumerStatefulWidget {
  const ResultsPane({
    super.key,
    required this.result,
    this.queryText,
    this.tabId,
    this.released = false,
    required this.onReleaseResults,
    this.paginated = false,
    this.hasMore = false,
    this.loadingMore = false,
    required this.onLoadMore,
    required this.resolveExportResult,
  });
  final QueryResult result;

  /// The exact statement(s) that produced [result] — used only to guess a
  /// table name for the default export filename (best-effort, see
  /// [_defaultExportFileName]); never displayed or executed from here.
  final String? queryText;

  /// Same routing key as `ResultsCard.tabId` — which `exportingCsvProvider`
  /// this pane's export reads/writes (home tab vs. a specific query tab).
  final String? tabId;

  /// Mirrors `QueryRunState.released` — see that field's doc comment.
  final bool released;

  /// Calls `QueryRunActions.releaseResults()` for whichever run notifier
  /// (home or this tab) actually owns [result] — kept as a callback rather
  /// than resolving `runActionsFor(ref, tabId)` in here so this widget
  /// doesn't need its own opinion on tab routing.
  final VoidCallback onReleaseResults;

  /// Mirrors `QueryRunState.paginated`/`hasMore`/`loadingMore` — see those
  /// fields' doc comments. [result] only ever holds the pages fetched so
  /// far when [paginated] is true.
  final bool paginated;
  final bool hasMore;
  final bool loadingMore;

  /// Calls `QueryRunActions.loadMore()` — same callback-not-`ref` shape as
  /// [onReleaseResults].
  final VoidCallback onLoadMore;

  /// Returns the result CSV export should actually write — not just
  /// [result] directly, since a paginated grid may only hold some of the
  /// rows. See `results_card.dart`'s `_resolveExportResult` for what this
  /// actually does (re-fetches the full, unpaginated result when needed).
  final Future<QueryResult> Function() resolveExportResult;

  @override
  ConsumerState<ResultsPane> createState() => _ResultsPaneState();
}

class _ResultsPaneState extends ConsumerState<ResultsPane> {
  @override
  Widget build(BuildContext context) {
    final result = widget.result;
    final typography = context.appTheme.typography;
    final exporting = watchExportingCsv(ref, widget.tabId);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: Wrap(
                spacing: AppSpacing.space1,
                runSpacing: AppSpacing.space1,
                children: [
                  for (final outcome in result.perDatabase)
                    if (outcome.success)
                      AppTag(
                          label: outcome.rowCount == 0 &&
                                  outcome.affectedRows > 0
                              ? '${outcome.databaseName} · ${outcome.affectedRows} filas afectadas'
                              // "≥" while more pages might exist — a real
                              // COUNT(*) is avoided on purpose (see
                              // sql_pagination.dart's paginationPageSize doc
                              // comment), so this is the true count only
                              // once the last page has loaded.
                              : '${outcome.databaseName} · '
                                  '${widget.paginated && widget.hasMore ? '≥' : ''}'
                                  '${outcome.rowCount} filas',
                          variant: AppTagVariant.success)
                    else
                      Tooltip(
                        message:
                            '${outcome.errorMessage ?? 'Error desconocido'}\n(clic para copiar)',
                        child: InkWell(
                          borderRadius: AppRadii.chipRadius,
                          onTap: () => _copyError(context,
                              outcome.errorMessage ?? 'Error desconocido'),
                          child: AppTag(
                            label: outcome.blocked
                                ? '${outcome.databaseName} · Solo lectura'
                                : outcome.databaseName,
                            icon: outcome.blocked
                                ? LucideIcons.lock
                                : LucideIcons.circle_alert,
                            // Green here doesn't mean "it ran" — it means
                            // "the read-only guard did its job correctly",
                            // same color family as the sidebar's read-only
                            // lock icon so 🔒 always reads as "protected",
                            // never as a warning.
                            variant: outcome.blocked
                                ? AppTagVariant.success
                                : AppTagVariant.error,
                          ),
                        ),
                      ),
                ],
              ),
            ),
            // The "Exportando…" indicator itself lives in `ResultsCard`
            // (see `_ExportingBanner`), not here — this pane gets disposed
            // the instant a new query starts running (`results_card.dart`'s
            // status switch), which used to silently kill the export's UI
            // feedback mid-flight even though the file kept writing
            // correctly in the background. The button here just disables
            // itself while an export (from any pane instance, home or this
            // tab) is in flight.
            // Icon-only, dense-row convention (see feedback_icon_only_buttons)
            // — frees this tab's row data without closing it or forgetting
            // what was run; see QueryRunState.released's doc comment for why
            // this exists (tabs are deliberately not `.autoDispose`, so a
            // ~100k-row result otherwise stays resident for as long as the
            // tab does, even while inactive).
            if (!widget.released)
              Padding(
                padding:
                    const EdgeInsets.only(right: AppSpacing.space2),
                child: Tooltip(
                  message: 'Liberar resultados de memoria (no cierra la pestaña)',
                  child: InkWell(
                    borderRadius: const BorderRadius.all(Radius.circular(6)),
                    onTap: exporting || result.rows.isEmpty
                        ? null
                        : widget.onReleaseResults,
                    child: Padding(
                      padding: const EdgeInsets.all(8),
                      child: Icon(LucideIcons.eraser,
                          size: 16,
                          color: exporting || result.rows.isEmpty
                              ? context.appTheme.colors.textMuted
                                  .withValues(alpha: 0.4)
                              : context.appTheme.colors.textMuted),
                    ),
                  ),
                ),
              ),
            AppButton(
              label: 'Exportar CSV',
              icon: LucideIcons.download,
              onPressed: exporting || result.rows.isEmpty
                  ? null
                  : () => _exportCsv(result),
            ),
          ],
        ),
        const SizedBox(height: AppSpacing.space3),
        if (widget.released)
          Expanded(
            child: CenteredScrollable(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(LucideIcons.eraser,
                      size: 28, color: context.appTheme.colors.textMuted),
                  const SizedBox(height: AppSpacing.space2),
                  Text(
                    'Resultados liberados para ahorrar memoria.',
                    style: typography.bodySmall,
                  ),
                  Text(
                    'Vuelve a ejecutar la consulta para verlos de nuevo.',
                    style: typography.bodySmall,
                  ),
                ],
              ),
            ),
          )
        else if (result.rows.isEmpty)
          Text('Sin filas.', style: typography.bodySmall)
        else
          // Expanded, not a fixed height — the results pane itself is now
          // resizable (see consulta_screen.dart's drag handle between the
          // editor and results panes), so the table should fill however
          // much of it the user has dragged open instead of always being
          // exactly a fixed height tall.
          Expanded(
              child: VirtualizedTable(columns: result.columns, rows: result.rows)),
        if (widget.paginated && widget.hasMore && !widget.released)
          Padding(
            padding: const EdgeInsets.only(top: AppSpacing.space2),
            child: _LoadMoreBar(
              rowCount: result.rows.length,
              loading: widget.loadingMore,
              onLoadMore: widget.onLoadMore,
            ),
          ),
      ],
    );
  }

  Future<void> _copyError(BuildContext context, String message) async {
    await Clipboard.setData(ClipboardData(text: message));
    if (!context.mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
          content: Text('Error copiado al portapapeles'),
          duration: Duration(seconds: 2)),
    );
  }

  Future<void> _exportCsv(QueryResult result) async {
    // Captured once, before any `await` — stays valid even if this pane
    // gets disposed mid-export (a new query starting swaps it out, see
    // `results_card.dart`), unlike calling `ref.read` again later would be.
    final setExporting = exportingCsvNotifierFor(ref, widget.tabId);

    // Desktop file_picker: `saveFile` returns the chosen path but doesn't
    // write the file itself (unlike web) — write it ourselves.
    final rawPath = await FilePicker.platform.saveFile(
      fileName: _defaultExportFileName(result, widget.queryText),
      type: FileType.custom,
      allowedExtensions: ['csv'],
    );
    if (rawPath == null) return;
    final path = ensureExtension(rawPath, 'csv');

    setExporting(true);
    try {
      // Not necessarily `result` as-is — a paginated grid only holds the
      // pages loaded so far, but export must always produce the complete
      // result (confirmed with the user); see `results_card.dart`'s
      // `_resolveExportResult` for what this actually does.
      final toWrite = await widget.resolveExportResult();
      // Runs on a fresh throwaway isolate (Isolate.run), not this one — the
      // CSV conversion + write for a ~100k-row export is real synchronous
      // CPU work with no natural `await` point inside its own loop, so
      // doing it in-place on the UI isolate froze the whole app (including
      // painting this very "Exportando…" state) until it finished. See
      // _writeCsvFile's doc comment.
      await Isolate.run(
          () => _writeCsvFile(path, toWrite.columns, toWrite.rows));
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
            content: Text('CSV exportado: ${File(path).uri.pathSegments.last}'),
            duration: const Duration(seconds: 3)),
      );
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
            content: Text('No se pudo exportar el CSV: $e'),
            duration: const Duration(seconds: 4)),
      );
    } finally {
      setExporting(false);
    }
  }
}

/// "Cargar más" affordance under a paginated grid — only shown while
/// `QueryRunState.hasMore` (see [ResultsPane.hasMore]'s doc comment).
/// Stateless: [loading]'s spinner is driven by `QueryRunState.loadingMore`,
/// not local widget state — same reasoning as `exportingCsvProvider`, this
/// needs to keep working across whatever rebuilds happen while a page is
/// in flight, and a plain `bool` prop already gets that for free here since
/// (unlike the export banner) this widget lives inside `ResultsPane` itself
/// and only needs to survive its own rebuilds, not a full pane swap.
class _LoadMoreBar extends StatelessWidget {
  const _LoadMoreBar({
    required this.rowCount,
    required this.loading,
    required this.onLoadMore,
  });

  final int rowCount;
  final bool loading;
  final VoidCallback onLoadMore;

  @override
  Widget build(BuildContext context) {
    final typography = context.appTheme.typography;
    final colors = context.appTheme.colors;
    return Row(
      children: [
        Text('Mostrando $rowCount filas', style: typography.bodySmall),
        const SizedBox(width: AppSpacing.space3),
        if (loading)
          Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              SizedBox(
                width: 14,
                height: 14,
                child: CircularProgressIndicator(
                    strokeWidth: 2, color: colors.textMuted),
              ),
              const SizedBox(width: AppSpacing.space1),
              Text('Cargando…', style: typography.bodySmall),
            ],
          )
        else
          AppButton(
            label: 'Cargar más',
            icon: LucideIcons.chevron_down,
            onPressed: onLoadMore,
          ),
      ],
    );
  }
}

/// Runs on a throwaway [Isolate] spawned by `Isolate.run` — must be a
/// top-level function, and every argument/return value must be
/// isolate-transferable (a `String`, `List<String>`, and
/// `List<List<Object?>>` all are). Streamed row-by-row to disk instead of
/// building the whole CSV as one string in memory first — same motivation
/// as `VirtualizedTable`, this only mattered once result sets started
/// reaching the hundred-thousand-row range.
Future<void> _writeCsvFile(
    String path, List<String> columns, List<List<Object?>> rows) async {
  const converter = ListToCsvConverter();
  final sink = File(path).openWrite();
  try {
    sink.writeln(converter.convert([columns]));
    for (final row in rows) {
      sink.writeln(converter.convert([row]));
    }
  } finally {
    await sink.close();
  }
}

/// Best-effort default export filename — user request, 2026-07-24: type
/// the query once, click "Guardar" without also having to type a filename
/// every time. The table name is a pure heuristic (first `FROM <name>`
/// match in the statement text) — wrong or missing for anything more
/// complex (a JOIN picks its first table, a subquery/CTE-only query may
/// find nothing) is fine, since it's only ever a starting point the save
/// dialog still lets the user rename.
///
/// User-requested 2026-08-05 (client-reported from production use): a
/// folder full of exported CSVs all named `faro_export_20260805_1430.csv`
/// gave no way to tell them apart without opening each one — the
/// destination database (and, since two bodegas can share a database
/// name, its real host/IP too) now rides along in the filename, for a
/// single-target export exactly as for one selected out of "Consulta
/// masiva". A true masiva export (several databases merged into one
/// result) gets a count instead of every alias — listing all of them
/// would make the filename unbounded for a large selection.
///
/// Real gap found 2026-08-06 (same client, testing in production): the
/// 2026-08-05 round above only ever surfaced the table name — the actual
/// ask was for the WHERE clause's filter values too (`SELECT * FROM
/// facturas WHERE facturaid = 123 AND cliente = 456` should read as
/// `..._facturas_facturaid_123_cliente_456_...`, not just `..._facturas_
/// ...`), which never got built. [_whereConditionKeywords] covers that —
/// same "best-effort, not exhaustive" philosophy as the table-name regex:
/// only plain `column = value` equality checks (a real number or quoted
/// string) are recognized, capped to the first 3 found so a WHERE clause
/// with many conditions doesn't produce an unbounded filename.
String _defaultExportFileName(QueryResult result, String? queryText) {
  final tableMatch = queryText == null
      ? null
      : RegExp(r'\bfrom\s+"?([a-zA-Z_][\w.]*)"?', caseSensitive: false)
          .firstMatch(queryText);
  final table = tableMatch?.group(1);
  final conditions = _whereConditionKeywords(queryText);

  final now = DateTime.now();
  String two(int n) => n.toString().padLeft(2, '0');
  final stamp =
      '${now.year}${two(now.month)}${two(now.day)}_${two(now.hour)}${two(now.minute)}';

  final singleOutcome =
      result.perDatabase.length == 1 ? result.perDatabase.single : null;
  final destination = singleOutcome == null
      ? null
      : '${_sanitizeForFileName(singleOutcome.databaseName)}_'
          '${_sanitizeForFileName(singleOutcome.databaseHost)}';

  final parts = [
    'faro_export',
    if (table != null) table,
    ...conditions,
    if (destination != null) destination,
    if (result.isMultiDatabase) 'masiva_${result.databasesQueried}bd',
    stamp,
  ];
  return '${parts.join('_')}.csv';
}

/// The WHERE clause's own text, stopping at whatever comes after it
/// (`GROUP BY`/`ORDER BY`/`HAVING`/`LIMIT`/`OFFSET`, or end of string) —
/// `dotAll` because a real query is routinely spread across several lines.
final _whereClausePattern = RegExp(
  r'\bwhere\b(.*?)(?:\bgroup\s+by\b|\border\s+by\b|\bhaving\b|\blimit\b|\boffset\b|$)',
  caseSensitive: false,
  dotAll: true,
);

/// `column = 123` / `column = 'texto'` / `column = "texto"` — deliberately
/// only plain equality: the leading `\s*=\s*` (nothing else allowed
/// between the column name and the `=`) already excludes `>=`/`<=`/`!=`/
/// `<>` on its own, and a join condition like `a.id = b.id` fails to match
/// too (`b.id` isn't a bare number or a quoted string), so this only ever
/// picks up genuine filter values, not comparison operators or column-to-
/// column joins.
final _whereConditionPattern = RegExp(
  r'''([a-zA-Z_][\w]*)\s*=\s*(?:'([^']*)'|"([^"]*)"|(\d+(?:\.\d+)?))''',
);

List<String> _whereConditionKeywords(String? queryText) {
  if (queryText == null) return const [];
  final whereClause = _whereClausePattern.firstMatch(queryText)?.group(1);
  if (whereClause == null) return const [];

  final keywords = <String>[];
  for (final m in _whereConditionPattern.allMatches(whereClause)) {
    if (keywords.length >= 3) break;
    final column = m.group(1)!;
    final value = m.group(2) ?? m.group(3) ?? m.group(4);
    if (value == null || value.isEmpty) continue;
    keywords.add('${column}_${_sanitizeForFileName(value)}');
  }
  return keywords;
}

/// Replaces characters a Windows filename can't contain — plus `.`, purely
/// for readability (an IP's dots would otherwise sit right next to the
/// `_stamp.csv` suffix, easy to misread as more of the extension) — with
/// `-`. Mainly for [DatabaseQueryOutcome.databaseHost]'s `192.168.1.10
/// :puerto` shape, but applied to the (free-form, user-typed) database
/// alias too for safety.
String _sanitizeForFileName(String value) =>
    value.replaceAll(RegExp(r'[\\/:*?"<>|.]'), '-');
