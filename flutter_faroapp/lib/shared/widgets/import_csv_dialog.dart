import 'dart:io';

import 'package:csv/csv.dart';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_lucide/flutter_lucide.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/theme/app_spacing.dart';
import '../../data/models/database_entry.dart';
import '../../data/models/server.dart';
import '../../data/models/table_column.dart';
import '../../data/providers/core_providers.dart';
import '../../data/providers/servers_providers.dart';
import '../../data/repositories/csv_import_service.dart';
import '../../features/consulta/application/table_columns_provider.dart';
import 'app_button.dart';
import 'app_dialog.dart';
import 'app_tag.dart';

/// "Importar CSV…" — reachable from a table's right-click menu
/// (`schema_object_row.dart`), 2026-07-24 feature. Picks a CSV file, lets
/// the user choose which of this table's servidor's databases to import
/// into (pre-checking the one the menu was opened from), then runs
/// `CsvImportService.importInto` against each selected database in
/// parallel — same "fan out, one outcome per database" shape
/// `QueryExecutionService.run` already uses for multi-bodega queries.
///
/// [serverId] `null` (2026-08-13) — a "Sin grupo" database has no sibling
/// databases to offer alongside it, so the destination checklist collapses
/// to just that one (already-checked, undeselectable) database instead of
/// a servidor's full list.
Future<void> showImportCsvDialog(
  BuildContext context,
  WidgetRef ref, {
  required String? serverId,
  required String databaseId,
  required String schema,
  required String table,
}) async {
  Server? server;
  final List<DatabaseEntry> candidateDatabases;
  if (serverId == null) {
    final originDatabase = ref
        .read(ungroupedDatabasesProvider)
        .where((db) => db.id == databaseId)
        .firstOrNull;
    if (originDatabase == null) return;
    candidateDatabases = [originDatabase];
  } else {
    final servers = ref.read(serverListProvider);
    server = servers.where((s) => s.id == serverId).firstOrNull;
    if (server == null) return;
    candidateDatabases = server.databases;
  }
  final originDatabase =
      candidateDatabases.where((db) => db.id == databaseId).firstOrNull;
  if (originDatabase == null) return;

  final picked = await FilePicker.platform
      .pickFiles(type: FileType.custom, allowedExtensions: ['csv']);
  final path = picked?.files.single.path;
  if (path == null || !context.mounted) return;

  final List<List<dynamic>> parsed;
  try {
    // Normalize line endings first — a CSV can arrive from anywhere (not
    // just Faro's own export, which uses `\n`), and `CsvToListConverter`
    // needs one fixed `eol` rather than guessing between `\r\n`/`\n`.
    var content = await File(path).readAsString();
    // Real bug fixed 2026-08-03 (AUDITORIA_CODIGO.md): a CSV exported from
    // Excel commonly starts with a UTF-8 BOM (U+FEFF) — `readAsString`
    // decodes it as a literal character glued onto the very first header
    // cell, silently breaking that one column's case-insensitive name
    // match against the real table columns ("<BOM>sku" != "sku") with no
    // error the user could see, just "column not matched."
    const bom = '﻿';
    if (content.startsWith(bom)) {
      content = content.substring(bom.length);
    }
    content = content.replaceAll('\r\n', '\n').replaceAll('\r', '\n');
    parsed = const CsvToListConverter(eol: '\n', shouldParseNumbers: false)
        .convert(content);
  } catch (e) {
    if (!context.mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text('No se pudo leer el CSV: $e')),
    );
    return;
  }
  if (parsed.isEmpty) {
    if (!context.mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('El archivo CSV está vacío.')),
    );
    return;
  }

  final headers = parsed.first.map((h) => h.toString()).toList();
  final csvRows = parsed.skip(1).map((row) => row.cast<Object?>()).toList();
  if (!context.mounted) return;

  final selected = <String>{originDatabase.id};
  final confirmed = await showAppDialog<bool>(
    context: context,
    title: 'Importar CSV a "$table"',
    body: StatefulBuilder(
      builder: (context, setDialogState) => Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
              '${headers.length} columna(s) detectada(s), ${csvRows.length} fila(s).'),
          const SizedBox(height: AppSpacing.space3),
          const Text('Bases de datos destino:'),
          ConstrainedBox(
            constraints: const BoxConstraints(maxHeight: 240),
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  for (final db in candidateDatabases)
                    CheckboxListTile(
                      dense: true,
                      contentPadding: EdgeInsets.zero,
                      controlAffinity: ListTileControlAffinity.leading,
                      value: selected.contains(db.id),
                      title: Text(db.name),
                      onChanged: (checked) => setDialogState(() {
                        if (checked == true) {
                          selected.add(db.id);
                        } else {
                          selected.remove(db.id);
                        }
                      }),
                    ),
                ],
              ),
            ),
          ),
        ],
      ),
    ),
    actions: [
      AppButton(
        label: 'Importar',
        variant: AppButtonVariant.primary,
        autofocus: true,
        onPressed: () => Navigator.of(context).pop(true),
      ),
    ],
  );

  if (confirmed != true || selected.isEmpty || !context.mounted) return;

  ScaffoldMessenger.of(context).showSnackBar(
    const SnackBar(
        content: Text('Importando…'), duration: Duration(seconds: 60)),
  );

  final credentialsRepo = ref.read(credentialsRepositoryProvider);
  final importService = ref.read(csvImportServiceProvider);
  Future<List<TableColumn>> fetchColumns(
          String? serverId, String databaseId, String schema, String table) =>
      ref.read(tableColumnsProvider((
        serverId: serverId,
        databaseId: databaseId,
        schema: schema,
        objectName: table,
      )).future);

  final targets =
      candidateDatabases.where((db) => selected.contains(db.id)).toList();
  final outcomes = await Future.wait(targets.map((db) async {
    final credentials = await credentialsRepo.resolve(server?.id, db.id);
    return importService.importInto(
      server: server,
      database: db,
      schema: schema,
      table: table,
      csvHeaders: headers,
      csvRows: csvRows,
      credentials: credentials,
      fetchColumns: fetchColumns,
    );
  }));

  if (!context.mounted) return;
  ScaffoldMessenger.of(context).clearSnackBars();

  await showAppDialog<void>(
    context: context,
    title: 'Resultado de la importación',
    body: ConstrainedBox(
      constraints: const BoxConstraints(maxHeight: 320),
      child: SingleChildScrollView(
        child: Wrap(
          spacing: AppSpacing.space1,
          runSpacing: AppSpacing.space1,
          children: [for (final outcome in outcomes) _OutcomePill(outcome)],
        ),
      ),
    ),
    actions: [
      AppButton(
        label: 'Cerrar',
        variant: AppButtonVariant.primary,
        autofocus: true,
        onPressed: () => Navigator.of(context).pop(),
      ),
    ],
  );
}

class _OutcomePill extends StatelessWidget {
  const _OutcomePill(this.outcome);
  final DatabaseImportOutcome outcome;

  @override
  Widget build(BuildContext context) {
    if (!outcome.success) {
      return Tooltip(
        message:
            '${outcome.errorMessage ?? 'Error desconocido'}\n(clic para copiar)',
        child: InkWell(
          onTap: () => _copyToClipboard(
              context, outcome.errorMessage ?? 'Error desconocido'),
          child: AppTag(
            label: outcome.blocked
                ? '${outcome.databaseName} · Solo lectura'
                : outcome.databaseName,
            icon: outcome.blocked ? LucideIcons.lock : LucideIcons.circle_alert,
            // Real bug fixed 2026-08-03 (AUDITORIA_CODIGO.md): this used to
            // be AppTagVariant.success (green) for "blocked" too — the same
            // color as "importado con éxito" below, so scanning a batch
            // import across several bodegas couldn't tell "0 filas, nada
            // se tocó porque está protegida" apart from "todo se insertó"
            // at a glance. `neutral` is the same variant already used for
            // "Solo lectura" elsewhere in the app (see AppTagVariant's own
            // doc comment) — distinct from both success and a real error.
            variant:
                outcome.blocked ? AppTagVariant.neutral : AppTagVariant.error,
          ),
        ),
      );
    }
    if (outcome.failures.isEmpty) {
      return AppTag(
        label: '${outcome.databaseName} · ${outcome.inserted} insertadas',
        variant: AppTagVariant.success,
      );
    }
    final detail = outcome.failures
        .take(20)
        .map((f) => 'Fila ${f.rowIndex + 1}: ${f.message}')
        .join('\n');
    final more = outcome.failures.length > 20
        ? '\n… y ${outcome.failures.length - 20} más'
        : '';
    return Tooltip(
      message: '$detail$more\n(clic para copiar)',
      child: InkWell(
        onTap: () => _copyToClipboard(context, detail + more),
        child: AppTag(
          label:
              '${outcome.databaseName} · ${outcome.inserted} insertadas, ${outcome.failures.length} fallidas',
          variant: AppTagVariant.warnSoft,
        ),
      ),
    );
  }

  Future<void> _copyToClipboard(BuildContext context, String text) async {
    await Clipboard.setData(ClipboardData(text: text));
    if (!context.mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
          content: Text('Detalle copiado al portapapeles'),
          duration: Duration(seconds: 2)),
    );
  }
}
