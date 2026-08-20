import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:uuid/uuid.dart';

import '../../data/models/favorite_query.dart';
import '../../features/favoritos/application/favoritos_providers.dart';
import 'app_button.dart';
import 'app_dialog.dart';

const _uuid = Uuid();

/// "Guardar como favorito" — extracted 2026-08-13 from `toolbar_card.dart`'s
/// private `_saveFavorite` (kept working there unchanged, now delegating
/// here) so Historial's rows can offer the same action directly — user
/// asked "de que me sirve ver el historial si no puedo... guardala a
/// favoritos" (2026-08-13).
Future<void> showSaveFavoriteDialog(
    BuildContext context, WidgetRef ref, String queryText) async {
  final text = queryText.trim();
  if (text.isEmpty) return;
  final nameController = TextEditingController();

  try {
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
        queryText: queryText,
        createdAt: DateTime.now()));
  } finally {
    nameController.dispose();
  }
}
