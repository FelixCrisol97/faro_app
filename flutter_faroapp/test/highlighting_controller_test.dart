import 'package:faro/features/consulta/application/sql_syntax_highlighter.dart';
import 'package:faro/features/consulta/presentation/widgets/editor/highlighting_controller.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

const _colors = SqlHighlightColors(
  keyword: Color(0xFF111111),
  string: Color(0xFF222222),
  comment: Color(0xFF333333),
  number: Color(0xFF444444),
);

TextSpan? _findSpanWithText(InlineSpan span, String text) {
  if (span is TextSpan) {
    if (span.text == text) return span;
    for (final child in span.children ?? const <InlineSpan>[]) {
      final found = _findSpanWithText(child, text);
      if (found != null) return found;
    }
  }
  return null;
}

void main() {
  // Regression coverage for the 2026-07-24 perf fix (see
  // HighlightingController's class doc comment): debouncing the expensive
  // `highlightSql` recompute must never let the *text* Flutter paints
  // desync from the controller's real text — only the coloring may lag.
  testWidgets(
      'buildTextSpan always reflects the current text immediately, even '
      'while syntax coloring is debounced', (tester) async {
    late BuildContext capturedContext;
    await tester.pumpWidget(MaterialApp(
      home: Builder(builder: (context) {
        capturedContext = context;
        return const SizedBox();
      }),
    ));

    final controller = HighlightingController(text: 'select 1')
      ..colors = _colors;
    addTearDown(controller.dispose);

    // First build: nothing cached yet, so this recomputes synchronously —
    // colored immediately, no debounce flash on initial paint.
    var span =
        controller.buildTextSpan(context: capturedContext, withComposing: false);
    expect(span.toPlainText(), 'select 1');
    expect(_findSpanWithText(span, 'select')?.style?.color, _colors.keyword);

    // Simulate a fast keystroke: text changes.
    controller.text = 'select 1 from t';
    span =
        controller.buildTextSpan(context: capturedContext, withComposing: false);
    // The text must be correct RIGHT AWAY — this is the property that
    // actually matters (a stale span here would desync the visible text
    // from the real cursor/selection).
    expect(span.toPlainText(), 'select 1 from t');
    // But coloring hasn't caught up yet — 'from' isn't split out as its
    // own colored span (the whole thing is still one plain span).
    expect(_findSpanWithText(span, 'from'), isNull);

    // Still within the debounce window — same story.
    await tester.pump(const Duration(milliseconds: 60));
    span =
        controller.buildTextSpan(context: capturedContext, withComposing: false);
    expect(span.toPlainText(), 'select 1 from t');
    expect(_findSpanWithText(span, 'from'), isNull);

    // Past the debounce delay — the pending Timer fires, recomputes, and
    // calls notifyListeners(); the next buildTextSpan call sees real color.
    await tester.pump(const Duration(milliseconds: 100));
    span =
        controller.buildTextSpan(context: capturedContext, withComposing: false);
    expect(span.toPlainText(), 'select 1 from t');
    expect(_findSpanWithText(span, 'from')?.style?.color, _colors.keyword);
  });

  testWidgets(
      'a theme/zoom change (same text) recomputes immediately, not debounced',
      (tester) async {
    late BuildContext capturedContext;
    await tester.pumpWidget(MaterialApp(
      home: Builder(builder: (context) {
        capturedContext = context;
        return const SizedBox();
      }),
    ));

    final controller = HighlightingController(text: 'select 1')
      ..colors = _colors;
    addTearDown(controller.dispose);
    controller.buildTextSpan(context: capturedContext, withComposing: false);

    const newColors = SqlHighlightColors(
      keyword: Color(0xFF999999),
      string: Color(0xFF888888),
      comment: Color(0xFF777777),
      number: Color(0xFF666666),
    );
    controller.colors = newColors;
    final span =
        controller.buildTextSpan(context: capturedContext, withComposing: false);
    // No pump/wait at all — must already reflect the new color.
    expect(_findSpanWithText(span, 'select')?.style?.color, newColors.keyword);
  });
}
