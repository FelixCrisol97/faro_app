import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/theme/app_theme.dart';
import '../../application/consulta_providers.dart';
import '../../application/query_tabs_providers.dart';
import '../../application/sql_autocomplete.dart';
import '../../application/sql_syntax_highlighter.dart';
import '../../application/table_names_provider.dart';
import 'editor/autocomplete_controller.dart';
import 'editor/highlighting_controller.dart';
import 'editor/line_number_gutter.dart';
import 'editor/search_bar.dart';
import 'editor/search_controller.dart';
import 'editor/zoom_controller.dart';

/// README.md "SQL editor": monospace, 10px radius (its own value — the
/// redesign's general control radius is 9px, containers are 16px; this
/// textarea gets its own per the handoff prototype). Height is driven by
/// its parent (`ToolbarCard` wraps it in `Expanded`) — the editor/results
/// split is drag-resizable, see `consulta_screen.dart`'s drag handle —
/// rather than a fixed row count.
///
/// **Revertido 2026-07-24 de vuelta a esta versión** después de probar una
/// Etapa 1 de migración a `re_editor` (colores/fuente conectados, sin
/// autocompletado/buscador/zoom todavía) — el usuario prefirió quedarse
/// con esta versión ya afinada ("me gusta así, de cualquier forma es para
/// mí"). El prototipo aislado que quedó de esa evaluación (botón en
/// Apariencia) se borró por completo el 2026-08-13 — la decisión de no
/// migrar quedó definitiva, junto con las dependencias `re_editor`/
/// `re_highlight` en `pubspec.yaml`.
///
/// Este archivo owns la lógica core de edición de texto (el
/// `TextField`/`HighlightingController` real, la sincronía scroll/gutter,
/// los atajos de teclado). El zoom, el buscador, y el popup de
/// autocompletado viven en sus propios controladores — ver
/// `editor/zoom_controller.dart`, `editor/search_controller.dart`,
/// `editor/autocomplete_controller.dart` (extraído 2026-08-19,
/// AUDITORIA_CODIGO.md — el último en salir de aquí, porque el
/// `LayerLink`/`Overlay`/la matemática de posición del cursor que necesita
/// SÍ siguen genuinamente ligados al árbol de renderizado de este widget;
/// ver el propio comentario de esa clase) — este widget solo conecta su
/// estado a eventos de teclado/scroll y al `HighlightingController`
/// compartido.
class SqlEditor extends ConsumerStatefulWidget {
  const SqlEditor({super.key, this.tabId});

  /// Set only inside an in-window query tab — see `toolbar_card.dart`'s
  /// matching field for the full explanation.
  final String? tabId;

  @override
  ConsumerState<SqlEditor> createState() => _SqlEditorState();
}

class _SqlEditorState extends ConsumerState<SqlEditor> {
  static const _contentPadding = 14.0;
  static const _editorRadius = BorderRadius.all(Radius.circular(10));

  late final HighlightingController _controller;
  late final FocusNode _focusNode;
  late final AutocompleteController _autocomplete;

  // Line numbers gutter: the TextField owns the real scroll position, the
  // gutter is a passive follower (NeverScrollableScrollPhysics — see build())
  // mirrored via a listener rather than sharing one ScrollController across
  // both Scrollables. Flutter gives each attached Scrollable its own
  // ScrollPosition, so a single shared controller doesn't sync automatically
  // and its `.offset` getter throws once more than one position is attached.
  final _editorScrollController = ScrollController();
  final _gutterScrollController = ScrollController();
  int _lineCount = 1;

  // Word-occurrence highlight: word under the caret. Only recomputed when
  // the caret moves WITHOUT the text changing (a click or arrow-key move —
  // see _onControllerChanged's `text == _lastText` check) — matching the
  // original ask ("cuando le doy clic a una tabla... quisiera que se
  // remarcara"), which is about inspecting existing code, not about typing.
  // Recomputing on every keystroke too made a word light up mid-sentence
  // while composing new text, which read as noisy/unintended (user report,
  // 2026-07-20: "eso" lighting up while typing an unrelated sentence).
  String? _highlightWord;
  String? _lastText;

  late final EditorZoomController _zoom;
  late final EditorSearchController _search;

  @override
  void initState() {
    super.initState();
    _controller =
        HighlightingController(text: readEditorState(ref, widget.tabId).text);
    _controller.addListener(_onControllerChanged);
    _focusNode = FocusNode(onKeyEvent: _handleKeyEvent);
    _autocomplete = AutocompleteController(
      editorController: _controller,
      onChanged: () => setState(() {}),
    );
    _zoom = EditorZoomController(ref);
    _search = EditorSearchController(
      editorController: _controller,
      editorFocusNode: _focusNode,
      onChanged: () => setState(() {}),
      onJumpTo: _scrollEditorTo,
    );
    _lastText = _controller.text;
    _lineCount = _countLines(_controller.text);
    _editorScrollController.addListener(_mirrorGutterScroll);
  }

  @override
  void dispose() {
    _controller.removeListener(_onControllerChanged);
    _controller.dispose();
    _focusNode.dispose();
    _search.dispose();
    _editorScrollController.removeListener(_mirrorGutterScroll);
    _editorScrollController.dispose();
    _gutterScrollController.dispose();
    _autocomplete.dispose();
    super.dispose();
  }

  void _mirrorGutterScroll() {
    if (!_gutterScrollController.hasClients) return;
    final target = _editorScrollController.offset.clamp(
      _gutterScrollController.position.minScrollExtent,
      _gutterScrollController.position.maxScrollExtent,
    );
    _gutterScrollController.jumpTo(target);
  }

  /// Scrolls the editor so [charOffset] is visible — [EditorSearchController]
  /// calls this (via `onJumpTo`) when the active search match changes.
  /// **Real bug, user-reported: the search bar counted matches correctly
  /// but the editor never actually scrolled to one.** The original design
  /// only moved the real text selection to the match, expecting Flutter's
  /// own `EditableText` to scroll it into view for free — that only
  /// reliably happens for a FOCUSED field, and the editor never has focus
  /// while the user is typing in the search box. Computing the scroll
  /// target by hand instead (same line-height math `_caretOffset` already
  /// uses for the autocomplete popup) doesn't depend on focus at all.
  /// Same known v1 caveat as that popup's positioning: a wrapped logical
  /// line (the editor has no horizontal scroll — see the class doc) throws
  /// off the line count for anything after it, since each logical line is
  /// assumed to be exactly one visual row.
  void _scrollEditorTo(int charOffset) {
    if (!_editorScrollController.hasClients) return;
    final typography = context.appTheme.typography;
    final fontSize = ref.read(sqlEditorFontSizeProvider) ??
        typography.monospace.fontSize!;
    final style = typography.monospace.copyWith(fontSize: fontSize);
    final text = _controller.text;
    final clamped = charOffset.clamp(0, text.length);
    final line = '\n'.allMatches(text.substring(0, clamped)).length;
    final targetY = line * _lineHeight(style);

    final position = _editorScrollController.position;
    // Centered in the viewport, not just barely scrolled into view — makes
    // a jump to a distant match easy to spot instead of landing right at
    // the viewport's edge.
    final target =
        (targetY - position.viewportDimension / 2).clamp(0.0, position.maxScrollExtent);
    _editorScrollController.animateTo(target,
        duration: const Duration(milliseconds: 200), curve: Curves.easeOut);
  }

  void _onControllerChanged() {
    final text = _controller.text;
    final selection = _controller.selection;
    // See EditorSearchController.suppressSelectionSync's doc comment — a
    // search-match jump sets the real TextField selection purely for its
    // native selection-box highlight (the actual scroll is handled
    // explicitly, see _scrollEditorTo), and must NOT be mistaken for the
    // user actually selecting that text to run as a query.
    final selectedText = !_search.suppressSelectionSync &&
            selection.isValid &&
            !selection.isCollapsed
        ? selection.textInside(text)
        : null;
    editorActionsFor(ref, widget.tabId).syncFromController(
      text: text,
      selectedText: selectedText,
      cursorOffset: selection.isValid ? selection.baseOffset : null,
    );
    _updateAutocomplete(text, selection);
    _updateLineCount(text);
    // Word-occurrence highlight is suppressed while searching — both paint
    // background colors on the same spans, and having both active at once
    // (e.g. clicking a word that also happens to match the search query)
    // read as visually confusing rather than useful.
    if (!_search.visible && text == _lastText) {
      // Caret moved (click, arrow keys) without the text itself changing —
      // the "inspecting existing code" case the feature is meant for.
      _updateHighlightWord(text, selection);
    } else if (_highlightWord != null) {
      // Actively typing/editing — drop any stale highlight instead of
      // recomputing one for whatever word the caret happens to be inside
      // mid-edit.
      setState(() => _highlightWord = null);
    }
    if (text != _lastText) _search.onEditorTextChanged();
    _lastText = text;
  }

  int _countLines(String text) => '\n'.allMatches(text).length + 1;

  void _updateLineCount(String text) {
    final count = _countLines(text);
    if (count != _lineCount) setState(() => _lineCount = count);
  }

  void _updateHighlightWord(String text, TextSelection selection) {
    final word = selection.isValid && selection.isCollapsed
        ? wordAt(text, selection.baseOffset)
        : null;
    if (word != _highlightWord) setState(() => _highlightWord = word);
  }

  /// Re-evaluates the popup for [text]/[selection] and, if
  /// [AutocompleteController.update] found something to suggest, computes
  /// where to put it and shows it — the caret-position/theme values it
  /// needs stay here (not in the controller) for the same reason `_caretOffset`/
  /// `_lineHeight` do, see this class's own doc comment and
  /// `AutocompleteController.show`'s.
  void _updateAutocomplete(String text, TextSelection selection) {
    final shouldShow = _autocomplete.update(
      text,
      selection,
      readTableNames: _readTableNames,
      readColumnNames: _readColumnNames,
    );
    if (!shouldShow) return;

    final typography = context.appTheme.typography;
    final editorStyle = typography.monospace.copyWith(
        fontSize: _zoom.effectiveFontSize(typography.monospace.fontSize!));
    final caretOffset = _caretOffset(
        text,
        _autocomplete.replaceFrom + _autocomplete.replaceLength,
        editorStyle);
    _autocomplete.show(
      context,
      caretOffset: caretOffset,
      colors: context.appTheme.colors,
      typography: typography,
    );
  }

  KeyEventResult _handleKeyEvent(FocusNode node, KeyEvent event) {
    if (event is! KeyDownEvent) return KeyEventResult.ignored;

    // Zoom/search shortcuts work regardless of whether the autocomplete
    // popup is open — checked before the overlay-only guard below.
    if (HardwareKeyboard.instance.isControlPressed) {
      final baseFontSize = context.appTheme.typography.monospace.fontSize!;
      switch (event.logicalKey) {
        case LogicalKeyboardKey.equal:
        case LogicalKeyboardKey.numpadAdd:
          _zoom.zoomIn(baseFontSize);
          return KeyEventResult.handled;
        case LogicalKeyboardKey.minus:
        case LogicalKeyboardKey.numpadSubtract:
          _zoom.zoomOut(baseFontSize);
          return KeyEventResult.handled;
        case LogicalKeyboardKey.digit0:
        case LogicalKeyboardKey.numpad0:
          _zoom.reset();
          return KeyEventResult.handled;
        case LogicalKeyboardKey.keyF:
          _search.open();
          return KeyEventResult.handled;
      }
    }

    if (!_autocomplete.isOpen) return KeyEventResult.ignored;

    switch (event.logicalKey) {
      case LogicalKeyboardKey.arrowDown:
        _autocomplete.selectNext();
        return KeyEventResult.handled;
      case LogicalKeyboardKey.arrowUp:
        _autocomplete.selectPrevious();
        return KeyEventResult.handled;
      case LogicalKeyboardKey.enter:
      case LogicalKeyboardKey.numpadEnter:
      case LogicalKeyboardKey.tab:
        _autocomplete.applyHighlighted();
        return KeyEventResult.handled;
      case LogicalKeyboardKey.escape:
        _autocomplete.close();
        return KeyEventResult.handled;
      default:
        return KeyEventResult.ignored;
    }
  }

  /// Pixel offset of the text cursor within the field, so the suggestion
  /// popup lines up under what's actually being typed instead of a fixed
  /// guess. Approximates `EditableText`'s own layout (same style/padding,
  /// one `TextPainter` per line up to the cursor) rather than reaching into
  /// `RenderEditable` internals.
  Offset _caretOffset(String text, int cursorOffset, TextStyle style) {
    final upToCursor = text.substring(0, cursorOffset);
    final lines = upToCursor.split('\n');
    final currentLine = lines.last;

    final linePainter = TextPainter(
        text: TextSpan(text: currentLine, style: style),
        textDirection: TextDirection.ltr)
      ..layout();

    return Offset(
      _contentPadding + linePainter.width,
      _contentPadding + (lines.length - 1) * _lineHeight(style),
    );
  }

  /// Height of one line in [style] — a single monospace glyph's own layout
  /// height already reflects the font's real line metrics, so this doubles
  /// as the line-number gutter's row height (see build()) with no separate
  /// calculation to keep in sync.
  double _lineHeight(TextStyle style) {
    final painter = TextPainter(
        text: TextSpan(text: 'M', style: style),
        textDirection: TextDirection.ltr)
      ..layout();
    return painter.height;
  }

  // tableNamesProvider/tabTableNamesProvider aren't state providers (no
  // shared QueryEditorActions/QueryRunActions-style interface to route
  // through), so the tabId branch is inlined here rather than added to
  // query_tabs_providers.dart's helpers — only this file needs it.
  AsyncValue<List<String>> _watchTableNames() => widget.tabId == null
      ? ref.watch(tableNamesProvider)
      : ref.watch(tabTableNamesProvider(widget.tabId!));

  AsyncValue<List<String>> _readTableNames() => widget.tabId == null
      ? ref.read(tableNamesProvider)
      : ref.read(tabTableNamesProvider(widget.tabId!));

  AsyncValue<List<String>> _watchColumnNames(String tablesKey) =>
      widget.tabId == null
          ? ref.watch(columnNamesProvider(tablesKey))
          : ref.watch(tabColumnNamesProvider(
              (tabId: widget.tabId!, tablesKey: tablesKey)));

  AsyncValue<List<String>> _readColumnNames(String tablesKey) =>
      widget.tabId == null
          ? ref.read(columnNamesProvider(tablesKey))
          : ref.read(tabColumnNamesProvider(
              (tabId: widget.tabId!, tablesKey: tablesKey)));

  @override
  Widget build(BuildContext context) {
    // Reuses the app's fixed semantic colors instead of inventing new
    // hues: keywords in the accent, strings in success-green (a common
    // syntax-highlighting convention success/error/warn already cover),
    // numbers in warn-amber, comments muted+italic.
    _controller.colors = SqlHighlightColors(
      keyword: context.appTheme.colors.accent.base,
      string: context.appTheme.colors.success.base,
      comment: context.appTheme.colors.textMuted,
      number: context.appTheme.colors.warn.base,
    );
    final colors = context.appTheme.colors;
    final typography = context.appTheme.typography;
    // Word-occurrence highlight lives on the controller (see
    // HighlightingController.buildTextSpan) so it's applied as part of the
    // same text-span build that already colors keywords/strings/comments —
    // not a separate overlay guessing at pixel positions.
    _controller.highlightWord = _highlightWord;
    _controller.highlightBackground = colors.accent.base.withValues(alpha: 0.25);
    // Find-in-editor (editor/search_bar.dart, editor/search_controller.dart)
    // — a different hue (warn, amber) from the word-occurrence highlight
    // just above (accent) so the two never look ambiguous on the rare
    // frame where both could apply.
    _controller.searchMatches = _search.matches;
    _controller.activeSearchMatchIndex = _search.activeMatchIndex;
    _controller.searchMatchBackground = colors.warn.base.withValues(alpha: 0.28);
    _controller.activeSearchMatchBackground = colors.warn.base.withValues(alpha: 0.55);

    // Keep table-name suggestions fresh as the active server changes.
    _watchTableNames();
    // Same, for column suggestions — keyed by whichever table(s) are
    // currently referenced via FROM/JOIN, so the fetch (and this widget's
    // rebuild once it completes) tracks the query as it's being written,
    // not just the active server.
    final tablesKey = referencedTablesKey(_controller.text);
    if (tablesKey.isNotEmpty) _watchColumnNames(tablesKey);

    // `_controller` only reads the provider once, in initState — without
    // this, external text changes (Formatear, Cargar, Favoritos "Usar",
    // Historial "Reusar", all of which call setText/loadText) update
    // sqlEditorProvider but never reach the visible TextField.
    ref.listen<QueryEditorState>(
      widget.tabId == null
          ? sqlEditorProvider
          : tabSqlEditorProvider(widget.tabId!),
      (previous, next) {
        if (next.text != _controller.text) {
          _controller.value = TextEditingValue(
            text: next.text,
            selection: TextSelection.collapsed(offset: next.text.length),
          );
        }
      },
    );

    // Zoom (Ctrl+scroll below, Ctrl+=/Ctrl+-/Ctrl+0 in _handleKeyEvent) —
    // both the field's own style and the gutter's (kept 1px smaller, same
    // relationship the fixed sizes had before zoom existed: 13 vs. the
    // theme's base 14) are derived from one effective size so line heights
    // stay in sync as it changes. `ref.watch` here (not `_zoom.effectiveFontSize`,
    // which reads — correct for the one-shot lookups in `_handleKeyEvent`/
    // `_updateAutocomplete`, but build() needs an actual subscription so
    // zooming triggers a rebuild).
    final baseFontSize = typography.monospace.fontSize!;
    final fontSize = ref.watch(sqlEditorFontSizeProvider) ?? baseFontSize;
    final editorStyle = typography.monospace.copyWith(fontSize: fontSize);
    final gutterStyle = editorStyle.copyWith(
        color: colors.textMuted, fontSize: (fontSize - 1).clamp(6.0, double.infinity));
    // Widest expected number sets a fixed gutter width so digits don't
    // shift the text area as the query grows past 9/99/999 lines; clamped
    // so a one-line query doesn't get a razor-thin column and a huge
    // generated script doesn't run away with the width.
    final gutterDigits = '$_lineCount'.length.clamp(2, 5);
    final gutterTextWidth = (TextPainter(
            text: TextSpan(text: '9' * gutterDigits, style: gutterStyle),
            textDirection: TextDirection.ltr)
          ..layout())
        .width;

    // Fill lives here instead of on the TextField's own InputDecoration, so
    // the line-number gutter can sit visually *inside* this one block next
    // to the field rather than floating outside it as a separate strip
    // (user feedback, 2026-07-20 — the earlier per-field border made the
    // gutter look bolted-on). No border stroke — a bordered outer block
    // plus the (now-removed) gutter/field divider read as doubled lines on
    // the field's three free edges (more user feedback, same round); the
    // shared `surfaceAlt` fill against the page's own background is enough
    // definition on its own.
    return Listener(
      // Ctrl+scroll zoom — plain Listener, not a resolver-arbitrated
      // handler, so it always fires alongside the TextField's own scroll;
      // a discrete Ctrl+scroll "zoom gesture" nudging the text position by
      // a pixel or two at the same time is an accepted, minor v1 tradeoff
      // (same class as the editor's other known v1 limitations noted
      // below) rather than fighting Flutter's pointer-signal routing for a
      // perfectly exclusive handler. Ctrl+=/Ctrl+-/Ctrl+0 (_handleKeyEvent)
      // are the fully conflict-free way to zoom for anyone who hits this.
      onPointerSignal: (event) {
        if (event is PointerScrollEvent &&
            HardwareKeyboard.instance.isControlPressed) {
          event.scrollDelta.dy < 0
              ? _zoom.zoomIn(baseFontSize)
              : _zoom.zoomOut(baseFontSize);
        }
      },
      child: Container(
        decoration: BoxDecoration(
          color: colors.surfaceAlt,
          borderRadius: _editorRadius,
        ),
        clipBehavior: Clip.antiAlias,
        child: Stack(
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                LineNumberGutter(
                  scrollController: _gutterScrollController,
                  lineCount: _lineCount,
                  lineHeight: _lineHeight(editorStyle),
                  topPadding: _contentPadding,
                  width: gutterTextWidth + 20,
                  style: gutterStyle,
                ),
                Expanded(
                  child: CompositedTransformTarget(
                    link: _autocomplete.layerLink,
                    child: TextField(
                      controller: _controller,
                      focusNode: _focusNode,
                      scrollController: _editorScrollController,
                      expands: true,
                      maxLines: null,
                      minLines: null,
                      textAlignVertical: TextAlignVertical.top,
                      style: editorStyle,
                      decoration: const InputDecoration(
                        border: InputBorder.none,
                        contentPadding: EdgeInsets.all(_contentPadding),
                        hintText: 'SELECT * FROM ...',
                      ),
                    ),
                  ),
                ),
              ],
            ),
            // Docked top-right, over the editor — never pushes the text
            // area's own layout around when it opens/closes. Collapsed to
            // just the trigger icon until Ctrl+F/a click expands it, per
            // the user's own ask for something that "no moleste la
            // visualización."
            Positioned(
              top: 6,
              right: 6,
              child: _search.visible
                  ? EditorSearchBar(
                      controller: _search.queryController,
                      focusNode: _search.focusNode,
                      matchCount: _search.matches.length,
                      activeMatchIndex: _search.activeMatchIndex,
                      onChanged: _search.onQueryChanged,
                      onNext: _search.next,
                      onPrevious: _search.previous,
                      onClose: _search.close,
                    )
                  : SearchToggleButton(onTap: _search.open),
            ),
          ],
        ),
      ),
    );
  }
}
