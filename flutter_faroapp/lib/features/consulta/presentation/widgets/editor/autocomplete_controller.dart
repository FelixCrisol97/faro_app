import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../../core/theme/app_colors.dart';
import '../../../../../core/theme/app_shadows.dart';
import '../../../../../core/theme/app_typography.dart';
import '../../../application/sql_autocomplete.dart';
import 'highlighting_controller.dart';

/// Table/column-name autocomplete popup — `FROM <partial>` and
/// `WHERE`/`AND`/`OR`/`ON`/`HAVING`/`ORDER BY`/`GROUP BY`/`SELECT <partial>`
/// (see `application/sql_autocomplete.dart`'s `detectAutocompleteTrigger`
/// for the pure trigger-detection/filtering logic this wraps in mutable
/// state). Owns the suggestion list, which one is highlighted, the pending
/// replace range, and the popup's own `Overlay`/`LayerLink` plumbing.
///
/// Extracted 2026-08-19 (AUDITORIA_CODIGO.md, the highest-risk item, saved
/// for last on purpose) out of `sql_editor.dart`, which used to explain
/// right in its own class doc comment why this stayed inline as long as it
/// did: "ambos ligados al árbol de renderizado propio de este widget —
/// `LayerLink`/`Overlay`/matemática de posición del cursor no se factorizan
/// limpio." That's still true for the *positioning* math specifically, so
/// this class doesn't try to own it: [show] takes an already-computed
/// [Offset] rather than reaching for `BuildContext`/`TextStyle` layout math
/// itself — the host keeps `_caretOffset`/`_lineHeight` (also shared with
/// the line-number gutter and the search-jump scroll, so duplicating a copy
/// here would diverge from those over time) and computes the caret position
/// right before calling [show]. The one piece of render-tree coupling that
/// truly can't be avoided is `Overlay.of(context)` itself, in [show].
///
/// Not a `ChangeNotifier` — [onChanged] is a plain callback the host wires
/// to its own `setState`, same convention `EditorSearchController` and
/// `EditorZoomController` already use.
class AutocompleteController {
  AutocompleteController({
    required HighlightingController editorController,
    required VoidCallback onChanged,
  })  : _editorController = editorController,
        _onChanged = onChanged;

  final HighlightingController _editorController;
  final VoidCallback _onChanged;

  /// See `application/sql_autocomplete.dart`'s `filterSuggestions` doc
  /// comment for why this is capped — nobody scrolls a 5000-name popup.
  static const maxSuggestions = 50;

  static const _popupRadius = BorderRadius.all(Radius.circular(10));

  /// Links [show]'s `CompositedTransformFollower` to the host's own
  /// `CompositedTransformTarget` wrapping its `TextField` — the host must
  /// use this exact link there for positioning to work at all.
  final layerLink = LayerLink();

  OverlayEntry? _overlayEntry;
  List<String> _suggestions = const [];
  int _selectedIndex = 0;
  int _replaceFrom = 0;
  int _replaceLength = 0;

  bool get isOpen => _overlayEntry != null;
  int get replaceFrom => _replaceFrom;
  int get replaceLength => _replaceLength;

  void dispose() => close();

  void close() {
    _overlayEntry?.remove();
    _overlayEntry = null;
  }

  /// Re-evaluates whether the popup should be open for [text]/[selection] —
  /// closes any existing popup and returns false unless the cursor sits
  /// right after a recognized trigger with at least one matching name.
  /// [readTableNames]/[readColumnNames] are callbacks rather than a direct
  /// Riverpod `ref` so this class doesn't need to know about query tabs —
  /// the host already resolves its own tab-aware provider before calling
  /// in. Doesn't itself show the popup — a true return means the caller
  /// should follow up with [show] right away (see its doc comment for why
  /// the host still supplies the caret position/colors for that step); the
  /// return value exists specifically so the caller doesn't have to guess
  /// that from [isOpen], which only reflects whether [show] has actually
  /// run yet.
  bool update(
    String text,
    TextSelection selection, {
    required AsyncValue<List<String>> Function() readTableNames,
    required AsyncValue<List<String>> Function(String tablesKey)
        readColumnNames,
  }) {
    if (!selection.isValid || !selection.isCollapsed) {
      close();
      return false;
    }
    final upToCursor = text.substring(0, selection.baseOffset);
    final trigger = detectAutocompleteTrigger(upToCursor);
    if (trigger == null) {
      close();
      return false;
    }

    switch (trigger.target) {
      case AutocompleteTarget.table:
        return _prepare(trigger, readTableNames());
      case AutocompleteTarget.column:
        final tablesKey = referencedTablesKey(text);
        if (tablesKey.isEmpty) {
          close();
          return false;
        }
        return _prepare(trigger, readColumnNames(tablesKey));
    }
  }

  /// Shared by both autocomplete triggers (`FROM <partial>` → table names;
  /// clause-keyword triggers → column names) — only the source of
  /// candidate names differs, everything about filtering/replace-range is
  /// identical. Closes the popup and returns false when there's nothing to
  /// suggest, e.g. while the name provider is still loading.
  bool _prepare(
      AutocompleteTrigger trigger, AsyncValue<List<String>> namesAsync) {
    final suggestions = namesAsync.maybeWhen(
      data: (names) =>
          filterSuggestions(names, trigger.partial, maxSuggestions),
      orElse: () => const <String>[],
    );
    if (suggestions.isEmpty) {
      close();
      return false;
    }
    _suggestions = suggestions;
    _selectedIndex = 0;
    _replaceFrom = trigger.replaceFrom;
    _replaceLength = trigger.partial.length;
    return true;
  }

  /// Builds and inserts the popup at [caretOffset] — see the class doc
  /// comment for why the host computes that instead of this class. A no-op
  /// when [update] didn't leave the popup open (nothing to show).
  void show(
    BuildContext context, {
    required Offset caretOffset,
    required AppColors colors,
    required AppTypography typography,
  }) {
    close();
    if (_suggestions.isEmpty) return;

    _overlayEntry = OverlayEntry(
      builder: (context) => Positioned(
        width: 240,
        child: CompositedTransformFollower(
          link: layerLink,
          showWhenUnlinked: false,
          offset: caretOffset + const Offset(0, 22),
          child: Material(
            elevation: 0,
            borderRadius: _popupRadius,
            child: Container(
              decoration: BoxDecoration(
                color: colors.surface,
                borderRadius: _popupRadius,
                boxShadow: AppShadows.md,
              ),
              constraints: const BoxConstraints(maxHeight: 160),
              // .builder, not a plain children: list — _suggestions is
              // capped (maxSuggestions) so this alone wouldn't balloon, but
              // building on-demand rather than allocating every row's
              // widgets up front on every keystroke is free to do right.
              child: ListView.builder(
                shrinkWrap: true,
                padding: const EdgeInsets.symmetric(vertical: 4),
                itemCount: _suggestions.length,
                itemBuilder: (context, index) {
                  final name = _suggestions[index];
                  return InkWell(
                    onTap: () => applySelected(name),
                    child: Container(
                      color:
                          index == _selectedIndex ? colors.accent.soft : null,
                      padding: const EdgeInsets.symmetric(
                          horizontal: 12, vertical: 8),
                      child: Text(name,
                          style: typography.monospace.copyWith(fontSize: 13)),
                    ),
                  );
                },
              ),
            ),
          ),
        ),
      ),
    );
    Overlay.of(context).insert(_overlayEntry!);
  }

  void selectNext() {
    if (_suggestions.isEmpty) return;
    _selectedIndex = (_selectedIndex + 1) % _suggestions.length;
    _onChanged();
    _overlayEntry?.markNeedsBuild();
  }

  void selectPrevious() {
    if (_suggestions.isEmpty) return;
    _selectedIndex =
        (_selectedIndex - 1 + _suggestions.length) % _suggestions.length;
    _onChanged();
    _overlayEntry?.markNeedsBuild();
  }

  /// The `Enter`/`Tab` key handler's version of tapping the highlighted
  /// row — a no-op if the popup isn't open.
  void applyHighlighted() {
    if (_suggestions.isEmpty) return;
    applySelected(_suggestions[_selectedIndex]);
  }

  /// Inserts [name] in place of the pending partial word and closes the
  /// popup. The host's own controller listener (`sql_editor.dart`'s
  /// `_onControllerChanged`) picks up the resulting text change the normal
  /// way — this only needs to make the edit and get out of the way.
  void applySelected(String name) {
    final text = _editorController.text;
    final newText =
        text.replaceRange(_replaceFrom, _replaceFrom + _replaceLength, name);
    _editorController.value = TextEditingValue(
      text: newText,
      selection: TextSelection.collapsed(offset: _replaceFrom + name.length),
    );
    close();
  }
}
