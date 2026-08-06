import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_lucide/flutter_lucide.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:uuid/uuid.dart';

import '../../../../core/constants/db_engine.dart';
import '../../../../core/theme/app_spacing.dart';
import '../../../../core/theme/app_theme.dart';
import '../../../../data/models/database_entry.dart';
import '../../../../data/models/favorite_query.dart';
import '../../../../data/providers/servers_providers.dart';
import '../../../../shared/widgets/app_button.dart';
import '../../../../shared/widgets/app_card.dart';
import '../../../../shared/widgets/app_dialog.dart';
import '../../../../shared/widgets/app_tag.dart';
import '../../../../shared/utils/file_paths.dart';
import '../../../../shared/widgets/add_server_dialog.dart';
import '../../../../shared/widgets/centered_scrollable.dart';
import '../../../favoritos/application/favoritos_providers.dart';
import '../../application/consulta_providers.dart';
import '../../application/query_tabs_providers.dart';
import '../../application/sql_formatter.dart';
import 'sql_editor.dart';

const _uuid = Uuid();

/// README.md "Main — toolbar card": header row, action row, SQL editor.
class ToolbarCard extends ConsumerWidget {
  const ToolbarCard({super.key, this.pinnedTarget, this.tabId});

  /// Set only inside a query window (`query_window_screen.dart`) — a
  /// separate native window permanently scoped to one database (its own
  /// `ProviderScope` overrides `selectedQueryTargetsProvider` to always
  /// resolve to just this one target, so nothing here needs to change how
  /// a run is actually scoped). Only changes what the header *shows*: the
  /// database's own name instead of a summary across however many servers
  /// have a selection, and no "N de M bases seleccionadas" counter, which
  /// wouldn't mean anything when selection can never be anything but this
  /// one fixed database.
  final QueryTarget? pinnedTarget;

  /// Set only inside an in-window query tab (`query_tab_workspace.dart`) —
  /// scopes editor/run state and which database this reads from to that
  /// one tab instead of the global providers, via
  /// `query_tabs_providers.dart`'s routing helpers. Never set together
  /// with [pinnedTarget] — a query window overrides its whole
  /// `ProviderScope` instead, it never needs tab routing.
  final String? tabId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colors = context.appTheme.colors;
    final typography = context.appTheme.typography;
    final servers = ref.watch(serversProvider);

    if (servers.isEmpty) {
      // Real action right here instead of just pointing at Administración
      // — the sidebar's own "+ Registrar servidor" (server_sidebar.dart)
      // already opens this same dialog from this same screen, so telling
      // the user to go elsewhere was redundant busywork, not a real
      // requirement (2026-08-02, user-reported: "se me hace inútil").
      return AppCard(
        child: CenteredScrollable(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(LucideIcons.server, size: 32, color: colors.textMuted),
              const SizedBox(height: AppSpacing.space2),
              Text('Todavía no hay servidores registrados.',
                  style: typography.body),
              const SizedBox(height: AppSpacing.space3),
              AppButton(
                label: 'Registrar servidor',
                icon: LucideIcons.plus,
                onPressed: () => showAddServerDialog(context, ref),
              ),
            ],
          ),
        ),
      );
    }

    // A tab's own target is resolved live (the database it points at can
    // be deleted from Administración while the tab stays open, in the
    // same window, no restart) — null means exactly that happened, so the
    // card shows an explanation instead of buttons that would run against
    // nothing.
    QueryTarget? tabTarget;
    if (tabId != null) {
      tabTarget = ref.watch(resolvedTabTargetProvider(tabId!));
      if (tabTarget == null) {
        return AppCard(
          child: Text(
            'Esta base de datos ya no existe — puede que se haya eliminado '
            'desde Administración. Cierra esta pestaña.',
            style: typography.body,
          ),
        );
      }
    }

    final targets =
        tabId != null ? [tabTarget!] : ref.watch(selectedQueryTargetsProvider);
    final editorState = watchEditorState(ref, tabId);
    final editorActions = editorActionsFor(ref, tabId);
    final runState = watchRunState(ref, tabId);
    final runActions = runActionsFor(ref, tabId);

    // A run can span every server now, not just one "active" one (a
    // servidor is only ever a grouping — see Server's doc comment), so the
    // header summarizes across whichever servers actually have a selected
    // database instead of naming one fixed server.
    final selectedDatabases = [for (final t in targets) t.database];
    final modeLabel = _aggregateModeLabel(selectedDatabases);
    final totalDatabases =
        servers.fold<int>(0, (sum, s) => sum + s.databases.length);
    final serversInvolved = {for (final t in targets) t.server.id: t.server.name};
    // With exactly one database targeted (the common case — "Consulta
    // masiva" off, the default, only ever allows one at a time — or a
    // query tab, always exactly one by construction) the title shows both
    // server and database instead of just the server's name. Several
    // databases on the same server (mass mode) still collapse to just the
    // server name — no single database to name unambiguously in that
    // case. Not shown at all when pinned — see below.
    final headerTitle = switch (serversInvolved.length) {
      0 => 'Consulta',
      1 => targets.length == 1
          ? '${targets.first.server.name} · ${targets.first.database.name}'
          : serversInvolved.values.first,
      _ => '${serversInvolved.length} servidores',
    };
    // A query window already has its own header naming the server/database
    // (`query_window_screen.dart`) right above this card — repeating the
    // exact same text here read as redundant (the user pointed it out
    // directly after trying it). A query tab has no such separate header
    // of its own (only the tab strip's chip, small and possibly
    // truncated), so it keeps the title.
    final hideSelectionCount = pinnedTarget != null || tabId != null;

    return AppCard(
      padding: const EdgeInsets.all(AppSpacing.space4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              if (pinnedTarget == null)
                Expanded(
                    child: Text(headerTitle,
                        style: typography.heading.copyWith(fontSize: 18))),
              if (modeLabel != null) ...[
                AppTag(
                  label: modeLabel,
                  variant: modeLabel == ServerMode.readOnly.label
                      ? AppTagVariant.neutral
                      : AppTagVariant.warnSoft,
                ),
                const SizedBox(width: AppSpacing.space2),
              ],
              if (!hideSelectionCount)
                Text(
                  '${targets.length} de $totalDatabases bases seleccionadas',
                  style: typography.caption,
                  softWrap: false,
                  overflow: TextOverflow.ellipsis,
                ),
            ],
          ),
          const SizedBox(height: AppSpacing.space3),
          Wrap(
            spacing: AppSpacing.space2,
            runSpacing: AppSpacing.space2,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              if (runState.isRunning)
                AppButton(
                  label: 'Cancelar',
                  icon: LucideIcons.square,
                  variant: AppButtonVariant.secondary,
                  onPressed: () => runActions.cancel(),
                )
              else
                AppButton(
                  label: 'F5',
                  icon: LucideIcons.play,
                  variant: AppButtonVariant.primary,
                  onPressed: targets.isEmpty ? null : () => runActions.run(),
                ),
              AppButton(
                label: 'Cargar',
                icon: LucideIcons.upload,
                onPressed: () => _loadFromFile(editorActions),
              ),
              // Only meaningful once there's a real unsaved change to write
              // (`QueryEditorState.hasUnsavedChanges`) — no open file, or an
              // open file with nothing changed since the last load/save,
              // both leave this disabled. Ctrl+G (`consulta_screen.dart`/
              // `query_window_screen.dart`) does the exact same thing.
              AppButton(
                label: 'Guardar',
                icon: LucideIcons.save,
                onPressed: editorState.hasUnsavedChanges
                    ? () => saveQueryToFile(context, ref, tabId)
                    : null,
              ),
              AppButton(
                label: 'Exportar SQL',
                icon: LucideIcons.download,
                onPressed: () => _exportToFile(context, editorState),
              ),
              AppButton(
                label: 'Formatear',
                icon: LucideIcons.text_align_start,
                onPressed: () => editorActions.loadText(
                    formatSql(editorState.text),
                    filePath: editorState.filePath),
              ),
              AppButton(
                label: 'Favorito',
                icon: LucideIcons.star,
                onPressed: () => _saveFavorite(context, ref, editorState),
              ),
              // The open file's name, so it's clear what "Guardar" would
              // write to — with a small dot while there's something unsaved
              // (user request: "debería verse el nombre del archivo que
              // está abierto").
              if (editorState.filePath != null)
                Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(LucideIcons.file_text, size: 13, color: colors.textMuted),
                    const SizedBox(width: 4),
                    Text(
                      File(editorState.filePath!).uri.pathSegments.last,
                      style: typography.caption.copyWith(color: colors.textMuted),
                    ),
                    if (editorState.hasUnsavedChanges) ...[
                      const SizedBox(width: 4),
                      Text('•',
                          style: typography.caption
                              .copyWith(color: colors.warn.base, fontSize: 16)),
                    ],
                  ],
                ),
            ],
          ),
          const SizedBox(height: AppSpacing.space3),
          // Expanded, not a fixed-size child — this card is now given a
          // drag-resizable height by consulta_screen.dart, and the editor
          // should fill whatever's left after the header/buttons above.
          Expanded(child: SqlEditor(tabId: tabId)),
        ],
      ),
    );
  }

  /// Mode is per-database now (`DatabaseEntry.mode` — a servidor is only a
  /// grouping, see `Server`'s doc comment), so the header tag summarizes
  /// the *selected* databases' modes instead of a single server-wide one:
  /// null when nothing's selected yet, a plain label when they all agree,
  /// "Modos mixtos" when they don't (the per-database truth still shows up
  /// per-result-pill and in Administración either way).
  String? _aggregateModeLabel(List<DatabaseEntry> selected) {
    if (selected.isEmpty) return null;
    final allReadOnly = selected.every((db) => db.mode == ServerMode.readOnly);
    if (allReadOnly) return ServerMode.readOnly.label;
    final allUnrestricted =
        selected.every((db) => db.mode == ServerMode.development);
    if (allUnrestricted) return ServerMode.development.label;
    return 'Modos mixtos';
  }

  Future<void> _loadFromFile(QueryEditorActions editorActions) async {
    final result = await FilePicker.platform.pickFiles(
      type: FileType.custom,
      allowedExtensions: ['sql', 'txt'],
    );
    final path = result?.files.single.path;
    if (path == null) return;
    var content = await File(path).readAsString();
    // Real bug fixed 2026-08-05 (client-reported): a `.sql` file saved from
    // Notepad/Excel-adjacent tools on Windows commonly starts with a UTF-8
    // BOM (U+FEFF) — `readAsString` decodes it as a literal invisible
    // character glued onto the very first line, which broke a leading
    // comment there (`SqlGuard`/the statement splitter no longer recognize
    // it as "nothing real yet" once that invisible character counts as
    // content) — same root cause already fixed for CSV import in
    // `import_csv_dialog.dart`, just never applied here.
    const bom = '﻿';
    if (content.startsWith(bom)) {
      content = content.substring(bom.length);
    }
    editorActions.loadText(content, filePath: path, fromDisk: true);
  }

  /// Inverse of "Cargar" — saves the editor's current text to a `.sql` file
  /// instead of reading one, e.g. for a context-menu-generated CREATE/UPDATE
  /// script the user wants to keep. Always prompts a save-file dialog
  /// ("save as"), unlike "Guardar" — doesn't attach the chosen path back to
  /// [QueryEditorState.filePath], so it stays a one-off export rather than
  /// silently starting to track a new file.
  Future<void> _exportToFile(
      BuildContext context, QueryEditorState editorState) async {
    final text = editorState.text;
    if (text.trim().isEmpty) return;
    final rawPath = await FilePicker.platform.saveFile(
      fileName: 'consulta.sql',
      type: FileType.custom,
      allowedExtensions: ['sql'],
    );
    if (rawPath == null) return;
    final path = ensureExtension(rawPath, 'sql');
    await File(path).writeAsString(text);
    if (!context.mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
          content:
              Text('SQL exportado: ${File(path).uri.pathSegments.last}')),
    );
  }

  /// Favoritos stays one shared list across every window/tab (same as
  /// Historial) — never per-tab, only *where the text comes from* is
  /// tab-scoped.
  Future<void> _saveFavorite(
      BuildContext context, WidgetRef ref, QueryEditorState editorState) async {
    final text = editorState.text;
    if (text.trim().isEmpty) return;
    final nameController = TextEditingController();

    void submit() => Navigator.of(context).pop(nameController.text.trim());

    final name = await showAppDialog<String>(
      context: context,
      title: 'Guardar como favorito',
      body: TextField(
        controller: nameController,
        decoration: const InputDecoration(labelText: 'Nombre'),
        autofocus: true,
        onSubmitted: (_) => submit(),
      ),
      actions: [
        AppButton(
          label: 'Guardar',
          variant: AppButtonVariant.primary,
          onPressed: submit,
        ),
      ],
    );

    if (name == null || name.isEmpty) return;
    ref.read(favoritesProvider.notifier).add(FavoriteQuery(
        id: _uuid.v4(),
        name: name,
        queryText: text,
        createdAt: DateTime.now()));
  }
}

/// "Guardar" — writes the current text back to the file "Cargar" attached
/// (`QueryEditorState.filePath`), no dialog, then marks it saved so
/// `hasUnsavedChanges` goes back to false. A no-op with nothing new to
/// write (already covered by the button's own `onPressed` guard, but
/// checked again here since this is also reachable straight from the
/// Ctrl+G shortcut in `consulta_screen.dart`/`query_window_screen.dart`,
/// which has no button state to gate it). Top-level, not a `ToolbarCard`
/// method, so both call it without either owning the other.
Future<void> saveQueryToFile(
    BuildContext context, WidgetRef ref, String? tabId) async {
  final editorState = readEditorState(ref, tabId);
  if (!editorState.hasUnsavedChanges) return;
  final path = editorState.filePath!;
  await File(path).writeAsString(editorState.text);
  editorActionsFor(ref, tabId).markSaved();
  if (!context.mounted) return;
  ScaffoldMessenger.of(context).showSnackBar(
    SnackBar(content: Text('Guardado: ${File(path).uri.pathSegments.last}')),
  );
}
