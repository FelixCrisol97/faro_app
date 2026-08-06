import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../application/consulta_providers.dart';

/// SQL editor zoom (Ctrl+scroll / Ctrl+=/Ctrl+-/Ctrl+0 — `sql_editor.dart`
/// wires the actual key/scroll events to the three methods here). Owns
/// nothing but the read/write access to [sqlEditorFontSizeProvider] — see
/// that provider's doc comment for the `null` = "theme default" contract.
/// No `setState`/rebuild plumbing needed: `sql_editor.dart`'s `build()`
/// already `ref.watch`es the same provider directly, so a change made
/// through here is picked up the normal Riverpod way.
class EditorZoomController {
  EditorZoomController(this._ref);

  final WidgetRef _ref;

  static const step = 1.0;
  static const minFontSize = 8.0;
  static const maxFontSize = 32.0;

  /// The zoomed value if the user has zoomed at all this session,
  /// otherwise [baseFontSize] (the theme's own).
  double effectiveFontSize(double baseFontSize) =>
      _ref.read(sqlEditorFontSizeProvider) ?? baseFontSize;

  void zoomIn(double baseFontSize) => _adjust(baseFontSize, step);

  void zoomOut(double baseFontSize) => _adjust(baseFontSize, -step);

  void reset() => _ref.read(sqlEditorFontSizeProvider.notifier).state = null;

  void _adjust(double baseFontSize, double delta) {
    final next = (effectiveFontSize(baseFontSize) + delta)
        .clamp(minFontSize, maxFontSize);
    _ref.read(sqlEditorFontSizeProvider.notifier).state = next;
  }
}
