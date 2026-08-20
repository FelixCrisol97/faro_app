import 'package:flutter/painting.dart';

import 'app_colors.dart';

/// Text styles for Sora (headings) over Manrope (body/UI) — the
/// 2026-07-17 redesign (design_system/design_handoff_faro), replacing the
/// earlier Caprasimo/Figtree pairing. Fonts must be bundled as assets (see
/// pubspec.yaml `fonts:`), never fetched at runtime.
class AppTypography {
  AppTypography(this.colors);

  final AppColors colors;

  static const String headingFamily = 'Sora';
  static const String bodyFamily = 'Manrope';

  TextStyle get h1 => TextStyle(
        fontFamily: headingFamily,
        fontWeight: FontWeight.w700,
        fontSize: 32,
        height: 1.2,
        color: colors.text,
      );

  TextStyle get h2 => TextStyle(
        fontFamily: headingFamily,
        fontWeight: FontWeight.w700,
        fontSize: 26,
        height: 1.2,
        color: colors.text,
      );

  TextStyle get h3 => TextStyle(
        fontFamily: headingFamily,
        fontWeight: FontWeight.w600,
        fontSize: 22,
        height: 1.25,
        color: colors.text,
      );

  TextStyle get h4 => TextStyle(
        fontFamily: headingFamily,
        fontWeight: FontWeight.w600,
        fontSize: 18,
        height: 1.25,
        color: colors.text,
      );

  /// Card titles, dialog titles, nav brand — same voice as h4.
  TextStyle get heading => TextStyle(
        fontFamily: headingFamily,
        fontWeight: FontWeight.w600,
        fontSize: 16,
        height: 1.25,
        color: colors.text,
      );

  TextStyle get body => TextStyle(
        fontFamily: bodyFamily,
        fontWeight: FontWeight.w500,
        fontSize: 14.5,
        height: 1.5,
        color: colors.text,
      );

  TextStyle get bodySmall => TextStyle(
        fontFamily: bodyFamily,
        fontWeight: FontWeight.w500,
        fontSize: 13,
        height: 1.4,
        color: colors.text,
      );

  /// Captions — e.g. "Engine · N bases", "N de M bases seleccionadas".
  TextStyle get caption => TextStyle(
        fontFamily: bodyFamily,
        fontWeight: FontWeight.w600,
        fontSize: 11.5,
        height: 1.4,
        letterSpacing: 0.02,
        color: colors.textMuted,
      );

  /// Monospace — SQL editor, query previews in Historial/Favoritos. Kept as
  /// JetBrains Mono (added earlier the same day to fix `fontFamily:
  /// 'monospace'` not resolving to any real font) — the redesign spec only
  /// calls out heading/body fonts, so there's no reason to change this.
  TextStyle get monospace => TextStyle(
        fontFamily: 'JetBrainsMono',
        fontFeatures: const [FontFeature.tabularFigures()],
        fontSize: 14,
        height: 1.5,
        color: colors.text,
      );

  /// Button label voice — Manrope 600, per styles.css `.btn` in the new
  /// spec (buttons no longer use the heading font).
  TextStyle get button => const TextStyle(
        fontFamily: bodyFamily,
        fontWeight: FontWeight.w600,
        fontSize: 13.5,
        height: 1.2,
      );
}
