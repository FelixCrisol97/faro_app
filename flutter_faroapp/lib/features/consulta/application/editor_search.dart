import 'package:flutter/services.dart' show TextRange;

/// Pure, unit-testable match-finding for the SQL editor's find bar
/// (`widgets/editor/search_bar.dart`) — no Flutter widget/Riverpod
/// dependency, same "pure function, no ProviderContainer needed"
/// philosophy as `sql_statement_resolver.dart`/`sql_autocomplete.dart`.
///
/// Case-insensitive, non-overlapping substring search over the whole
/// [text] (not just what's currently on screen) — the point for a client's
/// ~1000-line scripts is finding a keyword without scrolling/eyeballing
/// for it. Non-overlapping means searching "aa" in "aaaa" finds 2 matches,
/// not 3 — the usual find-in-editor convention, and simpler to reason
/// about when stepping between results. Returns an empty list for an empty
/// [query].
List<TextRange> findMatches(String text, String query) {
  if (query.isEmpty) return const [];
  final lowerText = text.toLowerCase();
  final lowerQuery = query.toLowerCase();
  final matches = <TextRange>[];
  var start = 0;
  while (true) {
    final index = lowerText.indexOf(lowerQuery, start);
    if (index == -1) break;
    matches.add(TextRange(start: index, end: index + query.length));
    start = index + query.length;
  }
  return matches;
}
