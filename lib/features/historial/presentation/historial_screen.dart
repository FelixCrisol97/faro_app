import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_lucide/flutter_lucide.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';

import '../../../core/theme/app_spacing.dart';
import '../../../core/theme/app_theme.dart';
import '../../../data/models/history_entry.dart';
import '../../../shared/navigation/side_panel/side_panel.dart';
import '../../../shared/widgets/app_button.dart';
import '../../../shared/widgets/app_card.dart';
import '../../../shared/widgets/app_tag.dart';
import '../../../shared/widgets/save_favorite_dialog.dart';
import '../../consulta/application/consulta_providers.dart';
import '../../consulta/application/query_tabs_providers.dart';
import '../application/historial_providers.dart';

/// README.md "2. Historial": newest first.
///
/// **Rebuilt as a card list 2026-08-13** (was a fixed-column table with a
/// dedicated header row, same pattern as Administración's old
/// `_DatabaseRow`) — real bug, user screenshot: that column layout needed
/// ~820px total, which never fit once Historial became a narrow overlay
/// panel (`side_panel_overlay.dart`, 320-640px). A first fix wrapped it in
/// a horizontal scroll instead of redesigning it — technically no longer
/// overflowing, but the user still couldn't find the action icons
/// ("Reusar"/copiar/favorito), scrolled off to the right with no visible
/// scrollbar hinting they existed. This card layout has no fixed-width
/// columns at all (only small badges/chips), so it reflows to fit any
/// panel width with nothing ever needing horizontal scroll, and — the
/// actual fix for "no veo las opciones" — the row's actions sit on the
/// *first* line, always visible, never the part that would scroll away.
/// Still a real `ListView.builder` (one widget per visible card only),
/// same virtualization reasoning the old table had.
class HistorialScreen extends ConsumerWidget {
  const HistorialScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final history = ref.watch(historyProvider);
    final typography = context.appTheme.typography;
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
      child: ListView.separated(
        itemCount: history.length,
        separatorBuilder: (context, index) =>
            const SizedBox(height: AppSpacing.space2),
        itemBuilder: (context, index) => _HistoryCard(
          key: ValueKey(history[index].id),
          entry: history[index],
          timeFormat: timeFormat,
        ),
      ),
    );
  }
}

class _HistoryCard extends ConsumerWidget {
  const _HistoryCard({super.key, required this.entry, required this.timeFormat});

  final HistoryEntry entry;
  final DateFormat timeFormat;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colors = context.appTheme.colors;
    final typography = context.appTheme.typography;

    return AppCard(
      padding: const EdgeInsets.all(AppSpacing.space3),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Text(timeFormat.format(entry.timestamp),
                  style: typography.body.copyWith(
                      fontSize: 13,
                      fontWeight: FontWeight.w600,
                      color: colors.textMuted)),
              const Spacer(),
              // Actions first, on their own always-visible line — real
              // fix (2026-08-13, user report: "no veo las opciones") for
              // a first attempt that put them at the end of a wide row
              // that could scroll them out of view.
              Tooltip(
                message: 'Copiar consulta',
                child: InkWell(
                  borderRadius: const BorderRadius.all(Radius.circular(6)),
                  onTap: () {
                    Clipboard.setData(ClipboardData(text: entry.queryText));
                    ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
                        content: Text('Consulta copiada al portapapeles')));
                  },
                  child: Padding(
                    padding: const EdgeInsets.all(4),
                    child:
                        Icon(LucideIcons.copy, size: 15, color: colors.textMuted),
                  ),
                ),
              ),
              Tooltip(
                message: 'Guardar como favorito',
                child: InkWell(
                  borderRadius: const BorderRadius.all(Radius.circular(6)),
                  onTap: () =>
                      showSaveFavoriteDialog(context, ref, entry.queryText),
                  child: Padding(
                    padding: const EdgeInsets.all(4),
                    child:
                        Icon(LucideIcons.star, size: 15, color: colors.textMuted),
                  ),
                ),
              ),
              const SizedBox(width: AppSpacing.space1),
              AppButton(
                label: 'Reusar',
                variant: AppButtonVariant.ghost,
                onPressed: () {
                  ref.read(sqlEditorProvider.notifier).loadText(entry.queryText);
                  // Reusing from Historial always lands in the "Consulta"
                  // home pane — if a query tab was active, switch back to
                  // home too, or the text would silently land in a pane
                  // that isn't on screen; also close this panel so the
                  // editor underneath it is visible.
                  ref.read(queryTabsProvider.notifier).activate(null);
                  ref.read(activeSidePanelProvider.notifier).state = null;
                },
              ),
            ],
          ),
          const SizedBox(height: AppSpacing.space1),
          Text(entry.queryText,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: typography.monospace.copyWith(fontSize: 12)),
          const SizedBox(height: AppSpacing.space1),
          // Meta line — wraps instead of a fixed-width column each, so a
          // long server name never forces the card wider than its panel.
          Wrap(
            crossAxisAlignment: WrapCrossAlignment.center,
            spacing: AppSpacing.space2,
            runSpacing: 4,
            children: [
              Text(entry.serverName,
                  overflow: TextOverflow.ellipsis,
                  style: typography.caption.copyWith(color: colors.textMuted)),
              Text('·', style: TextStyle(color: colors.textMuted)),
              Text('${entry.databaseCount} BD(s)',
                  style: typography.caption.copyWith(color: colors.textMuted)),
              Text('·', style: TextStyle(color: colors.textMuted)),
              Text('${entry.rowCount} filas',
                  style: typography.caption.copyWith(color: colors.textMuted)),
              AppTag(
                  label: entry.status.label,
                  variant: _tagVariantFor(entry.status)),
            ],
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
