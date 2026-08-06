import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../data/models/history_entry.dart';
import '../../../data/providers/core_providers.dart';

/// Execution history, persisted to disk (`HistorialRepository`) —
/// 2026-07-20, reverses an earlier explicit "session-only, confirmed with
/// the user" decision, per a later direct request (see
/// `favoritos_providers.dart`'s doc comment for the same reversal on that
/// feature, same reasoning).
///
/// Two things Historial needs that `ServersNotifier`'s pattern doesn't:
/// - **A cap** (`_maxEntries`) — unbounded growth used to self-limit for
///   free (cleared every restart); once it survives restarts, and can be
///   appended to from any open query window, it needs an explicit ceiling.
/// - **Read-merge-write, not a blind `saveAll(state)`** — same reasoning as
///   `FavoritesNotifier`: several windows can each finish a run and call
///   `add` near-simultaneously, each with no visibility into what the
///   others just wrote (separate isolates, no shared memory). Reading
///   fresh from disk immediately before every write avoids one window's
///   entry silently clobbering another's.
class HistoryNotifier extends Notifier<List<HistoryEntry>> {
  static const _maxEntries = 200;

  @override
  List<HistoryEntry> build() => ref.watch(historialRepositoryProvider).load();

  /// Newest first (README.md "Historial").
  void add(HistoryEntry entry) {
    final repo = ref.read(historialRepositoryProvider);
    final merged = {for (final e in [...repo.load(), entry]) e.id: e}
        .values
        .toList()
      ..sort((a, b) => b.timestamp.compareTo(a.timestamp));
    final capped = merged.take(_maxEntries).toList();
    state = capped;
    repo.saveAll(capped);
  }
}

final historyProvider =
    NotifierProvider<HistoryNotifier, List<HistoryEntry>>(HistoryNotifier.new);
