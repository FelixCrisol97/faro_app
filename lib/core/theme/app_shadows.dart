import 'package:flutter/painting.dart';

/// Elevation — soft, ink-tinted shadows (`rgba(15,23,42, …)`, never pure
/// black). 2026-07-17 redesign (design_system/design_handoff_faro) — same
/// three steps as before, new values/tint color. Fixed constants (not
/// theme-dependent): the spec uses the same slate-900 tint in both themes.
class AppShadows {
  const AppShadows._();

  /// Card lift — two soft layers.
  static const List<BoxShadow> sm = [
    BoxShadow(color: Color(0x0A0F172A), offset: Offset(0, 1), blurRadius: 2),
    BoxShadow(color: Color(0x0A0F172A), offset: Offset(0, 4), blurRadius: 12),
  ];

  /// Dropdowns / autocomplete.
  static const List<BoxShadow> md = [
    BoxShadow(color: Color(0x260F172A), offset: Offset(0, 10), blurRadius: 30),
  ];

  /// Dialogs.
  static const List<BoxShadow> lg = [
    BoxShadow(color: Color(0x400F172A), offset: Offset(0, 20), blurRadius: 50),
  ];

  /// Dialog backdrop scrim.
  static const Color backdrop = Color(0x8C0F172A); // rgba(15,23,42,0.55)
}
