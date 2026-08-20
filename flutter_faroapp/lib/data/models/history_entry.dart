/// Historial "Estado" tag. Read-only-blocked databases used to be their own
/// status ("Bloqueada") back when the mode was a single per-servidor
/// setting that blocked the whole run; now that it's per-database
/// (`DatabaseEntry.mode`), a blocked database is just one more kind of
/// per-database failure, so it folds into `partial` like any other mixed
/// success/failure run — the specific reason is visible per-database in the
/// results card pill instead.
enum HistoryStatus {
  success,
  partial;

  String get label => switch (this) {
        HistoryStatus.success => 'Éxito',
        HistoryStatus.partial => 'Parcial',
      };
}

/// One row in the Historial table — persisted to disk (see
/// `historial_repository.dart`), not session-only.
class HistoryEntry {
  const HistoryEntry({
    required this.id,
    required this.timestamp,
    required this.queryText,
    required this.serverId,
    required this.serverName,
    required this.databaseCount,
    required this.rowCount,
    required this.status,
  });

  final String id;
  final DateTime timestamp;
  final String queryText;
  final String serverId;
  final String serverName;
  final int databaseCount;
  final int rowCount;
  final HistoryStatus status;

  Map<String, Object?> toJson() => {
        'id': id,
        'timestamp': timestamp.toIso8601String(),
        'queryText': queryText,
        'serverId': serverId,
        'serverName': serverName,
        'databaseCount': databaseCount,
        'rowCount': rowCount,
        'status': status.name,
      };

  factory HistoryEntry.fromJson(Map<String, Object?> json) => HistoryEntry(
        id: json['id'] as String,
        timestamp: DateTime.parse(json['timestamp'] as String),
        queryText: json['queryText'] as String,
        serverId: json['serverId'] as String,
        serverName: json['serverName'] as String,
        databaseCount: json['databaseCount'] as int,
        rowCount: json['rowCount'] as int,
        status: HistoryStatus.values.byName(json['status'] as String),
      );
}
