import 'package:flutter/painting.dart';

import 'app_accent.dart';

/// Fixed semantic color (success/error/warning) — same meaning regardless
/// of the user's chosen accent, per the 2026-07-17 redesign
/// (design_system/design_handoff_faro): "Semantic colors (independent of
/// the brand accent, same in both themes' intent)".
class AppSemanticColor {
  const AppSemanticColor(
      {required this.base, required this.soft, required this.softText});

  final Color base;
  final Color soft;
  final Color softText;
}

/// Resolved color roles for one theme brightness + accent combination.
///
/// design_system/design_handoff_faro/README.md "Design tokens > Colors" is
/// the single source of truth for these values — don't recompute by eye,
/// re-copy from there if they ever change.
class AppColors {
  const AppColors({
    required this.background,
    required this.surface,
    required this.surfaceAlt,
    required this.text,
    required this.textMuted,
    required this.border,
    required this.accent,
    required this.success,
    required this.error,
    required this.warn,
  });

  factory AppColors.light(AppAccent accent) => AppColors(
        background: const Color(0xFFF8FAFC),
        surface: const Color(0xFFFFFFFF),
        surfaceAlt: const Color(0xFFF1F5F9),
        text: const Color(0xFF0F172A),
        textMuted: const Color(0xFF475569),
        border: const Color(0xFFE2E8F0),
        accent: accent.light,
        success: const AppSemanticColor(
          base: Color(0xFF059669),
          soft: Color(0xFFECFDF5),
          softText: Color(0xFF047857),
        ),
        error: const AppSemanticColor(
            base: Color(0xFFDC2626),
            soft: Color(0xFFFEF2F2),
            softText: Color(0xFFB91C1C)),
        // Warning/dev-mode is always solid amber with white text, not tinted —
        // `soft`/`softText` here are only used for the toolbar's "Desarrollo"
        // status pill (a tinted variant of the same hue), not the banner.
        warn: const AppSemanticColor(
            base: Color(0xFFB45309),
            soft: Color(0xFFFEF3C7),
            softText: Color(0xFF92400E)),
      );

  factory AppColors.dark(AppAccent accent) => AppColors(
        background: const Color(0xFF0F172A),
        surface: const Color(0xFF1E293B),
        surfaceAlt: const Color(0xFF334155),
        text: const Color(0xFFF1F5F9),
        textMuted: const Color(0xFFAEBACB),
        border: const Color(0xFF334155),
        accent: accent.dark,
        success: const AppSemanticColor(
          base: Color(0xFF34D399),
          soft: Color(0x2934D399),
          softText: Color(0xFF6EE7B7),
        ),
        error: const AppSemanticColor(
            base: Color(0xFFF87171),
            soft: Color(0x29F87171),
            softText: Color(0xFFFCA5A5)),
        warn: const AppSemanticColor(
            base: Color(0xFFB45309),
            soft: Color(0x38B45309),
            softText: Color(0xFFFCD34D)),
      );

  final Color background;
  final Color surface;

  /// `--surface-2` — chips, table header row, hover states.
  final Color surfaceAlt;
  final Color text;
  final Color textMuted;
  final Color border;
  final AppAccentPalette accent;
  final AppSemanticColor success;
  final AppSemanticColor error;
  final AppSemanticColor warn;
}
