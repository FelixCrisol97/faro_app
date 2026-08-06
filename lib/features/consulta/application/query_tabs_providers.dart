import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:uuid/uuid.dart';

import '../../../data/datasources/cancellation_token.dart';
import '../../../data/providers/servers_providers.dart';
import 'consulta_providers.dart';

const _uuid = Uuid();

/// One open query tab's identity + which database it targets. Deliberately
/// keyed by its own synthetic [id] (a uuid), not by (serverId, databaseId)
/// — matches how "Abrir en nueva ventana" already behaves (opening it
/// twice against the same database makes two windows, not one reused
/// window), so tabs don't surprise with different dedup behavior. The
/// (serverId, databaseId) pair a tab targets is metadata about the tab,
/// not its identity.
class QueryTabMeta {
  const QueryTabMeta({
    required this.id,
    required this.serverId,
    required this.databaseId,
  });

  final String id;
  final String serverId;
  final String databaseId;
}

class QueryTabsState {
  const QueryTabsState({this.tabs = const [], this.activeTabId});

  final List<QueryTabMeta> tabs;

  /// null = the "Consulta" home tab (the sidebar-driven single pane that
  /// existed before this feature) is active — never an id that isn't
  /// actually in [tabs].
  final String? activeTabId;
}

/// Which open tabs exist and which is active — not their editor/results
/// content, see [tabSqlEditorProvider]/[tabQueryRunProvider] for that.
class QueryTabsNotifier extends Notifier<QueryTabsState> {
  @override
  QueryTabsState build() => const QueryTabsState();

  /// Always creates a new tab — see [QueryTabMeta]'s doc comment for why
  /// this doesn't dedup by database.
  String openTab({required String serverId, required String databaseId}) {
    final id = _uuid.v4();
    state = QueryTabsState(
      tabs: [
        ...state.tabs,
        QueryTabMeta(id: id, serverId: serverId, databaseId: databaseId),
      ],
      activeTabId: id,
    );
    return id;
  }

  /// [tabId] null switches back to the "Consulta" home tab.
  void activate(String? tabId) =>
      state = QueryTabsState(tabs: state.tabs, activeTabId: tabId);

  void closeTab(String tabId) {
    final before = state.tabs;
    final after = before.where((t) => t.id != tabId).toList();
    final wasActive = state.activeTabId == tabId;
    state = QueryTabsState(
      tabs: after,
      activeTabId: wasActive
          ? nextActiveTabIdAfterClosing(tabId, before, after)
          : state.activeTabId,
    );
    // Explicit cleanup — these are deliberately non-autoDispose (see their
    // own doc comments), so nothing else frees them.
    ref.invalidate(tabSqlEditorProvider(tabId));
    ref.invalidate(tabQueryRunProvider(tabId));
    ref.invalidate(resolvedTabTargetProvider(tabId));
    ref.invalidate(tabExportingCsvProvider(tabId));
  }
}

final queryTabsProvider =
    NotifierProvider<QueryTabsNotifier, QueryTabsState>(QueryTabsNotifier.new);

/// Which tab becomes active after closing [closed] — the one that "falls
/// into" its spot in the tab strip (browser/VS Code convention), or the
/// "Consulta" home tab (`null`) if none are left. A pure function so it's
/// unit-testable without standing up a ProviderContainer, same spirit as
/// `sql_statement_resolver.dart`'s functions.
String? nextActiveTabIdAfterClosing(
  String closed,
  List<QueryTabMeta> before,
  List<QueryTabMeta> after,
) {
  if (after.isEmpty) return null;
  final closedIndex = before.indexWhere((t) => t.id == closed);
  final nextIndex = closedIndex.clamp(0, after.length - 1);
  return after[nextIndex].id;
}

/// The live [QueryTarget] a tab points at. `ref.watch`, not the one-shot
/// `ref.read` `schemaExplorerProvider`/`tableColumnsProvider`/etc.
/// deliberately use (those are fetch-once-and-cache; this is state a
/// widget renders continuously) — needs to be live because the database a
/// tab targets can be deleted from Administración while the tab stays
/// open, in the very same window, with no restart. Returns null in that
/// case so `toolbar_card.dart` can show "this database no longer exists"
/// instead of crashing on a stale reference.
final resolvedTabTargetProvider =
    Provider.family<QueryTarget?, String>((ref, tabId) {
  final meta = ref
      .watch(queryTabsProvider)
      .tabs
      .where((t) => t.id == tabId)
      .firstOrNull;
  if (meta == null) return null;
  final server = ref
      .watch(serversProvider)
      .where((s) => s.id == meta.serverId)
      .firstOrNull;
  final database =
      server?.databases.where((d) => d.id == meta.databaseId).firstOrNull;
  if (server == null || database == null) return null;
  return (server: server, database: database);
});

/// Per-tab editor state — same shape as the main window's global
/// [QueryEditorNotifier], just keyed by tab id instead of being a
/// singleton. Deliberately NOT `.autoDispose`: a tab's text must survive
/// switching away from it — its widget tree unmounts while it's not the
/// active tab (see `query_tab_workspace.dart`, the whole point of that
/// design is not keeping every tab's heavy widget tree, e.g. the results
/// table, mounted at once) — `autoDispose` would wipe the state at exactly
/// that moment, since nothing watches it while hidden. Cleaned up
/// explicitly instead, via `ref.invalidate` in [QueryTabsNotifier.closeTab].
class TabQueryEditorNotifier extends FamilyNotifier<QueryEditorState, String>
    implements QueryEditorActions {
  @override
  QueryEditorState build(String tabId) => const QueryEditorState();

  @override
  void setText(String text) => state = setTextState(state, text);

  @override
  void syncFromController(
          {required String text, String? selectedText, int? cursorOffset}) =>
      state = syncFromControllerState(state,
          text: text, selectedText: selectedText, cursorOffset: cursorOffset);

  /// See [loadTextState]'s doc comment — same [fromDisk] contract, just
  /// keyed by tab id.
  @override
  void loadText(String text, {String? filePath, bool fromDisk = false}) =>
      state = loadTextState(state, text, filePath: filePath, fromDisk: fromDisk);

  @override
  void markSaved() => state = markSavedState(state);
}

final tabSqlEditorProvider = NotifierProvider.family<TabQueryEditorNotifier,
    QueryEditorState, String>(TabQueryEditorNotifier.new);

/// Per-tab run state — same shape as [QueryRunNotifier], keyed by tab id.
/// Same non-autoDispose reasoning as [TabQueryEditorNotifier].
class TabQueryRunNotifier extends FamilyNotifier<QueryRunState, String>
    implements QueryRunActions {
  CancellationToken? _cancellationToken;

  @override
  QueryRunState build(String tabId) {
    // Closing a tab mid-run invalidates this provider outright (see
    // QueryTabsNotifier.closeTab) — without cancelling first, the
    // in-flight query would eventually try to write `state =` on a
    // Notifier Riverpod has already torn down, a real error, not a silent
    // no-op.
    ref.onDispose(() => _cancellationToken?.cancel());
    return const QueryRunState();
  }

  @override
  void releaseResults() => state = releasedRunState(state);

  @override
  Future<void> loadMore() async {
    if (!state.paginated || !state.hasMore || state.loadingMore) return;
    state = state.copyWith(loadingMore: true);
    state = await loadNextPage(ref, state);
  }

  @override
  Future<void> run() {
    final target = ref.read(resolvedTabTargetProvider(arg));
    if (target == null) return Future.value();
    return runQueryFlow(
      ref: ref,
      targets: [target],
      editor: ref.read(tabSqlEditorProvider(arg)),
      setState: (s) => state = s,
      setCancellationToken: (t) => _cancellationToken = t,
    );
  }

  @override
  void cancel() {
    _cancellationToken?.cancel();
    state = const QueryRunState(status: QueryRunStatus.idle);
  }
}

final tabQueryRunProvider = NotifierProvider.family<TabQueryRunNotifier,
    QueryRunState, String>(TabQueryRunNotifier.new);

/// Per-tab twin of [exportingCsvProvider] — see that provider's doc comment
/// for why this is a provider and not local widget `State`.
class TabExportingCsvNotifier extends FamilyNotifier<bool, String> {
  @override
  bool build(String tabId) => false;

  void set(bool value) => state = value;
}

final tabExportingCsvProvider = NotifierProvider.family<TabExportingCsvNotifier,
    bool, String>(TabExportingCsvNotifier.new);

// --- Routing helpers: tabId == null reads the main window's global
// providers unchanged; otherwise reads that tab's own `.family` instance.
// The one place every editor/results widget decides "home or tab" — keeps
// that branch out of toolbar_card.dart/sql_editor.dart/results_card.dart.

QueryEditorState watchEditorState(WidgetRef ref, String? tabId) => tabId == null
    ? ref.watch(sqlEditorProvider)
    : ref.watch(tabSqlEditorProvider(tabId));

/// Same as [watchEditorState] but `ref.read` — for call sites that can't
/// `watch` (e.g. a `State.initState`, before the widget's first `build`).
QueryEditorState readEditorState(WidgetRef ref, String? tabId) => tabId == null
    ? ref.read(sqlEditorProvider)
    : ref.read(tabSqlEditorProvider(tabId));

QueryEditorActions editorActionsFor(WidgetRef ref, String? tabId) =>
    tabId == null
        ? ref.read(sqlEditorProvider.notifier)
        : ref.read(tabSqlEditorProvider(tabId).notifier);

QueryRunState watchRunState(WidgetRef ref, String? tabId) => tabId == null
    ? ref.watch(queryRunProvider)
    : ref.watch(tabQueryRunProvider(tabId));

/// Same as [watchRunState] but `ref.read` — for call sites outside a
/// `build()`, e.g. `ConsultaScreen`'s F5 keyboard shortcut handler.
QueryRunState readRunState(WidgetRef ref, String? tabId) => tabId == null
    ? ref.read(queryRunProvider)
    : ref.read(tabQueryRunProvider(tabId));

QueryRunActions runActionsFor(WidgetRef ref, String? tabId) => tabId == null
    ? ref.read(queryRunProvider.notifier)
    : ref.read(tabQueryRunProvider(tabId).notifier);

bool watchExportingCsv(WidgetRef ref, String? tabId) => tabId == null
    ? ref.watch(exportingCsvProvider)
    : ref.watch(tabExportingCsvProvider(tabId));

/// A setter closure, not `void` — captured *once*, before any `await`
/// (`results_pane.dart`'s `_exportCsv` does exactly this), so it keeps
/// working even if the widget that requested it gets disposed while the
/// export is still running (e.g. a new query starts mid-export and
/// `results_card.dart` swaps `ResultsPane` out — see [exportingCsvProvider]).
/// Calling `ref.read`/`ref.watch` again after disposal throws; the captured
/// `Notifier.set` closure stays valid as long as the provider container
/// does, which outlives any one widget.
void Function(bool) exportingCsvNotifierFor(WidgetRef ref, String? tabId) =>
    tabId == null
        ? ref.read(exportingCsvProvider.notifier).set
        : ref.read(tabExportingCsvProvider(tabId).notifier).set;

/// Shared Ejecutar/Cancelar toggle logic — `ConsultaScreen`'s F5 shortcut
/// and `ToolbarCard`'s F5 button both funnel through this instead of each
/// repeating the same `if (isRunning) cancel() else if (canRun) run()`.
/// [canRun] is computed by the caller (the "is anything selected" guard
/// differs between the home tab and an explicit tab) — this only owns the
/// toggle itself.
void toggleQueryRun(
  QueryRunActions notifier, {
  required bool isRunning,
  required bool canRun,
}) {
  if (isRunning) {
    notifier.cancel();
    return;
  }
  if (!canRun) return;
  notifier.run();
}
