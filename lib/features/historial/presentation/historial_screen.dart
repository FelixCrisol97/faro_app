import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../../core/theme/app_colors.dart';
import '../../../core/theme/app_spacing.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/theme/app_typography.dart';
import '../../../data/models/history_entry.dart';
import '../../../shared/navigation/app_screen.dart';
import '../../../shared/widgets/app_button.dart';
import '../../../shared/widgets/app_tag.dart';
import '../../consulta/application/consulta_providers.dart';
import '../../consulta/application/query_tabs_providers.dart';
import '../application/historial_providers.dart';

/// README.md "2. Historial": a single table, newest first.
///
/// A plain `DataTable` (2026-07-17..07-20's original implementation)
/// builds every row as a real widget up front, no matter how many are
/// visible — real, measurable overhead even at Historial's own 200-entry
/// cap (`historial_providers.dart`), and the exact class of problem found
/// (at a much bigger scale — 5000 objects) and fixed in the schema tree
/// the same day this was rewritten. Manual fixed-width columns (same
/// pattern already used in Administración's `_DatabaseRow`) + a real
/// `ListView.builder` body gets virtualization for free instead.
class HistorialScreen extends ConsumerWidget {
  const HistorialScreen({super.key});

  static const _timeWidth = 90.0;
  static const _serverWidth = 150.0;
  static const _countWidth = 60.0;
  static const _statusWidth = 100.0;
  static const _actionWidth = 90.0;
  static const _columnGap = AppSpacing.space2;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final history = ref.watch(historyProvider);
    final typography = context.appTheme.typography;
    final colors = context.appTheme.colors;
    // Default (English) locale data ships with intl out of the box; a
    // Spanish-formatted clock would need `initializeDateFormatting('es_MX')`
    // wired up in main() first — skipped for now to avoid a runtime
    // LocaleDataException before that's in place.
    final timeFormat = DateFormat.Hms();

    if (history.isEmpty) {
      // Real bug fixed 2026-08-03 (AUDITORIA_CODIGO.md): "en esta sesión"
      // went stale on 2026-07-20, when Historial started persisting to
      // disk and surviving restarts/other windows — this empty state kept
      // implying it would reset next time you opened the app.
      return Center(
          child: Text('Todavía no se ha ejecutado ninguna consulta.',
              style: typography.body));
    }

    return Padding(
      padding: const EdgeInsets.all(AppSpacing.space4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _HeaderRow(typography: typography, colors: colors),
          Divider(height: 1, color: colors.border),
          Expanded(
            child: ListView.builder(
              itemCount: history.length,
              itemBuilder: (context, index) => _HistoryRow(
                key: ValueKey(history[index].id),
                entry: history[index],
                typography: typography,
                colors: colors,
                timeFormat: timeFormat,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _HeaderRow extends StatelessWidget {
  const _HeaderRow({required this.typography, required this.colors});

  final AppTypography typography;
  final AppColors colors;

  @override
  Widget build(BuildContext context) {
    final style = typography.caption.copyWith(color: colors.textMuted);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: AppSpacing.space2),
      child: Row(
        children: [
          SizedBox(width: HistorialScreen._timeWidth, child: Text('Hora', style: style)),
          const SizedBox(width: HistorialScreen._columnGap),
          Expanded(child: Text('Consulta', style: style)),
          const SizedBox(width: HistorialScreen._columnGap),
          SizedBox(
              width: HistorialScreen._serverWidth,
              child: Text('Servidor', style: style)),
          const SizedBox(width: HistorialScreen._columnGap),
          SizedBox(
              width: HistorialScreen._countWidth,
              child: Text('# BDs', style: style)),
          const SizedBox(width: HistorialScreen._columnGap),
          SizedBox(
              width: HistorialScreen._countWidth,
              child: Text('Filas', style: style)),
          const SizedBox(width: HistorialScreen._columnGap),
          SizedBox(
              width: HistorialScreen._statusWidth,
              child: Text('Estado', style: style)),
          const SizedBox(width: HistorialScreen._columnGap),
          const SizedBox(width: HistorialScreen._actionWidth),
        ],
      ),
    );
  }
}

class _HistoryRow extends ConsumerWidget {
  const _HistoryRow({
    super.key,
    required this.entry,
    required this.typography,
    required this.colors,
    required this.timeFormat,
  });

  final HistoryEntry entry;
  final AppTypography typography;
  final AppColors colors;
  final DateFormat timeFormat;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final truncated = entry.queryText.length > 60
        ? '${entry.queryText.substring(0, 60)}…'
        : entry.queryText;

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: AppSpacing.space1),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          SizedBox(
              width: HistorialScreen._timeWidth,
              child: Text(timeFormat.format(entry.timestamp),
                  style: typography.body.copyWith(fontSize: 13))),
          const SizedBox(width: HistorialScreen._columnGap),
          Expanded(
            child: Text(truncated,
                overflow: TextOverflow.ellipsis,
                style: typography.monospace.copyWith(fontSize: 12)),
          ),
          const SizedBox(width: HistorialScreen._columnGap),
          SizedBox(
              width: HistorialScreen._serverWidth,
              child: Text(entry.serverName,
                  overflow: TextOverflow.ellipsis,
                  style: typography.body.copyWith(fontSize: 13))),
          const SizedBox(width: HistorialScreen._columnGap),
          SizedBox(
              width: HistorialScreen._countWidth,
              child: Text('${entry.databaseCount}',
                  style: typography.body.copyWith(fontSize: 13))),
          const SizedBox(width: HistorialScreen._columnGap),
          SizedBox(
              width: HistorialScreen._countWidth,
              child: Text('${entry.rowCount}',
                  style: typography.body.copyWith(fontSize: 13))),
          const SizedBox(width: HistorialScreen._columnGap),
          SizedBox(
            width: HistorialScreen._statusWidth,
            child: AppTag(
                label: entry.status.label,
                variant: _tagVariantFor(entry.status)),
          ),
          const SizedBox(width: HistorialScreen._columnGap),
          SizedBox(
            width: HistorialScreen._actionWidth,
            child: AppButton(
              label: 'Reusar',
              variant: AppButtonVariant.ghost,
              onPressed: () {
                ref.read(sqlEditorProvider.notifier).loadText(entry.queryText);
                // Reusing from Historial always lands in the "Consulta"
                // home pane — if a query tab was active, switch back to
                // home too, or the text would silently land in a pane
                // that isn't on screen.
                ref.read(queryTabsProvider.notifier).activate(null);
                ref.read(currentScreenProvider.notifier).state =
                    AppScreen.consulta;
              },
            ),
          ),
        ],
      ),
    );
  }

  AppTagVariant _tagVariantFor(HistoryStatus status) => switch (status) {
        HistoryStatus.success => AppTagVariant.success,
        HistoryStatus.partial => AppTagVariant.warnSoft,
      };
}
