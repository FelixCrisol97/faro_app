import 'package:flutter/material.dart';
import 'package:flutter_lucide/flutter_lucide.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../../core/constants/db_engine.dart';
import '../../../../../core/theme/app_spacing.dart';
import '../../../../../core/theme/app_theme.dart';
import '../../../../../data/models/schema_object.dart';
import '../../../../../shared/widgets/context_menu_label.dart';
import '../../../../../shared/widgets/import_csv_dialog.dart';
import '../../../../../shared/windowing/query_window_launcher.dart';
import '../../../application/consulta_providers.dart';
import '../../../application/object_definition_provider.dart';
import '../../../application/query_tabs_providers.dart';
import '../../../application/schema_explorer_provider.dart';
import '../../../application/sql_script_generator.dart';
import '../../../application/table_columns_provider.dart';
import 'columns_list.dart';
import 'schema_icons.dart';

/// Right-click actions offered per object type — see the plan's "Menú según
/// tipo de objeto": tables get all four, views skip UPDATE (not generically
/// meaningful), routines/triggers only get the CREATE script (no columns).
enum ObjectAction {
  viewColumns,
  selectScript,
  updateScript,
  createScript,
  importCsv,
  openInTab,
  openInNewWindow,
}

/// One catalog object row — table/view/function/procedure/trigger — with
/// its own lazy-loaded inline column list and right-click context menu.
/// Reused as-is both inside a category's per-type list ([SchemaTypeGroup])
/// and inside the flat, mixed-type search results ([SchemaSearchResults]).
class SchemaObjectRow extends ConsumerStatefulWidget {
  const SchemaObjectRow({
    super.key,
    required this.dbKey,
    required this.engine,
    required this.object,
    required this.defaultSchema,
    this.showTypeIcon = false,
  });

  final SchemaExplorerKey dbKey;
  final DbEngine engine;
  final SchemaObject object;
  final String defaultSchema;

  /// Only set from search results — a flat list mixing every object type
  /// needs its own per-row type icon to stay legible; inside a
  /// [SchemaTypeGroup] the group header's icon already says which type
  /// every row underneath it is, so a second one per row would be noise.
  final bool showTypeIcon;

  @override
  ConsumerState<SchemaObjectRow> createState() => _SchemaObjectRowState();
}

class _SchemaObjectRowState extends ConsumerState<SchemaObjectRow> {
  bool _columnsExpanded = false;

  TableColumnsKey get _columnsKey => (
        serverId: widget.dbKey.serverId,
        databaseId: widget.dbKey.databaseId,
        schema: widget.object.schema,
        objectName: widget.object.name,
      );

  ObjectDefinitionKey get _definitionKey => (
        serverId: widget.dbKey.serverId,
        databaseId: widget.dbKey.databaseId,
        schema: widget.object.schema,
        objectName: widget.object.name,
        type: widget.object.type,
        parentTable: widget.object.parentTable,
      );

  @override
  Widget build(BuildContext context) {
    final colors = context.appTheme.colors;
    final typography = context.appTheme.typography;
    final object = widget.object;
    // Only tables/views have a column list — the toggle chevron only makes
    // sense (and only reserves layout space) for those.
    final canShowColumns =
        object.type == SchemaObjectType.table || object.type == SchemaObjectType.view;

    const rowRadius = BorderRadius.all(Radius.circular(4));
    Offset? tapPosition;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(
              horizontal: AppSpacing.space1, vertical: 3),
          child: Row(
            children: [
              // A dedicated, always-visible toggle for the column list —
              // "Ver estructura de campos" in the context menu still works
              // too, but going back through a right-click menu just to
              // collapse what you just opened was the reported problem.
              // This used to be nested inside the same InkWell that opens
              // the context menu (below); nesting them meant this chevron's
              // taps were unreliably won by the outer, row-wide InkWell
              // instead — kept as a sibling now, not a descendant, so
              // there's no overlapping tap region left to compete over.
              if (canShowColumns)
                InkWell(
                  borderRadius: const BorderRadius.all(Radius.circular(4)),
                  onTap: () =>
                      setState(() => _columnsExpanded = !_columnsExpanded),
                  child: SizedBox(
                    width: 18,
                    height: 18,
                    child: AnimatedRotation(
                      turns: _columnsExpanded ? 0.25 : 0,
                      duration: const Duration(milliseconds: 120),
                      child: Icon(LucideIcons.chevron_right,
                          size: 12, color: colors.textMuted),
                    ),
                  ),
                )
              else
                const SizedBox(width: 18),
              if (widget.showTypeIcon) ...[
                Icon(iconForType(object.type), size: 11, color: colors.textMuted),
                const SizedBox(width: 4),
              ],
              Expanded(
                child: Material(
                  color: Colors.transparent,
                  borderRadius: rowRadius,
                  child: InkWell(
                    borderRadius: rowRadius,
                    // A bare GestureDetector gives no visual confirmation a
                    // click landed — InkWell's ripple is what makes "yes,
                    // that's the row you hit" visible, for either button.
                    // Left click and right click both open the same menu
                    // (right-click is the discoverable convention, but not
                    // every user tries it first).
                    onTapDown: (details) => tapPosition = details.globalPosition,
                    onSecondaryTapDown: (details) =>
                        tapPosition = details.globalPosition,
                    onTap: () => _showContextMenu(tapPosition!),
                    onSecondaryTap: () => _showContextMenu(tapPosition!),
                    child: Text(
                      object.schema == widget.defaultSchema
                          ? object.name
                          : '${object.schema}.${object.name}',
                      overflow: TextOverflow.ellipsis,
                      style: typography.body
                          .copyWith(fontSize: 12, color: colors.text),
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
        if (_columnsExpanded)
          Padding(
            padding: const EdgeInsets.only(left: 32),
            child: ColumnsList(columnsKey: _columnsKey),
          ),
      ],
    );
  }

  Future<void> _showContextMenu(Offset globalPosition) async {
    final action = await showPositionedMenu<ObjectAction>(
      context,
      globalPosition,
      _menuItemsFor(widget.object.type),
    );
    if (action == null || !mounted) return;
    switch (action) {
      case ObjectAction.viewColumns:
        setState(() => _columnsExpanded = !_columnsExpanded);
      case ObjectAction.selectScript:
        _writeToHomeEditor(generateSelectScript(
            widget.engine, widget.object.schema, widget.object.name));
      case ObjectAction.updateScript:
        await _generateUpdateScript();
      case ObjectAction.createScript:
        await _generateCreateScript();
      case ObjectAction.importCsv:
        await showImportCsvDialog(
          context,
          ref,
          serverId: widget.dbKey.serverId,
          databaseId: widget.dbKey.databaseId,
          schema: widget.object.schema,
          table: widget.object.name,
        );
      case ObjectAction.openInTab:
        ref.read(queryTabsProvider.notifier).openTab(
          serverId: widget.dbKey.serverId,
          databaseId: widget.dbKey.databaseId,
        );
      case ObjectAction.openInNewWindow:
        await openQueryWindow(
          serverId: widget.dbKey.serverId,
          databaseId: widget.dbKey.databaseId,
        );
    }
  }

  /// Generated scripts always land in the "Consulta" home pane, never a
  /// query tab — so if the user was looking at a tab when they generated
  /// one, they're switched back to home too, or the script would silently
  /// write into a pane that isn't on screen.
  void _writeToHomeEditor(String sql) {
    ref.read(sqlEditorProvider.notifier).loadText(sql);
    ref.read(queryTabsProvider.notifier).activate(null);
  }

  List<PopupMenuEntry<ObjectAction>> _menuItemsFor(SchemaObjectType type) {
    switch (type) {
      case SchemaObjectType.table:
        return const [
          PopupMenuItem(
              value: ObjectAction.viewColumns,
              child: ContextMenuLabel(
                  LucideIcons.columns_3, 'Ver estructura de campos')),
          PopupMenuItem(
              value: ObjectAction.selectScript,
              child: ContextMenuLabel(LucideIcons.search, 'Generar SELECT')),
          PopupMenuItem(
              value: ObjectAction.updateScript,
              child: ContextMenuLabel(LucideIcons.pencil, 'Generar UPDATE')),
          PopupMenuItem(
              value: ObjectAction.createScript,
              child: ContextMenuLabel(
                  LucideIcons.file_code, 'Generar script CREATE')),
          PopupMenuItem(
              value: ObjectAction.importCsv,
              child: ContextMenuLabel(LucideIcons.upload, 'Importar CSV…')),
          PopupMenuItem(
              value: ObjectAction.openInTab,
              child:
                  ContextMenuLabel(LucideIcons.panel_top, 'Abrir en pestaña')),
          PopupMenuItem(
              value: ObjectAction.openInNewWindow,
              child: ContextMenuLabel(
                  LucideIcons.external_link, 'Abrir en nueva ventana')),
        ];
      case SchemaObjectType.view:
        return const [
          PopupMenuItem(
              value: ObjectAction.viewColumns,
              child: ContextMenuLabel(
                  LucideIcons.columns_3, 'Ver estructura de campos')),
          PopupMenuItem(
              value: ObjectAction.selectScript,
              child: ContextMenuLabel(LucideIcons.search, 'Generar SELECT')),
          PopupMenuItem(
              value: ObjectAction.createScript,
              child: ContextMenuLabel(
                  LucideIcons.file_code, 'Generar script CREATE')),
          PopupMenuItem(
              value: ObjectAction.openInTab,
              child:
                  ContextMenuLabel(LucideIcons.panel_top, 'Abrir en pestaña')),
          PopupMenuItem(
              value: ObjectAction.openInNewWindow,
              child: ContextMenuLabel(
                  LucideIcons.external_link, 'Abrir en nueva ventana')),
        ];
      case SchemaObjectType.function:
      case SchemaObjectType.procedure:
      case SchemaObjectType.trigger:
        return const [
          PopupMenuItem(
              value: ObjectAction.createScript,
              child: ContextMenuLabel(
                  LucideIcons.file_code, 'Generar script CREATE')),
          PopupMenuItem(
              value: ObjectAction.openInTab,
              child:
                  ContextMenuLabel(LucideIcons.panel_top, 'Abrir en pestaña')),
          PopupMenuItem(
              value: ObjectAction.openInNewWindow,
              child: ContextMenuLabel(
                  LucideIcons.external_link, 'Abrir en nueva ventana')),
        ];
    }
  }

  Future<void> _generateUpdateScript() async {
    try {
      final columns = await ref.read(tableColumnsProvider(_columnsKey).future);
      if (!mounted) return;
      _writeToHomeEditor(generateUpdateScript(
          widget.engine, widget.object.schema, widget.object.name, columns));
    } catch (e) {
      _showError('No se pudo generar el UPDATE: $e');
    }
  }

  Future<void> _generateCreateScript() async {
    try {
      final String sql;
      if (widget.object.type == SchemaObjectType.table) {
        final columns =
            await ref.read(tableColumnsProvider(_columnsKey).future);
        sql = generateCreateTableScript(
            widget.engine, widget.object.schema, widget.object.name, columns);
      } else {
        sql = await ref.read(objectDefinitionProvider(_definitionKey).future);
      }
      if (!mounted) return;
      _writeToHomeEditor(sql.isEmpty
          ? '-- No se encontró la definición de ${widget.object.name}'
          : sql);
    } catch (e) {
      _showError('No se pudo generar el script: $e');
    }
  }

  void _showError(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(message)));
  }
}
