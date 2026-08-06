import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:re_editor/re_editor.dart';
import 'package:re_highlight/languages/pgsql.dart';
import 'package:re_highlight/styles/atom-one-dark.dart';
import 'package:re_highlight/styles/atom-one-light.dart';

import '../../../core/theme/app_theme.dart';
import '../../../data/providers/settings_providers.dart';
import '../application/sql_autocomplete.dart';
import 'editor/re_editor_find_panel.dart';

/// SOLO EVALUACIÓN, 2026-07-24 — ver el comentario en `pubspec.yaml` sobre
/// `re_editor`. Pantalla completamente aislada, alcanzable desde un botón
/// temporal en Apariencia (`_openReEditorPrototype`) — no toca
/// `sql_editor.dart` ni ninguna otra parte del editor real. El objetivo es
/// solo dejar sentir el motor de edición real (líneas, folding,
/// buscar/reemplazar integrado, resaltado PL/pgSQL) antes de decidir si
/// vale la pena migrar el editor de verdad.
///
/// El autocompletado de aquí reutiliza la MISMA lógica pura que ya usa el
/// editor real (`application/sql_autocomplete.dart` — `detectAutocompleteTrigger`/
/// `filterSuggestions`), pero contra una lista de nombres de ejemplo fija
/// (no conectada a ningún servidor real) — sólo para probar que el gancho
/// de autocompletado de `re_editor` (`CodeAutocomplete`/
/// `CodeAutocompletePromptsBuilder`) funciona con nuestra lógica sin
/// reescribirla.
class ReEditorPrototypeScreen extends ConsumerStatefulWidget {
  const ReEditorPrototypeScreen({super.key});

  @override
  ConsumerState<ReEditorPrototypeScreen> createState() =>
      _ReEditorPrototypeScreenState();
}

const _kSampleNames = [
  'clientes',
  'productos',
  'ventas',
  'existencias',
  'inventario',
  'movimientos',
  'id',
  'nombre',
  'precio',
  'cantidad',
  'fecha',
  'sku',
  'sucursal_id',
];

const _kSampleSql = '''
-- Prototipo: motor de edición real (re_editor) vs. el TextField a mano
-- Prueba: Ctrl+F para buscar/reemplazar, clic en el triángulo del margen
-- para colapsar el bloque, y escribe "FROM " o "SELECT " para ver el
-- autocompletado conectado a application/sql_autocomplete.dart.

SELECT p.sku, p.nombre, e.cantidad
FROM productos p
JOIN existencias e ON e.sku = p.sku
WHERE e.sucursal_id = 1
ORDER BY p.nombre;

CREATE OR REPLACE FUNCTION fn_resumen_producto(p_sku text)
RETURNS TABLE (sku text, existencia_actual numeric, entradas numeric, salidas numeric)
LANGUAGE plpgsql
AS \$\$
DECLARE
    nRet numeric;
BEGIN
    SELECT cantidad INTO nRet
    FROM existencias
    WHERE sku = p_sku;

    RETURN QUERY
    SELECT p_sku, nRet, 0::numeric, 0::numeric;
END;
\$\$;
''';

class _ReEditorPrototypeScreenState
    extends ConsumerState<ReEditorPrototypeScreen> {
  late final CodeLineEditingController _controller;

  @override
  void initState() {
    super.initState();
    _controller = CodeLineEditingController.fromText(_kSampleSql);
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = context.appTheme.colors;
    final isDark = ref.watch(themeIsDarkProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Prototipo — motor de edición real (re_editor)'),
        leading: IconButton(
          icon: const Icon(Icons.close),
          tooltip: 'Cerrar prototipo',
          onPressed: () => Navigator.of(context).pop(),
        ),
      ),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: Container(
          decoration: BoxDecoration(
            color: colors.surfaceAlt,
            borderRadius: const BorderRadius.all(Radius.circular(10)),
          ),
          clipBehavior: Clip.antiAlias,
          child: CodeAutocomplete(
            viewBuilder: (context, notifier, onSelected) =>
                _AutocompleteList(notifier: notifier, onSelected: onSelected),
            promptsBuilder: _SqlPromptsBuilder(_controller, _kSampleNames),
            child: CodeEditor(
              controller: _controller,
              wordWrap: false,
              style: CodeEditorStyle(
                fontSize: 14,
                fontFamily: 'JetBrainsMono',
                codeTheme: CodeHighlightTheme(
                  languages: {
                    'pgsql': CodeHighlightThemeMode(mode: langPgsql),
                  },
                  theme: isDark ? atomOneDarkTheme : atomOneLightTheme,
                ),
              ),
              indicatorBuilder:
                  (context, editingController, chunkController, notifier) {
                return Row(
                  children: [
                    DefaultCodeLineNumber(
                      controller: editingController,
                      notifier: notifier,
                    ),
                    DefaultCodeChunkIndicator(
                      width: 20,
                      controller: chunkController,
                      notifier: notifier,
                    ),
                  ],
                );
              },
              findBuilder: (context, controller, readOnly) =>
                  CodeFindPanelView(controller: controller, readOnly: readOnly),
            ),
          ),
        ),
      ),
    );
  }
}

/// Conecta el gancho de autocompletado de `re_editor` a la MISMA lógica
/// pura del editor real — ver el doc comment del archivo. `re_editor`
/// entrega la posición del cursor como (línea, columna)
/// ([CodeLineSelection]), pero `detectAutocompleteTrigger` espera el texto
/// completo hasta el cursor como un solo string — [_flatOffset] hace esa
/// conversión sumando el largo de las líneas anteriores. Simplificación
/// aceptable para un prototipo de evaluación (no contempla distintos
/// finales de línea configurados vía `CodeLineOptions`).
class _SqlPromptsBuilder implements CodeAutocompletePromptsBuilder {
  const _SqlPromptsBuilder(this._controller, this._names);

  final CodeLineEditingController _controller;
  final List<String> _names;

  @override
  CodeAutocompleteEditingValue? build(
    BuildContext context,
    CodeLine codeLine,
    CodeLineSelection selection,
  ) {
    if (!selection.isCollapsed) return null;
    final text = _controller.text;
    final offset = _flatOffset(selection);
    if (offset > text.length) return null;
    final upToCursor = text.substring(0, offset);
    final trigger = detectAutocompleteTrigger(upToCursor);
    if (trigger == null) return null;
    final suggestions = filterSuggestions(_names, trigger.partial, 50);
    if (suggestions.isEmpty) return null;
    return CodeAutocompleteEditingValue(
      input: trigger.partial,
      prompts: [for (final name in suggestions) CodeKeywordPrompt(word: name)],
      index: 0,
    );
  }

  int _flatOffset(CodeLineSelection selection) {
    var offset = 0;
    for (var i = 0; i < selection.baseIndex; i++) {
      offset += _controller.codeLines[i].text.length + 1;
    }
    return offset + selection.baseOffset;
  }
}

class _AutocompleteList extends StatelessWidget implements PreferredSizeWidget {
  const _AutocompleteList({required this.notifier, required this.onSelected});

  final ValueNotifier<CodeAutocompleteEditingValue> notifier;
  final ValueChanged<CodeAutocompleteResult> onSelected;

  static const _itemHeight = 28.0;

  @override
  Size get preferredSize => Size(
        220,
        (_itemHeight * notifier.value.prompts.length).clamp(0, 160),
      );

  @override
  Widget build(BuildContext context) {
    final colors = context.appTheme.colors;
    return ValueListenableBuilder<CodeAutocompleteEditingValue>(
      valueListenable: notifier,
      builder: (context, value, _) {
        return Container(
          constraints: BoxConstraints.loose(preferredSize),
          decoration: BoxDecoration(
            color: colors.surface,
            borderRadius: const BorderRadius.all(Radius.circular(6)),
            border: Border.all(color: colors.border),
          ),
          child: ListView.builder(
            padding: EdgeInsets.zero,
            shrinkWrap: true,
            itemCount: value.prompts.length,
            itemBuilder: (context, index) {
              final prompt = value.prompts[index];
              return InkWell(
                onTap: () => onSelected(
                    value.copyWith(index: index).autocomplete),
                child: Container(
                  height: _itemHeight,
                  padding: const EdgeInsets.symmetric(horizontal: 10),
                  alignment: Alignment.centerLeft,
                  color: index == value.index ? colors.accent.soft : null,
                  child: Text(prompt.word,
                      style: TextStyle(
                          fontFamily: 'JetBrainsMono',
                          fontSize: 13,
                          color: colors.text)),
                ),
              );
            },
          ),
        );
      },
    );
  }
}
