import 'package:flutter/foundation.dart' show kDebugMode;
import 'package:flutter/material.dart';
import 'package:flutter_lucide/flutter_lucide.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_accent.dart';
import '../../../core/theme/app_radii.dart';
import '../../../core/theme/app_spacing.dart';
import '../../../core/theme/app_theme.dart';
import '../../../data/providers/settings_providers.dart';
import '../../../shared/widgets/app_button.dart';
import '../../../shared/widgets/app_card.dart';
import '../../../shared/widgets/app_segmented_control.dart';
import '../../consulta/presentation/re_editor_prototype_screen.dart';

/// README.md "5. Apariencia": a narrow settings column.
class AparienciaScreen extends ConsumerStatefulWidget {
  const AparienciaScreen({super.key});

  @override
  ConsumerState<AparienciaScreen> createState() => _AparienciaScreenState();
}

class _AparienciaScreenState extends ConsumerState<AparienciaScreen> {
  // Session-only (resets on restart), same drag-to-resize convention as
  // `server_sidebar.dart`'s sidebar — user-requested 2026-08-02, this
  // column used to be a hardcoded 340px with no way to widen it.
  double _width = 340;
  static const double _minWidth = 280;
  static const double _maxWidth = 560;

  @override
  Widget build(BuildContext context) {
    final colors = context.appTheme.colors;
    final typography = context.appTheme.typography;
    final isDark = ref.watch(themeIsDarkProvider);
    final accent = ref.watch(accentProvider);

    return Align(
      alignment: Alignment.topLeft,
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.space4),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            SizedBox(
              width: _width,
              // Real bug, user-reported with a screenshot ("BOTTOM OVERFLOWED
              // BY 112 PIXELS"): this column never scrolled, and kept growing
              // this session (atajos de teclado card, then the re_editor
              // prototype card) until it no longer fit a shorter window.
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
                            value: false,
                            label: 'Claro',
                            icon: LucideIcons.sun),
                        AppSegmentedOption(
                            value: true,
                            label: 'Oscuro',
                            icon: LucideIcons.moon),
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
                            keys: 'Ctrl+F',
                            description: 'Buscar en el script'),
                        _ShortcutRow(
                            keys: 'Ctrl + / Ctrl+Rueda',
                            description: 'Acercar (zoom)'),
                        _ShortcutRow(
                            keys: 'Ctrl - / Ctrl+Rueda',
                            description: 'Alejar (zoom)'),
                        _ShortcutRow(
                            keys: 'Ctrl+0',
                            description: 'Restablecer el zoom'),
                        _ShortcutRow(
                            keys: '↑ / ↓',
                            description:
                                'Moverse entre sugerencias de autocompletado'),
                        _ShortcutRow(
                            keys: 'Enter / Tab',
                            description: 'Aceptar la sugerencia'),
                        _ShortcutRow(
                            keys: 'Esc',
                            description: 'Cerrar las sugerencias'),
                      ],
                    ),
                    const _ShortcutGroup(
                      title: 'Buscador del editor (Ctrl+F)',
                      rows: [
                        _ShortcutRow(
                            keys: 'Enter',
                            description: 'Siguiente coincidencia'),
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
              // Real bug fixed 2026-08-03 (AUDITORIA_CODIGO.md): esta tarjeta
              // era alcanzable en builds de producción sin ningún gate — un
              // usuario final podía llegar al prototipo de editor (sin tema
              // Faro, en inglés, no conectado al editor real) solo abriendo
              // Apariencia. Envuelta en `kDebugMode` para que solo aparezca en
              // builds de desarrollo, sin borrar el prototipo todavía — la
              // decisión de si se migra o no sigue pendiente.
              if (kDebugMode) ...[
                const SizedBox(height: AppSpacing.space3),
                AppCard(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('Prototipo (evaluación)', style: typography.heading),
                      const SizedBox(height: AppSpacing.space1),
                      Text(
                        'Motor de edición real (re_editor) para el editor SQL — no conectado todavía al editor de verdad.',
                        style: typography.bodySmall
                            .copyWith(color: colors.textMuted),
                      ),
                      const SizedBox(height: AppSpacing.space2),
                      AppButton(
                        label: 'Probar editor nuevo',
                        icon: LucideIcons.flask_conical,
                        onPressed: () => Navigator.of(context).push(
                          MaterialPageRoute(
                            builder: (context) =>
                                const ReEditorPrototypeScreen(),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
                ],
                ),
              ),
            ),
            _ResizeHandle(
              onDrag: (dx) => setState(() {
                _width = (_width + dx).clamp(_minWidth, _maxWidth);
              }),
            ),
          ],
        ),
      ),
    );
  }
}

/// A thin draggable strip at this column's right edge — same shape as
/// `server_sidebar.dart`'s own `_ResizeHandle` (highlights on hover, wider
/// invisible hit target than its visible 2px line), kept as its own copy
/// rather than a shared widget since the two hosts don't have enough else
/// in common to justify the extra parameterization.
class _ResizeHandle extends StatefulWidget {
  const _ResizeHandle({required this.onDrag});

  final ValueChanged<double> onDrag;

  @override
  State<_ResizeHandle> createState() => _ResizeHandleState();
}

class _ResizeHandleState extends State<_ResizeHandle> {
  bool _hovering = false;

  @override
  Widget build(BuildContext context) {
    final colors = context.appTheme.colors;
    return MouseRegion(
      cursor: SystemMouseCursors.resizeLeftRight,
      onEnter: (_) => setState(() => _hovering = true),
      onExit: (_) => setState(() => _hovering = false),
      child: GestureDetector(
        behavior: HitTestBehavior.translucent,
        onHorizontalDragUpdate: (details) => widget.onDrag(details.delta.dx),
        child: SizedBox(
          width: 6,
          child: Center(
            child: Container(
              width: 2,
              color: _hovering ? colors.accent.base : colors.border,
            ),
          ),
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
