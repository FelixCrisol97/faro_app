import 'package:flutter/material.dart';
import 'package:flutter_lucide/flutter_lucide.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_theme.dart';
import '../../../data/models/table_column.dart';
import '../../../shared/widgets/app_button.dart';
import '../../../features/consulta/application/table_columns_provider.dart';

/// Column list for one table/view, lazy-fetched on first "Ver estructura de
/// campos" and kept cached for the rest of the session (same `.family`
/// shape as `schemaExplorerProvider` — see `table_columns_provider.dart`).
class ColumnsList extends ConsumerWidget {
  const ColumnsList({super.key, required this.columnsKey});

  final TableColumnsKey columnsKey;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colors = context.appTheme.colors;
    final typography = context.appTheme.typography;
    final asyncColumns = ref.watch(tableColumnsProvider(columnsKey));

    return asyncColumns.when(
      loading: () => Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(
          children: [
            SizedBox(
              width: 12,
              height: 12,
              child: CircularProgressIndicator(
                  strokeWidth: 2, color: colors.textMuted),
            ),
            const SizedBox(width: 8),
            Text('Cargando columnas…', style: typography.caption),
          ],
        ),
      ),
      error: (error, stackTrace) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(
          children: [
            Icon(LucideIcons.triangle_alert, size: 13, color: colors.warn.base),
            const SizedBox(width: 6),
            Expanded(
              child: Text('No se pudo cargar la estructura',
                  style: typography.caption, overflow: TextOverflow.ellipsis),
            ),
            AppButton(
              label: 'Reintentar',
              variant: AppButtonVariant.ghost,
              onPressed: () =>
                  ref.invalidate(tableColumnsProvider(columnsKey)),
            ),
          ],
        ),
      ),
      data: (columns) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          for (final column in columns)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 2),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // Name + type used to share one Row, with the type label
                  // unconstrained on the right — fine at first, but a
                  // narrow sidebar plus deep indentation (Tablas > tabla >
                  // columna) left too little width for anything past a
                  // short type name, causing a RenderFlex overflow ("RIGHT
                  // OVERFLOWED BY ... PIXELS"). Stacking name and type on
                  // two lines instead means neither ever needs to share
                  // horizontal space with the other, so this can't overflow
                  // regardless of how narrow the sidebar gets.
                  Row(
                    children: [
                      SizedBox(
                        width: 12,
                        child: column.isPrimaryKey
                            ? Icon(LucideIcons.key,
                                size: 10, color: colors.accent.base)
                            : null,
                      ),
                      Expanded(
                        child: Text(column.name,
                            overflow: TextOverflow.ellipsis,
                            style: typography.body
                                .copyWith(fontSize: 12, color: colors.text)),
                      ),
                    ],
                  ),
                  Padding(
                    padding: const EdgeInsets.only(left: 12),
                    child: Text(_typeLabel(column),
                        overflow: TextOverflow.ellipsis,
                        style: typography.caption
                            .copyWith(color: colors.textMuted, fontSize: 11)),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }

  String _typeLabel(TableColumn column) {
    final base = column.characterMaxLength != null
        ? '${column.dataType}(${column.characterMaxLength})'
        : (column.numericPrecision != null && column.numericScale != null
            ? '${column.dataType}(${column.numericPrecision},${column.numericScale})'
            : column.dataType);
    return column.nullable ? base : '$base NOT NULL';
  }
}
