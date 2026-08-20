import 'dart:async';

import 'package:flutter/material.dart';

import '../../../application/sql_syntax_highlighter.dart';

/// Colors keywords/strings/comments via [highlightSql] instead of showing
/// plain text — [colors] is refreshed every build (theme can change at
/// runtime via Apariencia) rather than being fixed at construction time.
///
/// **Real perf fix, 2026-07-24 — measured, not guessed.** `buildTextSpan`
/// runs on every keystroke (Flutter calls it whenever the controller's
/// value changes), and `highlightSql` alone benchmarked at ~63ms/call for
/// a 1000-line script (see that function's doc comment for the numbers)
/// — ~4x over the ~16ms budget for smooth 60fps typing. Two independent
/// fixes, both in [_syntaxSpan]: (1) `highlightSql` itself got faster
/// (~2.4x, word-tokenize + hash-set instead of one big regex
/// alternation); (2) the *recompute* is now debounced — while the text is
/// still changing within [_debounceDelay] of the last keystroke, this
/// returns the current (always-correct) text with no syntax color at all
/// (cheap, ~O(1)), and only runs the real `highlightSql` once the user
/// pauses. The text itself is never stale, only its coloring lags a few
/// hundred ms behind a fast typing burst — imperceptible in practice, and
/// how most real code editors already behave. [highlightWord]/
/// [searchMatches] below are deliberately NOT debounced — clicking a word
/// or typing in the search box are comparatively cheap, one-off string/
/// offset operations on already-tokenized spans, and users expect those to
/// react instantly.
class HighlightingController extends TextEditingController {
  HighlightingController({super.text});

  SqlHighlightColors colors = const SqlHighlightColors(
    keyword: Color(0xFF000000),
    string: Color(0xFF000000),
    comment: Color(0xFF000000),
    number: Color(0xFF000000),
  );

  /// Word-occurrence highlight, applied as a background color directly on
  /// the same spans `highlightSql` already builds — not a separate overlay
  /// painted on top. Four attempts at independently *replicating* glyph
  /// positions (selection boxes, caret offsets, isolated-word width
  /// measurements — every combination) all showed the same "off by about
  /// one character" symptom in practice (confirmed by the user via
  /// screenshots each time, including a stray space getting caught at the
  /// start of the box). Splitting the actual rendered string at the exact
  /// regex match indices instead makes a mismatch structurally impossible
  /// — there's no pixel geometry to get wrong, only string slicing.
  String? highlightWord;
  Color highlightBackground = const Color(0x00000000);

  /// Find-in-editor (Ctrl+F, `editor/search_bar.dart`) — same "split the
  /// real spans, no overlay" approach as [highlightWord] above, but keyed
  /// by absolute character offsets into [text] (search matches are plain
  /// substrings anywhere, not whole-word regex matches per span) rather
  /// than by re-matching a pattern inside each span independently.
  List<TextRange> searchMatches = const [];
  int activeSearchMatchIndex = -1;
  Color searchMatchBackground = const Color(0x00000000);
  Color activeSearchMatchBackground = const Color(0x00000000);

  static const _debounceDelay = Duration(milliseconds: 120);
  Timer? _debounceTimer;
  // Text as of the *previous* buildTextSpan call — distinct from
  // [_syntaxCacheText] (text as of the last actual recompute). Needed to
  // tell "a genuine new keystroke" apart from "buildTextSpan got called
  // again for an unrelated reason (cursor blink, some other rebuild)
  // while the same recompute is still pending" — see [_syntaxSpan]'s real
  // bug note below.
  String? _lastSeenText;
  String? _syntaxCacheText;
  SqlHighlightColors? _syntaxCacheColors;
  TextStyle? _syntaxCacheStyle;
  TextSpan? _syntaxCacheSpan;

  @override
  void dispose() {
    _debounceTimer?.cancel();
    super.dispose();
  }

  /// Returns the syntax-colored base span — see the class doc comment.
  /// Shaped exactly like [highlightSql]'s own return value (an empty-text
  /// root [TextSpan] wrapping the real content in `children`), including
  /// the debounced-plain-text fallback below, so [_splitForWordHighlight]/
  /// [_applySearchHighlight] (which both walk `span.children`) don't need
  /// to special-case "coloring hasn't caught up yet."
  TextSpan _syntaxSpan(TextStyle baseStyle) {
    final sameTextAsLastCall = _lastSeenText == text;
    final cacheIsCurrent = _syntaxCacheText == text &&
        _syntaxCacheColors == colors &&
        _syntaxCacheStyle == baseStyle;
    _lastSeenText = text;

    if (cacheIsCurrent && _syntaxCacheSpan != null) {
      return _syntaxCacheSpan!;
    }

    // First-ever build, or the text matches the last recompute but colors/
    // style don't (a theme/zoom change, not a keystroke) — recompute
    // immediately. A debounced blank flash only makes sense while the user
    // is actively typing; nobody expects the initial paint or a theme
    // toggle to visibly lag.
    if (_syntaxCacheSpan == null || _syntaxCacheText == text) {
      _debounceTimer?.cancel();
      _recomputeSyntaxCacheNow(baseStyle);
      return _syntaxCacheSpan!;
    }

    // Text differs from the last full recompute — the user is typing.
    // **Real bug caught by a test, not shipped:** an earlier version
    // cancelled+restarted the debounce timer on every call reaching this
    // branch — but `buildTextSpan` can be called again for reasons that
    // have nothing to do with a new keystroke (cursor blink, an unrelated
    // rebuild) while the *same* recompute is still pending, which kept
    // pushing the deadline out and could delay coloring indefinitely.
    // Only touch the timer when the text ALSO differs from the previous
    // call — a genuine new keystroke — otherwise leave the already-
    // counting-down timer alone.
    if (!sameTextAsLastCall || _debounceTimer == null) {
      _debounceTimer?.cancel();
      _debounceTimer = Timer(_debounceDelay, () {
        _recomputeSyntaxCacheNow(baseStyle);
        notifyListeners();
      });
    }
    // Serve the current (correct) text with no color meanwhile — a stale
    // span built from the OLD text can't be reused here, its length
    // wouldn't match [text] anymore and would desync cursor positioning.
    return TextSpan(
        style: baseStyle, children: [TextSpan(text: text, style: baseStyle)]);
  }

  void _recomputeSyntaxCacheNow(TextStyle baseStyle) {
    _syntaxCacheSpan = highlightSql(text, baseStyle, colors);
    _syntaxCacheText = text;
    _syntaxCacheColors = colors;
    _syntaxCacheStyle = baseStyle;
  }

  @override
  TextSpan buildTextSpan(
      {required BuildContext context,
      TextStyle? style,
      required bool withComposing}) {
    var span = _syntaxSpan(style ?? const TextStyle());
    final target = highlightWord;
    if (target != null && target.isNotEmpty) {
      span = TextSpan(
        style: span.style,
        children: [
          for (final child in span.children ?? const <InlineSpan>[])
            ..._splitForWordHighlight(child, target, highlightBackground),
        ],
      );
    }
    if (searchMatches.isNotEmpty) {
      span = _applySearchHighlight(span);
    }
    return span;
  }

  List<TextSpan> _splitForWordHighlight(
      InlineSpan span, String target, Color background) {
    if (span is! TextSpan || span.text == null) {
      return [if (span is TextSpan) span];
    }
    final spanText = span.text!;
    final matches =
        RegExp(r'\b' + RegExp.escape(target) + r'\b').allMatches(spanText);
    if (matches.isEmpty) return [span];

    final pieces = <TextSpan>[];
    var cursor = 0;
    for (final match in matches) {
      if (match.start > cursor) {
        pieces.add(
            TextSpan(text: spanText.substring(cursor, match.start), style: span.style));
      }
      pieces.add(TextSpan(
        text: spanText.substring(match.start, match.end),
        style: (span.style ?? const TextStyle()).copyWith(
          backgroundColor: background,
        ),
      ));
      cursor = match.end;
    }
    if (cursor < spanText.length) {
      pieces.add(TextSpan(text: spanText.substring(cursor), style: span.style));
    }
    return pieces;
  }

  /// Walks [base]'s already-flat children (post word-highlight — every leaf
  /// there is a real `TextSpan` with `.text` set, never further nested,
  /// confirmed by reading both `highlightSql` and [_splitForWordHighlight]
  /// above) while tracking a running absolute offset, splitting only the
  /// children that a [searchMatches] range actually overlaps.
  TextSpan _applySearchHighlight(TextSpan base) {
    var offset = 0;
    final children = <InlineSpan>[];
    for (final child in base.children ?? const <InlineSpan>[]) {
      if (child is! TextSpan || child.text == null) {
        children.add(child);
        continue;
      }
      final childText = child.text!;
      final childStart = offset;
      final childEnd = offset + childText.length;
      offset = childEnd;

      final overlapping = <(TextRange, int)>[
        for (var i = 0; i < searchMatches.length; i++)
          if (searchMatches[i].start < childEnd && searchMatches[i].end > childStart)
            (searchMatches[i], i),
      ];
      if (overlapping.isEmpty) {
        children.add(child);
        continue;
      }
      children.addAll(_splitChildForSearch(child, childText, childStart, overlapping));
    }
    return TextSpan(style: base.style, children: children);
  }

  List<TextSpan> _splitChildForSearch(TextSpan child, String childText,
      int childStart, List<(TextRange, int)> overlapping) {
    final pieces = <TextSpan>[];
    var cursor = 0;
    for (final (match, index) in overlapping) {
      final relStart = (match.start - childStart).clamp(0, childText.length);
      final relEnd = (match.end - childStart).clamp(0, childText.length);
      if (relStart > cursor) {
        pieces.add(TextSpan(
            text: childText.substring(cursor, relStart), style: child.style));
      }
      pieces.add(TextSpan(
        text: childText.substring(relStart, relEnd),
        style: (child.style ?? const TextStyle()).copyWith(
          backgroundColor: index == activeSearchMatchIndex
              ? activeSearchMatchBackground
              : searchMatchBackground,
        ),
      ));
      cursor = relEnd;
    }
    if (cursor < childText.length) {
      pieces.add(TextSpan(text: childText.substring(cursor), style: child.style));
    }
    return pieces;
  }
}
