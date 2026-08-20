import 'package:flutter/material.dart';
import 'package:flutter_lucide/flutter_lucide.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_accent.dart';
import '../../../core/theme/app_radii.dart';
import '../../../core/theme/app_spacing.dart';
import '../../../core/theme/app_theme.dart';
import '../../../data/providers/settings_providers.dart';
import '../../../shared/widgets/app_card.dart';
import '../../../shared/widgets/app_segmented_control.dart';

/// README.md "5. Apariencia": a settings column.
///
/// **Own resizable width column removed 2026-08-13** (real bug, user
/// screenshot: "RIGHT OVERFLOWED BY 39 PIXELS") — this screen used to be a
/// full top-level tab and carried its own `_width`/drag handle for that
/// reason; once it moved inside `SidePanelOverlay` (2026-08-12), that
/// panel already has its *own* independent resize handle, so this screen
/// had two separate width states fighting each other — whichever was
/// narrower is what actually got drawn, overflowing against the other.
/// Now it just fills whatever width the panel gives it, same as
/// `HistorialScreen`/`FavoritosScreen`.
class AparienciaScreen extends ConsumerWidget {
  const AparienciaScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final typography = context.appTheme.typography;
    final isDark = ref.watch(themeIsDarkProvider);
    final accent = ref.watch(accentProvider);

    return Padding(
      padding: const EdgeInsets.all(AppSpacing.space4),
      child: SingleChildScrollView(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            AppCard(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('Tema', style: typography.heading),
                  const SizedBox(height: AppSpacing.space2),
                  AppSegmentedControl<bool>(
                    value: isDark,
                    onChanged: (value) =>
                        ref.read(themeIsDarkProvider.notifier).set(value),
                    options: const [
                      AppSegmentedOption(
                          value: false, label: 'Claro', icon: LucideIcons.sun),
                      AppSegmentedOption(
                          value: true, label: 'Oscuro', icon: LucideIcons.moon),
                    ],
                  ),
                ],
              ),
            ),
            const SizedBox(height: AppSpacing.space3),
            AppCard(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('Color de acento', style: typography.heading),
                  const SizedBox(height: AppSpacing.space2),
                  Row(
                    children: [
                      for (final option in AppAccent.values)
                        Padding(
                          padding:
                              const EdgeInsets.only(right: AppSpacing.space2),
                          child: _AccentSwatch(
                            accent: option,
                            selected: accent == option,
                            onTap: () =>
                                ref.read(accentProvider.notifier).set(option),
                          ),
                        ),
                    ],
                  ),
                ],
              ),
            ),
            const SizedBox(height: AppSpacing.space3),
            AppCard(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('Atajos de teclado', style: typography.heading),
                  const SizedBox(height: AppSpacing.space2),
                  const _ShortcutGroup(
                    title: 'Consulta',
                    rows: [
                      _ShortcutRow(
                          keys: 'F5', description: 'Ejecutar / cancelar'),
                      _ShortcutRow(
                          keys: 'Ctrl+G',
                          description: 'Guardar el archivo abierto'),
                    ],
                  ),
                  const _ShortcutGroup(
                    title: 'Editor SQL',
                    rows: [
                      _ShortcutRow(
                          keys: 'Ctrl+F', description: 'Buscar en el script'),
                      _ShortcutRow(
                          keys: 'Ctrl + / Ctrl+Rueda',
                          description: 'Acercar (zoom)'),
                      _ShortcutRow(
                          keys: 'Ctrl - / Ctrl+Rueda',
                          description: 'Alejar (zoom)'),
                      _ShortcutRow(
                          keys: 'Ctrl+0', description: 'Restablecer el zoom'),
                      _ShortcutRow(
                          keys: '↑ / ↓',
                          description:
                              'Moverse entre sugerencias de autocompletado'),
                      _ShortcutRow(
                          keys: 'Enter / Tab',
                          description: 'Aceptar la sugerencia'),
                      _ShortcutRow(
                          keys: 'Esc', description: 'Cerrar las sugerencias'),
                    ],
                  ),
                  const _ShortcutGroup(
                    title: 'Buscador del editor (Ctrl+F)',
                    rows: [
                      _ShortcutRow(
                          keys: 'Enter', description: 'Siguiente coincidencia'),
                      _ShortcutRow(
                          keys: 'Shift+Enter',
                          description: 'Coincidencia anterior'),
                      _ShortcutRow(
                          keys: 'Esc', description: 'Cerrar el buscador'),
                    ],
                    isLast: true,
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _ShortcutGroup extends StatelessWidget {
  const _ShortcutGroup(
      {required this.title, required this.rows, this.isLast = false});

  final String title;
  final List<_ShortcutRow> rows;
  final bool isLast;

  @override
  Widget build(BuildContext context) {
    final colors = context.appTheme.colors;
    final typography = context.appTheme.typography;
    return Padding(
      padding: EdgeInsets.only(bottom: isLast ? 0 : AppSpacing.space2),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title,
              style: typography.caption.copyWith(color: colors.textMuted)),
          const SizedBox(height: 2),
          ...rows,
        ],
      ),
    );
  }
}

class _ShortcutRow extends StatelessWidget {
  const _ShortcutRow({required this.keys, required this.description});

  final String keys;
  final String description;

  @override
  Widget build(BuildContext context) {
    final colors = context.appTheme.colors;
    final typography = context.appTheme.typography;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
            decoration: BoxDecoration(
              color: colors.surfaceAlt,
              borderRadius: AppRadii.chipRadius,
              border: Border.all(color: colors.border),
            ),
            child: Text(
              keys,
              style: typography.caption.copyWith(
                fontFamily: 'JetBrainsMono',
                color: colors.text,
                fontSize: 10.5,
              ),
            ),
          ),
          const SizedBox(width: AppSpacing.space2),
          Expanded(
            child: Padding(
              padding: const EdgeInsets.only(top: 2),
              child: Text(description, style: typography.bodySmall),
            ),
          ),
        ],
      ),
    );
  }
}

class _AccentSwatch extends StatelessWidget {
  const _AccentSwatch(
      {required this.accent, required this.selected, required this.onTap});

  final AppAccent accent;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final colors = context.appTheme.colors;

    return InkWell(
      customBorder: const CircleBorder(),
      onTap: onTap,
      child: Container(
        width: 40,
        height: 40,
        padding: const EdgeInsets.all(3),
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          border: selected ? Border.all(color: colors.text, width: 2) : null,
        ),
        child: Container(
            decoration:
                BoxDecoration(shape: BoxShape.circle, color: accent.swatch)),
      ),
    );
  }
}
