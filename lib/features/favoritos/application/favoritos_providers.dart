import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../data/models/favorite_query.dart';
import '../../../data/providers/core_providers.dart';

/// Saved queries, persisted to disk (`FavoritosRepository`) — 2026-07-20,
/// reverses an earlier explicit "session-only, confirmed with the user"
/// decision, per a later direct request once multi-window made losing
/// favorites on every window close/app restart a real annoyance rather
/// than a one-off.
///
/// **Read-merge-write, not a blind `saveAll(state)`** — unlike
/// `ServersNotifier` (rare, deliberate edits from one window at a time),
/// a favorite can be saved from any open query window. Each window has its
/// own isolate/`ProviderScope` with no live shared memory (see
/// `query_window_bootstrap.dart`'s doc comment), so two windows saving
/// near-simultaneously would otherwise each overwrite `faro.favoritos`
/// with their own stale-at-their-own-startup copy, silently losing
/// whichever one wrote second. Reading fresh from disk immediately before
/// every write closes that window.
class FavoritesNotifier extends Notifier<List<FavoriteQuery>> {
  @override
  List<FavoriteQuery> build() => ref.watch(favoritosRepositoryProvider).load();

  void add(FavoriteQuery favorite) {
    final repo = ref.read(favoritosRepositoryProvider);
    final merged = {for (final f in [...repo.load(), favorite]) f.id: f}
        .values
        .toList();
    state = merged;
    repo.saveAll(merged);
  }

  void remove(String id) {
    final repo = ref.read(favoritosRepositoryProvider);
    final merged =
        repo.load().where((f) => f.id != id).toList();
    state = merged;
    repo.saveAll(merged);
  }
}

final favoritesProvider =
    NotifierProvider<FavoritesNotifier, List<FavoriteQuery>>(
        FavoritesNotifier.new);
