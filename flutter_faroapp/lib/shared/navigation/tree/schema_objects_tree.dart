import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_lucide/flutter_lucide.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/constants/db_engine.dart';
import '../../../core/theme/app_theme.dart';
import '../../../data/models/schema_object.dart';
import '../../../features/consulta/application/schema_explorer_provider.dart';
import 'schema_search_results.dart';
import 'schema_type_group.dart';

/// The catalog browser for one database — tables, views, functions,
/// procedures and triggers, each its own collapsible, independently-lazy
/// category (see [SchemaTypeGroup]). Always renders all five category
/// headers up front (SSMS/pgAdmin-style folders); no data is fetched for
/// any of them until the user actually expands that one — see
/// `schemaTypeExplorerProvider`'s doc comment for why this replaced a
/// single eager whole-database fetch.
class SchemaObjectsTree extends ConsumerStatefulWidget {
  const SchemaObjectsTree({super.key, required this.dbKey, required this.engine});

  final SchemaExplorerKey dbKey;
  final DbEngine engine;

  @override
  ConsumerState<SchemaObjectsTree> createState() => _SchemaObjectsTreeState();
}

class _SchemaObjectsTreeState extends ConsumerState<SchemaObjectsTree> {
  final Set<SchemaObjectType> _expandedTypes = {};
  final _searchController = TextEditingController();
  Timer? _debounce;
  // Only what's actually fed to `schemaSearchProvider` — separate from the
  // controller's live text so a fast typist doesn't fire a query per
  // keystroke (each distinct query string is its own `.family` entry, i.e.
  // its own network round-trip; see that provider's doc comment).
  String _query = '';

  @override
  void dispose() {
    _debounce?.cancel();
    _searchController.dispose();
    super.dispose();
  }

  void _onSearchChanged(String value) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 350), () {
      if (mounted) setState(() => _query = value.trim());
    });
  }

  void _clearSearch() {
    _debounce?.cancel();
    _searchController.clear();
    setState(() => _query = '');
  }

  @override
  Widget build(BuildContext context) {
    final colors = context.appTheme.colors;
    final defaultSchema = defaultSchemaFor(widget.engine);
    // A couple of characters at least — a single letter against a catalog
    // with thousands of objects would still return a huge, not-actually-
    // useful result set.
    final searching = _query.trim().length >= 2;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.only(bottom: 4),
          child: SizedBox(
            height: 28,
            child: TextField(
              controller: _searchController,
              onChanged: _onSearchChanged,
              style: context.appTheme.typography.caption,
              decoration: InputDecoration(
                isDense: true,
                contentPadding:
                    const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
                hintText: 'Buscar en el esquema…',
                prefixIcon: Icon(LucideIcons.search, size: 13, color: colors.textMuted),
                prefixIconConstraints:
                    const BoxConstraints(minWidth: 28, minHeight: 28),
                suffixIcon: _searchController.text.isEmpty
                    ? null
                    : InkWell(
                        onTap: _clearSearch,
                        child: Icon(LucideIcons.x, size: 13, color: colors.textMuted),
                      ),
                suffixIconConstraints:
                    const BoxConstraints(minWidth: 28, minHeight: 28),
              ),
            ),
          ),
        ),
        if (searching)
          SchemaSearchResults(
            dbKey: widget.dbKey,
            engine: widget.engine,
            query: _query,
            defaultSchema: defaultSchema,
          )
        else
          for (final type in SchemaObjectType.values)
            SchemaTypeGroup(
              key: ValueKey(type),
              dbKey: widget.dbKey,
              engine: widget.engine,
              type: type,
              defaultSchema: defaultSchema,
              expanded: _expandedTypes.contains(type),
              onToggle: () => setState(() {
                if (!_expandedTypes.remove(type)) {
                  _expandedTypes.add(type);
                }
              }),
            ),
      ],
    );
  }
}
