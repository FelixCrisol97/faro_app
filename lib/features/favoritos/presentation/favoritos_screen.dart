import 'package:flutter/material.dart';
import 'package:flutter_lucide/flutter_lucide.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_spacing.dart';
import '../../../core/theme/app_theme.dart';
import '../../../data/models/favorite_query.dart';
import '../../../shared/navigation/app_screen.dart';
import '../../../shared/widgets/app_button.dart';
import '../../../shared/widgets/app_card.dart';
import '../../consulta/application/consulta_providers.dart';
import '../../consulta/application/query_tabs_providers.dart';
import '../application/favoritos_providers.dart';

/// README.md "3. Favoritos": a responsive card grid.
class FavoritosScreen extends ConsumerWidget {
  const FavoritosScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final favorites = ref.watch(favoritesProvider);
    final typography = context.appTheme.typography;

    if (favorites.isEmpty) {
      return Center(
        child: Text('Guarda una consulta desde Consulta para verla aquí.',
            style: typography.body),
      );
    }

    return Padding(
      padding: const EdgeInsets.all(AppSpacing.space4),
      child: GridView.builder(
        gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
          maxCrossAxisExtent: 320,
          mainAxisExtent: 190,
          crossAxisSpacing: AppSpacing.space3,
          mainAxisSpacing: AppSpacing.space3,
        ),
        itemCount: favorites.length,
        itemBuilder: (context, index) =>
            _FavoriteCard(favorite: favorites[index]),
      ),
    );
  }
}

class _FavoriteCard extends ConsumerWidget {
  const _FavoriteCard({required this.favorite});
  final FavoriteQuery favorite;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final typography = context.appTheme.typography;

    return AppCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(favorite.name, style: typography.heading),
          const SizedBox(height: AppSpacing.space1),
          Expanded(
            child: Text(
              favorite.queryText,
              style: typography.monospace.copyWith(fontSize: 12),
              maxLines: 5,
              overflow: TextOverflow.ellipsis,
            ),
          ),
          Row(
            children: [
              AppButton(
                label: 'Usar',
                variant: AppButtonVariant.primary,
                onPressed: () {
                  ref
                      .read(sqlEditorProvider.notifier)
                      .loadText(favorite.queryText);
                  // Same reasoning as Historial "Reusar" — switch back to
                  // the "Consulta" home pane so the loaded text is
                  // actually visible if a query tab was active.
                  ref.read(queryTabsProvider.notifier).activate(null);
                  ref.read(currentScreenProvider.notifier).state =
                      AppScreen.consulta;
                },
              ),
              const Spacer(),
              AppIconButton(
                icon: LucideIcons.trash_2,
                tooltip: 'Eliminar',
                onPressed: () =>
                    ref.read(favoritesProvider.notifier).remove(favorite.id),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
