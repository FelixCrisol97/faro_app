import 'package:flutter/material.dart';
import 'package:flutter_lucide/flutter_lucide.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/constants/db_engine.dart';
import '../../../core/theme/app_theme.dart';
import '../../../shared/widgets/app_button.dart';
import '../../../features/consulta/application/schema_explorer_provider.dart';
import 'schema_object_list.dart';

/// Flat, mixed-type results for the sidebar's search box — backed by
/// `schemaSearchProvider`, which pushes the name filter into SQL
/// (`ILIKE`/`LIKE`) instead of fetching every category and filtering in
/// Dart, so searching stays cheap even against a catalog with thousands of
/// objects (see that provider's doc comment).
class SchemaSearchResults extends ConsumerWidget {
  const SchemaSearchResults({
    super.key,
    required this.dbKey,
    required this.engine,
    required this.query,
    required this.defaultSchema,
  });

  final SchemaExplorerKey dbKey;
  final DbEngine engine;
  final String query;
  final String defaultSchema;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colors = context.appTheme.colors;
    final typography = context.appTheme.typography;
    final searchKey = (
      serverId: dbKey.serverId,
      databaseId: dbKey.databaseId,
      query: query,
    );
    final asyncResults = ref.watch(schemaSearchProvider(searchKey));

    return asyncResults.when(
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
            Text('Buscando…', style: typography.caption),
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
              child: Text('No se pudo buscar',
                  style: typography.caption, overflow: TextOverflow.ellipsis),
            ),
            AppButton(
              label: 'Reintentar',
              variant: AppButtonVariant.ghost,
              onPressed: () => ref.invalidate(schemaSearchProvider(searchKey)),
            ),
          ],
        ),
      ),
      data: (objects) {
        if (objects.isEmpty) {
          return Padding(
            padding: const EdgeInsets.symmetric(vertical: 4),
            child: Text('Sin resultados para "$query"',
                style: typography.caption.copyWith(color: colors.textMuted)),
          );
        }
        return SchemaObjectList(
          objects: objects,
          dbKey: dbKey,
          engine: engine,
          defaultSchema: defaultSchema,
          showTypeIcon: true,
        );
      },
    );
  }
}
