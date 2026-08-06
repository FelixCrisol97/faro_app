import 'package:flutter/material.dart';

import '../../../../../core/constants/db_engine.dart';
import '../../../../../data/models/schema_object.dart';
import '../../../application/schema_explorer_provider.dart';
import 'schema_object_row.dart';

/// A category's (or search results') object list — bounded height,
/// scrollable on its own past a handful of rows, never a plain `Column`
/// with one real widget per object. A real client database can have
/// thousands of tables/functions in a single category; rendering all of
/// them as live widgets at once (the previous approach) is what made RAM
/// balloon and made scrolling through a fully-loaded category unusable —
/// the same problem the results grid already solved via a real virtualized
/// grid (`VirtualizedTable` in `widgets/results/virtualized_table.dart`),
/// just never applied here until now. `ListView.builder` only ever builds
/// rows near the visible viewport regardless of how many objects there
/// are. Height sizes to the actual content up to `_maxHeight`, so a
/// category with a handful of objects still looks like a plain list (no
/// dead space, no unnecessary inner scrollbar) — only a genuinely long one
/// gets capped and its own scroll (Flutter's default desktop
/// `ScrollBehavior` already draws a scrollbar on it automatically, no
/// extra widget needed).
class SchemaObjectList extends StatelessWidget {
  const SchemaObjectList({
    super.key,
    required this.objects,
    required this.dbKey,
    required this.engine,
    required this.defaultSchema,
    this.showTypeIcon = false,
  });

  final List<SchemaObject> objects;
  final SchemaExplorerKey dbKey;
  final DbEngine engine;
  final String defaultSchema;
  final bool showTypeIcon;

  // A rough estimate of one collapsed row's height (padding + ~12px text)
  // — only used to size this box, not to constrain each row's actual
  // layout (unlike a fixed `itemExtent`), so it doesn't need to be exact
  // and a row expanding its inline column list isn't a problem. Worst
  // case an under-estimate leaves a little dead space or clips the last
  // row right at the cap boundary before the inner scroll takes over.
  static const _rowHeight = 26.0;
  static const _maxHeight = 320.0;

  @override
  Widget build(BuildContext context) {
    final height = (objects.length * _rowHeight).clamp(_rowHeight, _maxHeight);
    return SizedBox(
      height: height,
      // Flutter's default desktop ScrollBehavior auto-draws a scrollbar on
      // every scrollable, including this nested one — in the sidebar's
      // narrow ~300px width that showed up as a second scrollbar crowding
      // right next to (sometimes visually overlapping) the sidebar's own,
      // reported by the user with a screenshot. This box already sits
      // inside an obviously-scrollable sidebar, so it doesn't need its own
      // separate scroll indicator — dragging/wheel-scrolling while
      // hovering over it still scrolls it exactly the same, this only
      // turns off the redundant decoration.
      child: ScrollConfiguration(
        behavior:
            ScrollConfiguration.of(context).copyWith(scrollbars: false),
        child: ListView.builder(
          padding: EdgeInsets.zero,
          itemCount: objects.length,
          itemBuilder: (context, index) {
            final object = objects[index];
            return SchemaObjectRow(
              key: ValueKey(
                  '${object.type.name}|${object.schema}|${object.name}'),
              dbKey: dbKey,
              engine: engine,
              object: object,
              defaultSchema: defaultSchema,
              showTypeIcon: showTypeIcon,
            );
          },
        ),
      ),
    );
  }
}
