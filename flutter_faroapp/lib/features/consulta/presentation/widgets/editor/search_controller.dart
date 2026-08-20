import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../application/editor_search.dart';
import 'highlighting_controller.dart';

/// Find-in-editor (Ctrl+F / the search icon, `editor/search_bar.dart`) —
/// user request: their client's scripts run up to ~1000 lines, long enough
/// that eyeballing for a keyword isn't practical. Owns the search box's own
/// text/focus, the current match list, and which one is active; reads/
/// writes [_editorController] (the SQL editor's own text) directly rather
/// than going through `sql_editor.dart`, so the two stay trivially in sync.
///
/// Not a `ChangeNotifier` — [onChanged] is a plain callback the host wires
/// to its own `setState`, matching how the rest of `sql_editor.dart`
/// already drives rebuilds, rather than introducing a second rebuild
/// mechanism (`ListenableBuilder`) alongside it.
class EditorSearchController {
  EditorSearchController({
    required HighlightingController editorController,
    required FocusNode editorFocusNode,
    required VoidCallback onChanged,
    required ValueChanged<int> onJumpTo,
  })  : _editorController = editorController,
        _editorFocusNode = editorFocusNode,
        _onChanged = onChanged,
        _onJumpTo = onJumpTo {
    focusNode = FocusNode(onKeyEvent: _handleKeyEvent);
  }

  final HighlightingController _editorController;
  final FocusNode _editorFocusNode;
  final VoidCallback _onChanged;

  /// **Real bug, user-reported: jumping to a match never actually scrolled
  /// the editor there.** The original design here only set the *real* text
  /// selection to the active match, expecting Flutter's own `EditableText`
  /// to scroll it into view for free — that only reliably happens for a
  /// FOCUSED field, and the editor never has focus while the user is
  /// typing in the search box (the search box does). Fixed by asking the
  /// host directly to scroll — `sql_editor.dart` already owns the
  /// `ScrollController` and the line-height math (`_scrollEditorTo`), so
  /// this only needs to hand it a character offset. The selection is still
  /// set too (below) for its native-highlight visual, but is no longer
  /// relied on for scrolling.
  final ValueChanged<int> _onJumpTo;

  bool visible = false;
  final queryController = TextEditingController();
  late final FocusNode focusNode;
  List<TextRange> matches = const [];
  int activeMatchIndex = -1;

  /// Set for the instant [_scrollToActive] moves the *real* text selection
  /// to the active match — purely for its native selection-box highlight.
  /// `sql_editor.dart`'s own controller listener checks this before
  /// treating a selection change as the user selecting that text to run as
  /// a query — without it, every search jump would look exactly like that.
  bool suppressSelectionSync = false;

  void dispose() {
    queryController.dispose();
    focusNode.dispose();
  }

  void open() {
    visible = true;
    _recompute();
    // The search field doesn't exist in the tree until the rebuild the
    // _onChanged() inside _recompute() triggers — request focus next
    // frame, same reasoning any "focus a just-shown field" case needs.
    WidgetsBinding.instance.addPostFrameCallback((_) => focusNode.requestFocus());
  }

  void close() {
    visible = false;
    matches = const [];
    activeMatchIndex = -1;
    queryController.clear();
    _onChanged();
    _editorFocusNode.requestFocus();
  }

  void onQueryChanged(String _) => _recompute();

  /// Call whenever the underlying script text changes while search is
  /// open, so matches (and the "N de M" counter) stay live instead of
  /// pointing at stale offsets. A no-op while search is closed.
  ///
  /// **Real bug, user-reported with a screenshot:** this used to call the
  /// same [_recompute] that [open]/[onQueryChanged] use, which also jumps
  /// the *real* editor selection to the first match via [_scrollToActive].
  /// With a search still open from an earlier lookup, every keystroke
  /// typed anywhere else in the script — including just accepting an
  /// autocomplete suggestion or pressing Enter for a new line — silently
  /// yanked the cursor back to that old match mid-edit, so the next
  /// character typed landed there instead of where the user was actually
  /// typing. Recomputing the match list (for the counter) is still
  /// correct and wanted here; jumping the cursor is not — only an
  /// explicit search action ([open], typing in the search box, [next]/
  /// [previous]) should ever move it.
  void onEditorTextChanged() {
    if (!visible) return;
    matches = findMatches(_editorController.text, queryController.text);
    if (activeMatchIndex >= matches.length) {
      activeMatchIndex = matches.isEmpty ? -1 : matches.length - 1;
    }
    _onChanged();
  }

  void next() {
    if (matches.isEmpty) return;
    activeMatchIndex = (activeMatchIndex + 1) % matches.length;
    _onChanged();
    _scrollToActive();
  }

  void previous() {
    if (matches.isEmpty) return;
    activeMatchIndex =
        (activeMatchIndex - 1 + matches.length) % matches.length;
    _onChanged();
    _scrollToActive();
  }

  /// Plain case-insensitive substring search over the *whole* script (not
  /// just what's on screen). Resets to the first match on every recompute,
  /// whether that's a new keystroke in the search box or the script text
  /// itself changing underneath an open search.
  void _recompute() {
    matches = findMatches(_editorController.text, queryController.text);
    activeMatchIndex = matches.isEmpty ? -1 : 0;
    _onChanged();
    if (matches.isNotEmpty) _scrollToActive();
  }

  void _scrollToActive() {
    if (activeMatchIndex < 0 || activeMatchIndex >= matches.length) return;
    final match = matches[activeMatchIndex];
    suppressSelectionSync = true;
    _editorController.selection =
        TextSelection(baseOffset: match.start, extentOffset: match.end);
    suppressSelectionSync = false;
    _onJumpTo(match.start);
  }

  KeyEventResult _handleKeyEvent(FocusNode node, KeyEvent event) {
    if (event is! KeyDownEvent) return KeyEventResult.ignored;
    switch (event.logicalKey) {
      case LogicalKeyboardKey.escape:
        close();
        return KeyEventResult.handled;
      case LogicalKeyboardKey.enter:
      case LogicalKeyboardKey.numpadEnter:
        if (HardwareKeyboard.instance.isShiftPressed) {
          previous();
        } else {
          next();
        }
        return KeyEventResult.handled;
      default:
        return KeyEventResult.ignored;
    }
  }
}
