import 'package:flutter/material.dart';
import 'package:flutter_lucide/flutter_lucide.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../../core/constants/db_engine.dart';
import '../../../../../core/theme/app_theme.dart';
import '../../../../../data/models/schema_object.dart';
import '../../../../../shared/widgets/app_button.dart';
import '../../../application/schema_explorer_provider.dart';
import 'schema_icons.dart';
import 'schema_object_list.dart';

class SchemaTypeGroup extends ConsumerStatefulWidget {
  const SchemaTypeGroup({
    super.key,
    required this.dbKey,
    required this.engine,
    required this.type,
    required this.defaultSchema,
    required this.expanded,
    required this.onToggle,
  });

  final SchemaExplorerKey dbKey;
  final DbEngine engine;
  final SchemaObjectType type;
  final String defaultSchema;
  final bool expanded;
  final VoidCallback onToggle;

  @override
  ConsumerState<SchemaTypeGroup> createState() => _SchemaTypeGroupState();
}

class _SchemaTypeGroupState extends ConsumerState<SchemaTypeGroup> {
  SchemaTypeExplorerKey get _typeKey => (
        serverId: widget.dbKey.serverId,
        databaseId: widget.dbKey.databaseId,
        type: widget.type,
      );

  @override
  Widget build(BuildContext context) {
    final colors = context.appTheme.colors;
    final typography = context.appTheme.typography;
    const rowRadius = BorderRadius.all(Radius.circular(6));

    // The whole point of the per-type split: only touch this category's
    // provider (and thus only run its query) once its header is actually
    // expanded. Collapsed groups never call `ref.watch` at all, so a
    // database with thousands of functions costs nothing until "Funciones"
    // is opened — and opening it never touches "Tablas".
    final AsyncValue<List<SchemaObject>>? asyncObjects =
        widget.expanded ? ref.watch(schemaTypeExplorerProvider(_typeKey)) : null;
    final count = asyncObjects?.valueOrNull?.length;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Material(
          color: Colors.transparent,
          borderRadius: rowRadius,
          child: InkWell(
            borderRadius: rowRadius,
            onTap: widget.onToggle,
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 4),
              child: Row(
                children: [
                  AnimatedRotation(
                    turns: widget.expanded ? 0.25 : 0,
                    duration: const Duration(milliseconds: 120),
                    child: Icon(LucideIcons.chevron_right,
                        size: 12, color: colors.textMuted),
                  ),
                  const SizedBox(width: 2),
                  Icon(iconForType(widget.type), size: 12, color: colors.textMuted),
                  const SizedBox(width: 6),
                  Expanded(
                      child:
                          Text(widget.type.label, style: typography.caption)),
                  // No count until this category has actually been loaded —
                  // showing one up front would mean fetching it up front,
                  // exactly what this rewrite removes.
                  if (count != null)
                    Text('$count',
                        style: typography.caption
                            .copyWith(color: colors.textMuted)),
                  if (widget.expanded && asyncObjects?.isLoading != true) ...[
                    const SizedBox(width: 4),
                    Tooltip(
                      message: 'Actualizar ${widget.type.label.toLowerCase()}',
                      child: InkWell(
                        borderRadius: const BorderRadius.all(Radius.circular(4)),
                        onTap: () =>
                            ref.invalidate(schemaTypeExplorerProvider(_typeKey)),
                        child: Padding(
                          padding: const EdgeInsets.all(2),
                          child: Icon(LucideIcons.refresh_cw,
                              size: 11, color: colors.accent.base),
                        ),
                      ),
                    ),
                  ],
                ],
              ),
            ),
          ),
        ),
        if (widget.expanded)
          Padding(
            padding: const EdgeInsets.only(left: 20),
            child: (asyncObjects ?? const AsyncValue.loading()).when(
              loading: () => Padding(
                padding: const EdgeInsets.symmetric(vertical: 6),
                child: Row(
                  children: [
                    SizedBox(
                      width: 12,
                      height: 12,
                      child: CircularProgressIndicator(
                          strokeWidth: 2, color: colors.textMuted),
                    ),
                    const SizedBox(width: 8),
                    Text('Cargando…', style: typography.caption),
                  ],
                ),
              ),
              error: (error, stackTrace) => Padding(
                padding: const EdgeInsets.symmetric(vertical: 4),
                child: Row(
                  children: [
                    Icon(LucideIcons.triangle_alert,
                        size: 13, color: colors.warn.base),
                    const SizedBox(width: 6),
                    Expanded(
                      child: Text('No se pudo cargar',
                          style: typography.caption,
                          overflow: TextOverflow.ellipsis),
                    ),
                    AppButton(
                      label: 'Reintentar',
                      variant: AppButtonVariant.ghost,
                      onPressed: () =>
                          ref.invalidate(schemaTypeExplorerProvider(_typeKey)),
                    ),
                  ],
                ),
              ),
              data: (objects) {
                if (objects.isEmpty) {
                  return Padding(
                    padding: const EdgeInsets.symmetric(vertical: 4),
                    child: Text('Sin objetos',
                        style: typography.caption
                            .copyWith(color: colors.textMuted)),
                  );
                }
                return SchemaObjectList(
                  objects: objects,
                  dbKey: widget.dbKey,
                  engine: widget.engine,
                  defaultSchema: widget.defaultSchema,
                );
              },
            ),
          ),
      ],
    );
  }
}
