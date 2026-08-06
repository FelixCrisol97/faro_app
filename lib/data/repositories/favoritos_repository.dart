import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

import '../models/favorite_query.dart';

/// Persists Favoritos — same shape as `ServersRepository`. Unlike servers
/// (rare, deliberate edits from one window at a time), favorites can be
/// added from any open query window, so `FavoritesNotifier` reads fresh
/// from here immediately before every save instead of blindly overwriting
/// with its own in-memory copy — see that notifier's doc comment.
class FavoritosRepository {
  const FavoritosRepository(this._prefs);

  final SharedPreferences _prefs;

  static const _key = 'faro.favoritos';

  List<FavoriteQuery> load() {
    final raw = _prefs.getString(_key);
    if (raw == null) return const [];
    final decoded = jsonDecode(raw) as List;
    return decoded
        .map((e) => FavoriteQuery.fromJson(e as Map<String, Object?>))
        .toList();
  }

  Future<void> saveAll(List<FavoriteQuery> favorites) {
    final encoded = jsonEncode(favorites.map((f) => f.toJson()).toList());
    return _prefs.setString(_key, encoded);
  }
}
