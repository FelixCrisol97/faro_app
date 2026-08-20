import 'package:flutter/material.dart';

import 'app_accent.dart';
import 'app_colors.dart';
import 'app_radii.dart';
import 'app_typography.dart';

/// Builds a Flutter `ThemeData` from the Organic design tokens for a given
/// brightness + accent. Screens should read colors/type via
/// `Theme.of(context).extension<AppThemeExtension>()!` rather than
/// hardcoding hex values — see README.md "Fidelity": tokens are final,
/// recreate with Flutter's own widgets.
class AppTheme {
  const AppTheme._();

  static ThemeData build(
      {required Brightness brightness, required AppAccent accent}) {
    final colors = brightness == Brightness.light
        ? AppColors.light(accent)
        : AppColors.dark(accent);
    final typography = AppTypography(colors);

    final colorScheme = ColorScheme(
      brightness: brightness,
      primary: colors.accent.base,
      onPrimary: Colors.white,
      secondary: colors.accent.hover,
      onSecondary: Colors.white,
      error: colors.error.base,
      onError: Colors.white,
      surface: colors.surface,
      onSurface: colors.text,
    );

    return ThemeData(
      useMaterial3: true,
      brightness: brightness,
      colorScheme: colorScheme,
      scaffoldBackgroundColor: colors.background,
      canvasColor: colors.background,
      dividerColor: colors.border,
      fontFamily: AppTypography.bodyFamily,
      textTheme: TextTheme(
        displayLarge: typography.h1,
        displayMedium: typography.h2,
        displaySmall: typography.h3,
        headlineMedium: typography.h4,
        titleMedium: typography.heading,
        bodyLarge: typography.body,
        bodyMedium: typography.body,
        bodySmall: typography.bodySmall,
        labelSmall: typography.caption,
        labelLarge: typography.button,
      ),
      cardTheme: CardThemeData(
        color: colors.surface,
        elevation: 0,
        shape: const RoundedRectangleBorder(
            borderRadius: AppRadii.containerRadius),
      ),
      dialogTheme: DialogThemeData(
        backgroundColor: colors.surface,
        shape: const RoundedRectangleBorder(
            borderRadius: AppRadii.containerRadius),
        titleTextStyle: typography.h4,
      ),
      // Every right-click context menu in the app (schema tree, results
      // grid cells, database rows, tabs) goes through `showMenu`/
      // `PopupMenuButton` with no per-call-site styling — user-reported
      // ("se ven muy básicos"), the plain Material default (flat gray,
      // square corners, no border) was the actual cause, same class of fix
      // as `cardTheme`/`dialogTheme` above: one shared theme instead of
      // restyling every call site individually.
      popupMenuTheme: PopupMenuThemeData(
        color: colors.surface,
        surfaceTintColor: Colors.transparent,
        elevation: 6,
        shape: RoundedRectangleBorder(
          borderRadius: AppRadii.containerRadius,
          side: BorderSide(color: colors.border),
        ),
        textStyle: typography.body,
      ),
      // Dialog/form inputs sit on `--bg` even though the dialog itself is
      // `--surface`, so the field reads as recessed — the SQL editor
      // textarea overrides its own fill (surface-2) directly in
      // sql_editor.dart, this is just the generic default.
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: colors.background,
        border: OutlineInputBorder(
          borderRadius: AppRadii.controlRadius,
          borderSide: BorderSide(color: colors.border),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: AppRadii.controlRadius,
          borderSide: BorderSide(color: colors.accent.base, width: 2),
        ),
      ),
      elevatedButtonTheme: ElevatedButtonThemeData(
        style: ElevatedButton.styleFrom(
          backgroundColor: colors.accent.base,
          foregroundColor: Colors.white,
          textStyle: typography.button,
          shape: const RoundedRectangleBorder(
              borderRadius: AppRadii.controlRadius),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          foregroundColor: colors.text,
          side: BorderSide(color: colors.border),
          textStyle: typography.button,
          shape: const RoundedRectangleBorder(
              borderRadius: AppRadii.controlRadius),
        ),
      ),
      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(
          foregroundColor: colors.accent.base,
          textStyle: typography.button,
        ),
      ),
      focusColor: colors.accent.base,
      extensions: [AppThemeExtension(colors: colors, typography: typography)],
    );
  }
}

/// Theme extension carrying the full Organic token set (ramps, spacing-aware
/// typography) beyond what `ColorScheme`/`TextTheme` can express directly —
/// e.g. the neutral/accent-2 ramps, muted text, per-role divider color.
class AppThemeExtension extends ThemeExtension<AppThemeExtension> {
  const AppThemeExtension({required this.colors, required this.typography});

  final AppColors colors;
  final AppTypography typography;

  @override
  AppThemeExtension copyWith({AppColors? colors, AppTypography? typography}) {
    return AppThemeExtension(
      colors: colors ?? this.colors,
      typography: typography ?? this.typography,
    );
  }

  @override
  AppThemeExtension lerp(ThemeExtension<AppThemeExtension>? other, double t) {
    // Colors/typography swap discretely with the ~250ms crossfade handled by
    // an AnimatedTheme at the call site (README.md "Interactions & behavior");
    // no meaningful in-between value to interpolate here.
    if (other is! AppThemeExtension) return this;
    return t < 0.5 ? this : other;
  }
}

/// Convenience accessor: `context.appTheme.colors.accent.base`.
extension AppThemeContext on BuildContext {
  AppThemeExtension get appTheme =>
      Theme.of(this).extension<AppThemeExtension>()!;
}
