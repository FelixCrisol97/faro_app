/// A saved query, shown as a card in Favoritos (README.md "Favoritos").
class FavoriteQuery {
  const FavoriteQuery({
    required this.id,
    required this.name,
    required this.queryText,
    required this.createdAt,
  });

  final String id;
  final String name;
  final String queryText;
  final DateTime createdAt;

  Map<String, Object?> toJson() => {
        'id': id,
        'name': name,
        'queryText': queryText,
        'createdAt': createdAt.toIso8601String(),
      };

  factory FavoriteQuery.fromJson(Map<String, Object?> json) => FavoriteQuery(
        id: json['id'] as String,
        name: json['name'] as String,
        queryText: json['queryText'] as String,
        createdAt: DateTime.parse(json['createdAt'] as String),
      );
}
