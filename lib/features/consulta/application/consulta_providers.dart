import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:uuid/uuid.dart';

import '../../../data/models/history_entry.dart';
import '../../../data/models/query_result.dart';
import '../../../data/providers/core_providers.dart';
import '../../../data/providers/servers_providers.dart';
import '../../../data/repositories/query_execution_service.dart';
import '../../historial/application/historial_providers.dart';
import 'sql_pagination.dart';
import 'sql_statement_resolver.dart';

const _uuid = Uuid();

/// Implemented by both the single global [QueryEditorNotifier] (the main
/// window's always-there editor) and `query_tabs_providers.dart`'s
/// per-tab `TabQueryEditorNotifier` — lets `editorActionsFor(ref, tabId)`
/// return one common type regardless of which one it actually resolved to,
/// so callers (`toolbar_card.dart`, `sql_editor.dart`) don't need an
/// `if (tabId == null) ... else ...` at every call site.
abstract class QueryEditorActions {
  void setText(String text);
  void syncFromController(
      {required String text, String? selectedText, int? cursorOffset});
  void loadText(String text, {String? filePath, bool fromDisk = false});

  /// Marks the current [QueryEditorState.text] as matching what's now on
  /// disk at [QueryEditorState.filePath] — called right after "Guardar"
  /// (`toolbar_card.dart`) successfully writes it, so
  /// [QueryEditorState.hasUnsavedChanges] goes back to false until the next
  /// edit.
  void markSaved();
}

/// Same idea as [QueryEditorActions], for the run/cancel side
/// ([QueryRunNotifier] / `TabQueryRunNotifier`).
abstract class QueryRunActions {
  Future<void> run();
  void cancel();

  /// Discards [QueryRunState.result]'s rows to free memory, without
  /// closing the tab or forgetting what was run — see
  /// [QueryRunState.released]'s doc comment. A no-op unless [QueryRunState
  /// .status] is [QueryRunStatus.done].
  void releaseResults();

  /// Fetches the next page and appends it to [QueryRunState.result]'s rows
  /// — see [QueryRunState.paginated]'s doc comment. A no-op unless
  /// [QueryRunState.paginated] and [QueryRunState.hasMore] are both true and
  /// no page is already loading.
  Future<void> loadMore();
}

/// SQL editor contents + the user's current text selection (README.md
/// "State management": "SQL editor text + cursor/selection").
/// [selectedText] is null/empty whenever there's no active selection.
/// [cursorOffset] is the caret position even when there's no drag-selection
/// — no longer used to pick a statement to run (see
/// `sql_statement_resolver.dart`'s 2026-07-22 doc comment: no selection now
/// always runs everything), kept only in case a future feature needs "where
/// is the caret right now" for something else.
///
/// [filePath] tracks which file (if any) the current [text] came from via
/// "Cargar", so "Guardar" (`toolbar_card.dart`) can write back to it
/// directly instead of always prompting a new save-file dialog like
/// "Exportar SQL" does. Cleared (stays null) whenever text arrives from
/// anywhere else — Favoritos "Usar", Historial "Reusar", or a schema
/// context-menu script generation — since that text no longer corresponds
/// to any file on disk. Survives typing (`syncFromController`) and
/// "Formatear" (both pass the current [filePath] straight through) — the
/// file is still "the same file, with unsaved changes" through either.
///
/// [savedText] is [text] as of the last load-from-disk or successful save —
/// only meaningful while [filePath] is set. [hasUnsavedChanges] compares
/// the two so "Guardar" (`toolbar_card.dart`) can disable itself once
/// there's nothing new to write, and so the toolbar can show a dirty
/// indicator next to the open file's name.
class QueryEditorState {
  const QueryEditorState(
      {this.text = '',
      this.selectedText,
      this.cursorOffset,
      this.filePath,
      this.savedText});

  final String text;
  final String? selectedText;
  final int? cursorOffset;
  final String? filePath;
  final String? savedText;

  bool get hasUnsavedChanges => filePath != null && text != savedText;

  QueryEditorState copyWith(
      {String? text, String? Function()? selectedText, int? cursorOffset}) {
    return QueryEditorState(
      text: text ?? this.text,
      selectedText: selectedText != null ? selectedText() : this.selectedText,
      cursorOffset: cursorOffset ?? this.cursorOffset,
      filePath: filePath,
      savedText: savedText,
    );
  }
}

/// Real duplication fixed 2026-08-04 (AUDITORIA_CODIGO.md): [QueryEditorNotifier]
/// (this file, the main window's always-there editor) and
/// `query_tabs_providers.dart`'s per-tab `TabQueryEditorNotifier` used to
/// repeat these 4 method bodies verbatim — both implement
/// [QueryEditorActions] but extend different Riverpod Notifier base classes
/// ([Notifier] vs [FamilyNotifier]), so the classes themselves can't be
/// merged. Each body is pure state-in/state-out, though, so pulling that
/// part into free functions both notifiers call removes the duplication
/// without touching the Notifier boundary — same pattern already used for
/// [QueryRunActions] below (`runStatementAndRecord`, `releasedRunState`,
/// `applyPaginationCap`, `preparePagination`).
QueryEditorState setTextState(QueryEditorState state, String text) =>
    state.copyWith(text: text, selectedText: () => null);

/// Called on every `TextEditingController` change (typing *or* just moving
/// the selection) — updates all three in one go so a selection-only change
/// doesn't get misread as a text edit that clears the selection. Not built
/// via `copyWith` (its `cursorOffset`/`selectedText` can't be explicitly
/// cleared back to null through that method's `??` pattern) — [filePath] is
/// threaded through by hand instead, to stay attached across edits to the
/// file that was loaded.
QueryEditorState syncFromControllerState(QueryEditorState state,
    {required String text, String? selectedText, int? cursorOffset}) {
  return QueryEditorState(
    text: text,
    selectedText: selectedText,
    cursorOffset: cursorOffset,
    filePath: state.filePath,
    savedText: state.savedText,
  );
}

/// Used by "Cargar" (file picker, passes [filePath] and `fromDisk: true` —
/// the text really did just come from disk, so [QueryEditorState.savedText]
/// syncs to it, nothing unsaved yet), "Formatear" (passes the current
/// [filePath] through unchanged but NOT `fromDisk` — reformatting is a new
/// in-memory edit relative to whatever's still on disk, so
/// [QueryEditorState.savedText] is deliberately left alone, making the
/// reformatted text show up as unsaved same as any other edit), Favoritos
/// "Usar" and Historial "Reusar" (neither passes a [filePath], clearing it —
/// that text isn't tied to any file, so nothing to track as saved/unsaved
/// either).
QueryEditorState loadTextState(QueryEditorState state, String text,
    {String? filePath, bool fromDisk = false}) {
  return QueryEditorState(
    text: text,
    filePath: filePath,
    savedText: filePath == null ? null : (fromDisk ? text : state.savedText),
  );
}

QueryEditorState markSavedState(QueryEditorState state) => QueryEditorState(
      text: state.text,
      selectedText: state.selectedText,
      cursorOffset: state.cursorOffset,
      filePath: state.filePath,
      savedText: state.text,
    );

class QueryEditorNotifier extends Notifier<QueryEditorState>
    implements QueryEditorActions {
  @override
  QueryEditorState build() => const QueryEditorState();

  @override
  void setText(String text) => state = setTextState(state, text);

  @override
  void syncFromController(
          {required String text, String? selectedText, int? cursorOffset}) =>
      state = syncFromControllerState(state,
          text: text, selectedText: selectedText, cursorOffset: cursorOffset);

  @override
  void loadText(String text, {String? filePath, bool fromDisk = false}) =>
      state = loadTextState(state, text, filePath: filePath, fromDisk: fromDisk);

  @override
  void markSaved() => state = markSavedState(state);
}

final sqlEditorProvider =
    NotifierProvider<QueryEditorNotifier, QueryEditorState>(
  QueryEditorNotifier.new,
);

enum QueryRunStatus { idle, running, done }

class QueryRunState {
  const QueryRunState({
    this.status = QueryRunStatus.idle,
    this.result,
    this.currentStatement,
    this.totalStatements,
    this.queryText,
    this.released = false,
    this.paginated = false,
    this.hasMore = false,
    this.loadingMore = false,
    this.pagingTarget,
    this.pagingOriginalStatement,
  });

  final QueryRunStatus status;
  final QueryResult? result;

  /// True when this run's last statement was recognized as a plain
  /// `SELECT`/`WITH` with no `LIMIT`/`OFFSET`/`TOP` of its own
  /// (`sql_pagination.dart`'s `isPaginableSelect`) and wrapped to fetch one
  /// page at a time instead of the whole result set — 2026-08-02, the RAM
  /// win pagination exists for: a query that could return 100k+ rows no
  /// longer has to materialize all of them just to show the first screenful.
  /// Only ever true for a single-target run (`QueryExecutionService`
  /// doesn't get involved in `origen_bd` merging here — per-database paging
  /// offsets were judged out of scope for v1). [result] holds only the rows
  /// fetched so far; [hasMore] says whether another page might exist.
  final bool paginated;

  /// Only meaningful when [paginated]. Derived without a separate
  /// `COUNT(*)`: each page is actually fetched as `paginationPageSize + 1`
  /// rows — getting back more than [paginationPageSize] means there's at
  /// least one more page, and the extra probe row is dropped before
  /// display.
  final bool hasMore;

  /// A `loadMore()` fetch is in flight. Deliberately NOT modeled as
  /// [QueryRunStatus.running] — that would make `results_card.dart` swap
  /// `ResultsPane` out for `_RunningState`, hiding the pages already
  /// loaded. Surfaced instead as a small spinner on the "Cargar más"
  /// affordance itself (`results_pane.dart`'s `_LoadMoreBar`).
  final bool loadingMore;

  /// The exact target this run queried, captured at run time — only set
  /// when [paginated]. `loadMore()` and CSV export's full re-fetch
  /// (`results_card.dart`'s `resolveExportResult`) both use this instead of
  /// re-reading whatever's currently selected in the sidebar, so they keep
  /// talking to the same database even if the user changes that selection
  /// afterward.
  final QueryTarget? pagingTarget;

  /// The last statement's original text, *before* being wrapped in a
  /// `LIMIT`/`OFFSET` page — only set when [paginated]. Used to build the
  /// next page's wrapped SQL and, for CSV export, to re-run the real query
  /// unwrapped so the export isn't capped to whatever's currently loaded.
  final String? pagingOriginalStatement;

  /// True once the user explicitly discarded [result]'s rows via "Liberar
  /// resultados" (2026-08-01: tabs are deliberately not `.autoDispose`, see
  /// `query_tabs_providers.dart`'s doc comments — a tab with a ~100k-row
  /// result stays fully resident in memory for as long as the tab exists,
  /// even while inactive; this lets the user reclaim that memory for a tab
  /// they're done looking at without closing it outright). [result] is kept
  /// (not nulled) with [QueryResult.rows] emptied but
  /// [QueryResult.perDatabase]/[QueryResult.columns] intact, so the
  /// per-database outcome pills still render — only the actual row data is
  /// gone. Re-running the query (still possible — [status] stays [QueryRunStatus.done])
  /// clears this back to false.
  final bool released;

  /// The exact statement(s) that produced [result] — only set once
  /// [status] is [QueryRunStatus.done]. Used to build a sensible default
  /// CSV export filename (`results_pane.dart`) without having to re-read
  /// the editor's *current* text, which may have already changed since
  /// this run finished.
  final String? queryText;

  /// Live progress through a multi-statement run (`currentStatement` is
  /// 1-based — "on statement N of `totalStatements`"). Only ever populated
  /// when there's exactly one selected target and more than one statement
  /// — with several databases running in parallel, each can be on a
  /// different statement at the same instant, so a single "N of M" number
  /// would be misleading; that case just shows the plain running message,
  /// same as it always did. Null once [status] leaves [QueryRunStatus.running].
  final int? currentStatement;
  final int? totalStatements;

  bool get isRunning => status == QueryRunStatus.running;

  /// Only touches the fields explicitly passed — every other constructor
  /// call site in this file builds a `QueryRunState` fresh instead (a new
  /// `run()` deliberately starts from a blank slate, not a copy of
  /// whatever the previous run left behind in `paginated`/`released`/etc.).
  /// This exists specifically for [applyPaginationCap]/`loadNextPage`,
  /// which only ever adjust a handful of fields on an already-`done` state.
  QueryRunState copyWith({
    QueryResult? result,
    bool? paginated,
    bool? hasMore,
    bool? loadingMore,
    QueryTarget? pagingTarget,
    String? pagingOriginalStatement,
  }) =>
      QueryRunState(
        status: status,
        result: result ?? this.result,
        currentStatement: currentStatement,
        totalStatements: totalStatements,
        queryText: queryText,
        released: released,
        paginated: paginated ?? this.paginated,
        hasMore: hasMore ?? this.hasMore,
        loadingMore: loadingMore ?? this.loadingMore,
        pagingTarget: pagingTarget ?? this.pagingTarget,
        pagingOriginalStatement:
            pagingOriginalStatement ?? this.pagingOriginalStatement,
      );
}

/// Runs [statements] (usually one; a selection spanning several runs them
/// in order, per database — see `QueryExecutionService.run`'s doc comment)
/// against [targets] and records it in Historial — the part of a run
/// that's identical whether it's driven by the main window's global
/// [QueryRunNotifier] or a tab's `TabQueryRunNotifier`
/// (`query_tabs_providers.dart`). Deliberately excludes resolving the
/// statement(s) and the transition to [QueryRunStatus.running] — those stay
/// in each notifier's own `run()`, before calling this, so "no runnable
/// statement" still never touches `running` at all, exactly like before
/// this was extracted.
///
/// [onProgress], if given, is only ever forwarded to `QueryExecutionService
/// .run` when [targets] has exactly one entry — see [QueryRunState
/// .currentStatement]'s doc comment for why several targets can't share one
/// meaningful progress number.
///
/// [displayStatements], if given, is what Historial records and what
/// [QueryRunState.queryText] becomes — defaults to [statements] itself, and
/// only ever differs when the caller wrapped its last statement in a
/// pagination `LIMIT`/`OFFSET` (`sql_pagination.dart`): Historial's
/// "Reusar" and the CSV export filename guess should see the user's real,
/// original SQL, never the internal `_faro_page` wrapper.
///
/// [paginationCap], if given, is [paginationPageSize] whenever the caller's
/// `preparePagination` wrapped the last statement into a page-0 fetch —
/// real bug fixed 2026-08-03 (AUDITORIA_CODIGO.md): [result] at this point
/// still holds the *uncapped* rows (including the one extra "is there
/// another page" probe row `wrapForPage` always asks for), but
/// `applyPaginationCap` — which trims that back down to [paginationCap] —
/// only runs in the caller, *after* this function (and the Historial entry
/// it records) has already returned. Recording the uncapped count meant
/// Historial could show `paginationPageSize + 1` rows for a run whose grid
/// only ever showed [paginationPageSize]. Passing the same cap in here
/// keeps the two in sync without this function needing to know anything
/// about pagination beyond the one number.
Future<QueryRunState> runStatementAndRecord({
  required Ref ref,
  required List<QueryTarget> targets,
  required List<String> statements,
  required CancellationToken cancellationToken,
  void Function(int completed, int total)? onProgress,
  List<String>? displayStatements,
  int? paginationCap,
}) async {
  final result = await ref.read(queryExecutionServiceProvider).run(
        targets: targets,
        statements: statements,
        resolveCredentials: ref.read(credentialsRepositoryProvider).resolve,
        cancellationToken: cancellationToken,
        onProgress: targets.length == 1 ? onProgress : null,
      );

  if (result.cancelled) {
    return const QueryRunState(status: QueryRunStatus.idle);
  }

  final shown = displayStatements ?? statements;
  ref.read(historyProvider.notifier).add(historyEntryFor(targets, shown, result,
      rowCountCap: paginationCap));
  return QueryRunState(
      status: QueryRunStatus.done, result: result, queryText: shown.join('\n'));
}

HistoryEntry historyEntryFor(
  List<QueryTarget> targets,
  List<String> statements,
  QueryResult result, {
  int? rowCountCap,
}) {
  final status = result.perDatabase.every((o) => o.success)
      ? HistoryStatus.success
      : HistoryStatus.partial;
  // One server involved (the common case) keeps today's exact look (its
  // real name); several collapse into a count — HistoryEntry stays a
  // plain String rather than a real serverId list either way.
  final serversInvolved = {
    for (final t in targets) (t.server?.id ?? ''): (t.server?.name ?? 'Sin grupo')
  };
  final singleServer =
      serversInvolved.length == 1 ? serversInvolved.entries.first : null;
  return HistoryEntry(
    id: _uuid.v4(),
    timestamp: DateTime.now(),
    // Joined back into one block (not just the last statement) so
    // Historial's "Reusar" restores everything that was actually run, not
    // only its final instruction.
    queryText: statements.join('\n'),
    serverId: singleServer?.key ?? '',
    serverName: singleServer?.value ?? '${serversInvolved.length} servidores',
    databaseCount: result.databasesQueried,
    rowCount: rowCountCap != null && result.rows.length > rowCountCap
        ? rowCountCap
        : result.rows.length,
    status: status,
  );
}

/// Shared by [QueryRunNotifier.releaseResults] and `TabQueryRunNotifier
/// .releaseResults` — a no-op (returns [current] unchanged) unless
/// [QueryRunState.status] is [QueryRunStatus.done] with a non-null
/// [QueryRunState.result], matching [QueryRunActions.releaseResults]'s doc
/// comment.
QueryRunState releasedRunState(QueryRunState current) {
  final result = current.result;
  if (current.status != QueryRunStatus.done || result == null) return current;
  return QueryRunState(
    status: QueryRunStatus.done,
    result: QueryResult(
      columns: result.columns,
      rows: const [],
      perDatabase: result.perDatabase,
      cancelled: false,
    ),
    queryText: current.queryText,
    released: true,
  );
}

/// Turns a freshly-`done` [state] — from a *first-page* fetch, i.e.
/// [statements]'s last entry was already wrapped via `wrapForPage` — into
/// the paginated form: caps [QueryResult.rows] to [paginationPageSize],
/// derives [QueryRunState.hasMore] from whether the extra probe row came
/// back, and records [target]/[originalStatement] so `loadNextPage`/CSV
/// export know how to fetch the rest later. A no-op unless [state.status]
/// is [QueryRunStatus.done] (e.g. leaves a cancelled run untouched).
QueryRunState applyPaginationCap(
  QueryRunState state, {
  required QueryTarget target,
  required String originalStatement,
}) {
  final result = state.result;
  if (state.status != QueryRunStatus.done || result == null) return state;

  final hasMore = result.rows.length > paginationPageSize;
  final pageRows =
      hasMore ? result.rows.take(paginationPageSize).toList() : result.rows;
  final outcome = result.perDatabase.firstOrNull;
  return state.copyWith(
    result: QueryResult(
      columns: result.columns,
      rows: pageRows,
      perDatabase: outcome == null
          ? result.perDatabase
          : [
              DatabaseQueryOutcome(
                databaseId: outcome.databaseId,
                databaseName: outcome.databaseName,
                serverName: outcome.serverName,
                databaseHost: outcome.databaseHost,
                success: outcome.success,
                errorMessage: outcome.errorMessage,
                blocked: outcome.blocked,
                rowCount: outcome.success ? pageRows.length : outcome.rowCount,
                affectedRows: outcome.affectedRows,
              ),
            ],
      cancelled: result.cancelled,
    ),
    paginated: true,
    hasMore: hasMore,
    pagingTarget: target,
    pagingOriginalStatement: originalStatement,
  );
}

/// Backs [QueryRunActions.loadMore] — shared by [QueryRunNotifier] and
/// `TabQueryRunNotifier`, same "free function both notifiers call" shape as
/// [runStatementAndRecord]/[releasedRunState]. Fetches one more page
/// starting right after however many rows are already loaded and appends
/// it; a fetch failure stops offering more pages and surfaces the error via
/// the same outcome-pill `ResultsPane` already renders for a failed run,
/// without discarding the rows already loaded. Not cancellable mid-flight
/// (v1 scope) — one page is bounded (`paginationPageSize + 1` rows), not
/// worth its own cancel affordance.
Future<QueryRunState> loadNextPage(Ref ref, QueryRunState current) async {
  final target = current.pagingTarget;
  final original = current.pagingOriginalStatement;
  final existing = current.result;
  if (target == null || original == null || existing == null) {
    return current.copyWith(loadingMore: false, hasMore: false);
  }

  final wrapped = wrapForPage(original, target.database.engine,
      offset: existing.rows.length, fetchCount: paginationPageSize + 1);
  final fetched = await ref.read(queryExecutionServiceProvider).run(
        targets: [target],
        statements: [wrapped],
        resolveCredentials: ref.read(credentialsRepositoryProvider).resolve,
        cancellationToken: CancellationToken(),
      );

  final outcome = fetched.perDatabase.firstOrNull;
  if (fetched.cancelled || outcome == null || !outcome.success) {
    return current.copyWith(
      loadingMore: false,
      hasMore: false,
      result: QueryResult(
        columns: existing.columns,
        rows: existing.rows,
        perDatabase: outcome == null ? existing.perDatabase : [outcome],
        cancelled: false,
      ),
    );
  }

  final hasMore = fetched.rows.length > paginationPageSize;
  final newRows =
      hasMore ? fetched.rows.take(paginationPageSize).toList() : fetched.rows;
  final allRows = [...existing.rows, ...newRows];
  return current.copyWith(
    loadingMore: false,
    hasMore: hasMore,
    result: QueryResult(
      columns: existing.columns,
      rows: allRows,
      perDatabase: [
        DatabaseQueryOutcome(
          databaseId: outcome.databaseId,
          databaseName: outcome.databaseName,
          serverName: outcome.serverName,
          databaseHost: outcome.databaseHost,
          success: true,
          rowCount: allRows.length,
        ),
      ],
      cancelled: false,
    ),
  );
}

/// Wraps [statements]'s last entry in a page-0 `LIMIT`/`OFFSET` fetch when
/// eligible (exactly one target — see [QueryRunState.paginated]'s doc
/// comment for why multi-target stays out of scope — and the statement is a
/// plain `SELECT`/`WITH` with no `LIMIT`/`OFFSET`/`TOP` of its own) —
/// shared by both notifiers' `run()`. `target`/`original` come back null
/// when pagination doesn't apply, in which case `toRun` is just
/// [statements] unchanged.
({List<String> toRun, QueryTarget? target, String? original})
    preparePagination(List<QueryTarget> targets, List<String> statements) {
  if (targets.length != 1 || !isPaginableSelect(statements.last)) {
    return (toRun: statements, target: null, original: null);
  }
  final target = targets.single;
  final original = statements.last;
  final wrapped = wrapForPage(original, target.database.engine,
      offset: 0, fetchCount: paginationPageSize + 1);
  return (
    toRun: [...statements.sublist(0, statements.length - 1), wrapped],
    target: target,
    original: original,
  );
}

/// Refreshes each target's `DatabaseEntry.mode` from disk right before
/// running — real gap reported 2026-08-12: a query window's own
/// `selectedQueryTargetsProvider` is a frozen snapshot taken once when the
/// window opened (`overrideWithValue` in `query_window_bootstrap.dart`,
/// which explicitly never re-reads server config afterward), so flipping a
/// database to "Consultas sin restricciones" from the main window's
/// Administración left an already-open query window for that same
/// database still enforcing the old "Solo lectura" guard until closed and
/// reopened. `SharedPreferences.getInstance()` only loads from the
/// platform side once per isolate and caches after that — `.reload()` is
/// the one call that genuinely re-fetches, so this reflects whatever the
/// most recent `ServersRepository.saveAll` wrote, from any window. Applied
/// unconditionally (main window included) rather than special-cased to
/// query windows: the main window's own edits already flow through
/// `serversProvider` before this ever runs, so the reload here is just a
/// harmless redundant disk read for that case, not a behavior change.
Future<List<QueryTarget>> _withLiveModes(
    Ref ref, List<QueryTarget> targets) async {
  final prefs = ref.read(sharedPreferencesProvider);
  await prefs.reload();
  final liveState = ref.read(serversRepositoryProvider).load();

  QueryTarget refresh(QueryTarget target) {
    // "Sin grupo" target — its live copy lives in `ungroupedDatabases`,
    // not inside any server's own list.
    final liveDb = target.server == null
        ? liveState.ungroupedDatabases
            .where((d) => d.id == target.database.id)
            .firstOrNull
        : liveState.servers
            .where((s) => s.id == target.server!.id)
            .firstOrNull
            ?.databases
            .where((d) => d.id == target.database.id)
            .firstOrNull;
    return liveDb == null
        ? target
        : (
            server: target.server,
            database: target.database.copyWith(mode: liveDb.mode)
          );
  }

  return [for (final t in targets) refresh(t)];
}

/// Shared by [QueryRunNotifier.run] and `TabQueryRunNotifier.run` — real
/// duplication fixed 2026-08-04 (AUDITORIA_CODIGO.md): every step past
/// resolving `targets`/the editor state was copy-pasted verbatim between the
/// two (only *how* each obtains those two inputs differs — the main window
/// reads them with no tab id, a tab reads its own tab-scoped providers).
/// Takes over from there: resolves the statement(s) to run, prepares
/// pagination, transitions through `running`, calls
/// [runStatementAndRecord], and applies the pagination cap — assigning
/// through [setState]/[setCancellationToken] at each step because the
/// intermediate `running` transitions and the cancellation token need to
/// reach the real `Notifier.state`/`_cancellationToken` field, which only
/// the caller has. [setCancellationToken] is called synchronously before
/// the first `await`, same as the un-extracted code, so a concurrent
/// `cancel()` still sees it in time.
Future<void> runQueryFlow({
  required Ref ref,
  required List<QueryTarget> targets,
  required QueryEditorState editor,
  required void Function(QueryRunState) setState,
  required void Function(CancellationToken) setCancellationToken,
}) async {
  if (targets.isEmpty) return;

  final statements = resolveStatementsToRun(
    fullText: editor.text,
    selectedText: editor.selectedText,
  );
  if (statements.isEmpty) return;

  final liveTargets = await _withLiveModes(ref, targets);
  final paging = preparePagination(liveTargets, statements);

  final cancellationToken = CancellationToken();
  setCancellationToken(cancellationToken);
  setState(const QueryRunState(status: QueryRunStatus.running));

  var result = await runStatementAndRecord(
    ref: ref,
    targets: liveTargets,
    statements: paging.toRun,
    displayStatements: statements,
    cancellationToken: cancellationToken,
    paginationCap: paging.target != null ? paginationPageSize : null,
    onProgress: (completed, total) => setState(QueryRunState(
      status: QueryRunStatus.running,
      currentStatement: completed,
      totalStatements: total,
    )),
  );

  if (paging.target != null && paging.original != null) {
    result = applyPaginationCap(result,
        target: paging.target!, originalStatement: paging.original!);
  }
  setState(result);
}

/// Drives the single Ejecutar/Cancelar toggle button and the results card.
class QueryRunNotifier extends Notifier<QueryRunState>
    implements QueryRunActions {
  CancellationToken? _cancellationToken;

  @override
  QueryRunState build() => const QueryRunState();

  @override
  void releaseResults() => state = releasedRunState(state);

  @override
  Future<void> loadMore() async {
    if (!state.paginated || !state.hasMore || state.loadingMore) return;
    state = state.copyWith(loadingMore: true);
    state = await loadNextPage(ref, state);
  }

  @override
  Future<void> run() => runQueryFlow(
        ref: ref,
        targets: ref.read(selectedQueryTargetsProvider),
        editor: ref.read(sqlEditorProvider),
        setState: (s) => state = s,
        setCancellationToken: (t) => _cancellationToken = t,
      );

  @override
  void cancel() {
    _cancellationToken?.cancel();
    state = const QueryRunState(status: QueryRunStatus.idle);
  }
}

final queryRunProvider =
    NotifierProvider<QueryRunNotifier, QueryRunState>(QueryRunNotifier.new);

/// Whether a CSV export is currently running for the home tab's results —
/// deliberately its own provider, not local `State` on `ResultsPane`
/// (2026-08-01 real bug: `results_card.dart` swaps `ResultsPane` out for
/// `_RunningState` the instant a new query starts running, which disposes
/// local widget state — the export was still writing the file correctly in
/// the background, it just silently lost all UI feedback). See
/// `query_tabs_providers.dart`'s `tabExportingCsvProvider` for the per-tab
/// twin and `watchExportingCsv`/`setExportingCsv` for the routing helpers
/// both `results_card.dart` and `results_pane.dart` actually use.
class ExportingCsvNotifier extends Notifier<bool> {
  @override
  bool build() => false;

  void set(bool value) => state = value;
}

final exportingCsvProvider =
    NotifierProvider<ExportingCsvNotifier, bool>(ExportingCsvNotifier.new);

/// SQL editor zoom (`sql_editor.dart`, Ctrl+scroll or Ctrl+=/Ctrl+-/Ctrl+0)
/// — null means "use the theme's own `AppTypography.monospace.fontSize`
/// unmodified," only becoming a concrete pixel size once the user actually
/// zooms; Ctrl+0 resets back to null rather than to some hardcoded number,
/// so a future change to the theme's base editor size is still honored for
/// anyone who never zoomed (or reset). Session-only (resets on restart),
/// same as the sidebar's drag-resized width — a reading-comfort
/// preference, not persisted app config. Shared across every `SqlEditor`
/// instance in one window (home pane + every tab) rather than per-tab,
/// since it's about the user's own eyes, not part of any one query's
/// state — but each query *window* (`desktop_multi_window`, its own
/// isolate/`ProviderScope`) naturally gets its own independent zoom level,
/// same as every other piece of state that doesn't sync across windows.
final sqlEditorFontSizeProvider = StateProvider<double?>((ref) => null);
