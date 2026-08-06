import 'dart:ui';

/// One resolved accent palette (already picked for the current theme
/// brightness) — base/hover/active for solid buttons and interactive
/// states, soft/softText for tinted backgrounds (selected rows, chips).
class AppAccentPalette {
  const AppAccentPalette({
    required this.base,
    required this.hover,
    required this.active,
    required this.soft,
    required this.softText,
  });

  final Color base;
  final Color hover;
  final Color active;
  final Color soft;
  final Color softText;
}

/// The app-wide accent choice (Apariencia screen) — six brand hues, each
/// with a light-mode and a brighter/more-saturated dark-mode variant so
/// accents stay legible on a dark ground instead of looking washed out.
/// A single setting, not per-element — every screen reads [resolve].
enum AppAccent {
  indigo(
    label: 'Acento índigo',
    swatch: Color(0xFF6366F1),
    light: AppAccentPalette(
      base: Color(0xFF6366F1),
      hover: Color(0xFF4F46E5),
      active: Color(0xFF4338CA),
      soft: Color(0xFFEEF2FF),
      softText: Color(0xFF4338CA),
    ),
    dark: AppAccentPalette(
      base: Color(0xFF818CF8),
      hover: Color(0xFFA5B4FC),
      active: Color(0xFF6366F1),
      soft: Color(0x2E818CF8),
      softText: Color(0xFFC7D2FE),
    ),
  ),
  violet(
    label: 'Acento violeta',
    swatch: Color(0xFF8B5CF6),
    light: AppAccentPalette(
      base: Color(0xFF8B5CF6),
      hover: Color(0xFF7C3AED),
      active: Color(0xFF6D28D9),
      soft: Color(0xFFF5F3FF),
      softText: Color(0xFF6D28D9),
    ),
    dark: AppAccentPalette(
      base: Color(0xFFA78BFA),
      hover: Color(0xFFC4B5FD),
      active: Color(0xFF8B5CF6),
      soft: Color(0x2EA78BFA),
      softText: Color(0xFFDDD6FE),
    ),
  ),
  blue(
    label: 'Acento azul',
    swatch: Color(0xFF2563EB),
    light: AppAccentPalette(
      base: Color(0xFF2563EB),
      hover: Color(0xFF1D4ED8),
      active: Color(0xFF1E40AF),
      soft: Color(0xFFEFF6FF),
      softText: Color(0xFF1D4ED8),
    ),
    dark: AppAccentPalette(
      base: Color(0xFF60A5FA),
      hover: Color(0xFF93C5FD),
      active: Color(0xFF3B82F6),
      soft: Color(0x2E60A5FA),
      softText: Color(0xFFBFDBFE),
    ),
  ),
  teal(
    label: 'Acento teal',
    swatch: Color(0xFF0D9488),
    light: AppAccentPalette(
      base: Color(0xFF0D9488),
      hover: Color(0xFF0F766E),
      active: Color(0xFF115E59),
      soft: Color(0xFFF0FDFA),
      softText: Color(0xFF0F766E),
    ),
    dark: AppAccentPalette(
      base: Color(0xFF2DD4BF),
      hover: Color(0xFF5EEAD4),
      active: Color(0xFF14B8A6),
      soft: Color(0x2E2DD4BF),
      softText: Color(0xFF99F6E4),
    ),
  ),
  rose(
    label: 'Acento rosa',
    swatch: Color(0xFFE11D48),
    light: AppAccentPalette(
      base: Color(0xFFE11D48),
      hover: Color(0xFFBE123C),
      active: Color(0xFF9F1239),
      soft: Color(0xFFFFF1F2),
      softText: Color(0xFFBE123C),
    ),
    dark: AppAccentPalette(
      base: Color(0xFFFB7185),
      hover: Color(0xFFFDA4AF),
      active: Color(0xFFF43F5E),
      soft: Color(0x2EFB7185),
      softText: Color(0xFFFECDD3),
    ),
  ),
  amber(
    label: 'Acento ámbar',
    swatch: Color(0xFFD97706),
    light: AppAccentPalette(
      base: Color(0xFFD97706),
      hover: Color(0xFFB45309),
      active: Color(0xFF92400E),
      soft: Color(0xFFFFFBEB),
      softText: Color(0xFFB45309),
    ),
    dark: AppAccentPalette(
      base: Color(0xFFFBBF24),
      hover: Color(0xFFFCD34D),
      active: Color(0xFFF59E0B),
      soft: Color(0x2EFBBF24),
      softText: Color(0xFFFDE68A),
    ),
  );

  const AppAccent(
      {required this.label,
      required this.swatch,
      required this.light,
      required this.dark});

  /// Shown on the Apariencia picker button — same in both themes.
  final Color swatch;
  final String label;
  final AppAccentPalette light;
  final AppAccentPalette dark;

  AppAccentPalette resolve(Brightness brightness) =>
      brightness == Brightness.dark ? dark : light;
}
