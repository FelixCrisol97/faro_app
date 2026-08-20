import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../data/providers/core_providers.dart';

/// Which server nodes the tree renders collapsed — persisted so the tree
/// doesn't snap every node back open on every restart. Moved here
/// 2026-08-12 from the now-deleted `features/administracion/` (this same
/// provider used to back only Administración's server cards; the tree's
/// own expand/collapse was, until this move, ephemeral `StatefulWidget`
/// state that reset on every restart) — porting it here means the fix
/// Administración got this session now also applies to the tree, instead
/// of being lost when that screen was absorbed into it.
class CollapsedServerIdsNotifier extends Notifier<Set<String>> {
  @override
  Set<String> build() => ref.watch(settingsRepositoryProvider).loadCollapsedServerIds();

  void toggle(String serverId) {
    final next = {...state};
    if (!next.remove(serverId)) next.add(serverId);
    state = next;
    ref.read(settingsRepositoryProvider).saveCollapsedServerIds(state);
  }
}

final collapsedServerIdsProvider =
    NotifierProvider<CollapsedServerIdsNotifier, Set<String>>(
        CollapsedServerIdsNotifier.new);
