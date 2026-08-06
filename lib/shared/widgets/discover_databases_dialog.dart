import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:uuid/uuid.dart';

import '../../data/datasources/database_connection_config.dart';
import '../../data/models/database_credentials.dart';
import '../../data/models/database_entry.dart';
import '../../data/models/server.dart';
import '../../data/providers/core_providers.dart';
import '../../data/providers/servers_providers.dart';
import 'app_button.dart';
import 'app_dialog.dart';

const _uuid = Uuid();

/// Used by [showDiscoverDatabasesDialog] — connects through [from] (an
/// already-registered database) to its host and lists what other real
/// databases exist there (`database_discovery_service.dart`). Returns null
/// if nothing was found/connectable — every failure case already shows its
/// own feedback before returning, so the caller just bails out on null.
///
/// **Design history, 2026-07-24 (user-directed):** this used to also offer
/// a server-level entry point with no specific database to anchor to —
/// you typed a host by hand and it fell back to the servidor's *default*
/// credentials. That turned out to have no real use once this per-database
/// action existed (you always need to already know at least one database
/// name to add manually before there's anything to discover *more* of on
/// that host anyway) — worse, it actively confused the credentials story:
/// retyping a different host in that dialog could never fix a failed
/// attempt, because credentials were tied to whichever database you'd
/// started from, not to the host you typed. Removed outright, along with
/// its "Agregar todas sin revisar" sibling — [from] is required now, one
/// mechanism only. The servidor's *default* credentials concept itself
/// (`CredentialsRepository`, the key icon on a server row) stays — it's
/// still the fallback `resolve()` uses for a database with no override of
/// its own.
/// Connect-and-list plumbing shared by [_promptAndDiscover] (single IP,
/// resolves credentials itself so its own "Buscando…" SnackBar still fires
/// at the exact point it always has) and [showDiscoverForMassQueryDialog]
/// (several IPs at once, run concurrently via `Future.wait`). Deliberately
/// never throws — a connection failure comes back as [error] instead. This
/// is load-bearing for the mass-query flow: if this threw, `Future.wait`
/// would discard every *other* group's successful result too once any one
/// group's future rejects (true even with `eagerError: false` — that only
/// changes *when* the combined future rejects, not whether it does).
Future<({List<String> found, String? error})> _discoverOn(
  WidgetRef ref, {
  required Server server,
  required DatabaseEntry from,
  required DatabaseCredentials credentials,
}) async {
  final hostPort = parseHostPort(from.host, server.engine.defaultPort);
  try {
    final found = await ref.read(databaseDiscoveryServiceProvider).discover(
          engine: server.engine,
          host: hostPort.host,
          port: hostPort.port,
          username: credentials.username,
          password: credentials.password,
          bootstrapDatabase: from.databaseName,
        );
    return (found: found, error: null);
  } catch (e) {
    // The empty-credentials case is by far the most common real cause
    // here (a servidor/database with no login configured at all yet, not
    // a wrong password) — call it out explicitly instead of leaving the
    // user to guess from a raw driver exception.
    final hint = credentials.username.isEmpty
        ? ' Este servidor/base de datos no tiene credenciales configuradas — usa el ícono de llave para agregarlas.'
        : '';
    return (
      found: const <String>[],
      error: 'No se pudo conectar a ${from.host}: $e.$hint',
    );
  }
}

Future<({String host, List<String> found})?> _promptAndDiscover(
    BuildContext context, WidgetRef ref, Server server,
    {required DatabaseEntry from}) async {
  if (from.host.isEmpty) {
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
          content:
              Text('Esta base de datos no tiene un host configurado todavía.'),
          duration: Duration(seconds: 4)),
    );
    return null;
  }
  final host = from.host;
  final credentials =
      await ref.read(credentialsRepositoryProvider).resolve(server.id, from.id);
  if (!context.mounted) return null;

  ScaffoldMessenger.of(context).showSnackBar(
    SnackBar(
        content: Text('Buscando bases de datos en $host…'),
        duration: const Duration(seconds: 30)),
  );

  final result =
      await _discoverOn(ref, server: server, from: from, credentials: credentials);
  if (!context.mounted) return null;
  ScaffoldMessenger.of(context).clearSnackBars();

  if (result.error != null) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text(result.error!), duration: const Duration(seconds: 6)));
    return null;
  }

  if (result.found.isEmpty) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
          content: Text('No se encontraron bases de datos nuevas en $host.'),
          duration: const Duration(seconds: 3)),
    );
    return null;
  }

  return (host: host, found: result.found);
}

/// "Descubrir más bases de datos en esta IP" — [from] anchors the search to
/// an already-registered database's host and credentials (its own
/// override, falling back to the servidor's default). Host in (implicit,
/// from [from]), checklist out (lets the user pick which found databases to
/// actually add). Reachable from a right-click on a database row in both
/// Consulta's sidebar and Administración.
Future<void> showDiscoverDatabasesDialog(
    BuildContext context, WidgetRef ref, Server server,
    {required DatabaseEntry from}) async {
  final result = await _promptAndDiscover(context, ref, server, from: from);
  if (result == null || !context.mounted) return;
  final host = result.host;
  final found = result.found;

  // (databaseName, host) — not databaseName alone, since the same real name
  // can legitimately repeat across different hosts within one servidor
  // (that's the whole reason alias/databaseName are split).
  final existing = {
    for (final db in server.databases) (db.databaseName, db.host)
  };
  final selected = {
    for (final name in found)
      if (!existing.contains((name, host))) name
  };

  final confirmed = await showAppDialog<bool>(
    context: context,
    title: 'Bases de datos encontradas',
    body: StatefulBuilder(
      builder: (context, setDialogState) => ConstrainedBox(
        constraints: const BoxConstraints(maxHeight: 320),
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              for (final name in found)
                CheckboxListTile(
                  dense: true,
                  contentPadding: EdgeInsets.zero,
                  controlAffinity: ListTileControlAffinity.leading,
                  value: selected.contains(name),
                  enabled: !existing.contains((name, host)),
                  title: Text(name),
                  subtitle: existing.contains((name, host))
                      ? const Text('Ya agregada')
                      : null,
                  onChanged: existing.contains((name, host))
                      ? null
                      : (checked) => setDialogState(() {
                            if (checked == true) {
                              selected.add(name);
                            } else {
                              selected.remove(name);
                            }
                          }),
                ),
            ],
          ),
        ),
      ),
    ),
    actions: [
      AppButton(
        label: 'Agregar seleccionadas',
        variant: AppButtonVariant.primary,
        autofocus: true,
        onPressed: () => Navigator.of(context).pop(true),
      ),
    ],
  );

  if (confirmed != true || selected.isEmpty) return;

  ref.read(serversProvider.notifier).addDatabases(
    server.id,
    [
      for (final name in selected)
        DatabaseEntry(id: _uuid.v4(), name: name, host: host, databaseName: name),
    ],
  );
}

/// One (server, host) pair worth of discovery, run as part of
/// [showDiscoverForMassQueryDialog]'s `Future.wait` batch. Never throws —
/// see [_discoverOn]'s doc comment for why that matters here.
Future<({List<String> found, String? error})> _discoverForMassGroup(
  WidgetRef ref, {
  required Server server,
  required DatabaseEntry anchor,
}) async {
  if (anchor.host.isEmpty) {
    return (
      found: const <String>[],
      error: 'Esta base de datos no tiene un host configurado todavía.',
    );
  }
  final credentials =
      await ref.read(credentialsRepositoryProvider).resolve(server.id, anchor.id);
  return _discoverOn(ref, server: server, from: anchor, credentials: credentials);
}

/// "Descubrir en todas las IPs seleccionadas" — the multi-host sibling of
/// [showDiscoverDatabasesDialog]. Reads the current mass-query selection
/// (`selectedQueryTargetsProvider`, which can span several servidores, not
/// just several hosts within one) instead of a single anchor database, and
/// discovers on every distinct (servidor, host) pair represented there —
/// so the user doesn't have to repeat "descubrir" once per IP when several
/// are already selected. Still requires explicit confirmation via a
/// checklist before adding anything (2026-07-24 decision: never add
/// without review) — this only batches that review across every selected
/// IP into one dialog instead of one per IP.
Future<void> showDiscoverForMassQueryDialog(
    BuildContext context, WidgetRef ref) async {
  final targets = ref.read(selectedQueryTargetsProvider);
  if (targets.isEmpty) {
    ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
        content: Text('No hay bases de datos seleccionadas.'),
        duration: Duration(seconds: 3)));
    return;
  }

  // One anchor per distinct (servidor, host) — same criterion
  // `host_group_node.dart` already uses (`databases.first`), just applied
  // across the whole selection instead of one host group.
  final groups = <(String, String), ({Server server, DatabaseEntry anchor})>{};
  for (final target in targets) {
    groups.putIfAbsent(
      (target.server.id, target.database.host),
      () => (server: target.server, anchor: target.database),
    );
  }
  final entries = groups.entries.toList();

  ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(entries.length == 1
          ? 'Buscando bases de datos en 1 IP…'
          : 'Buscando bases de datos en ${entries.length} IPs…'),
      duration: const Duration(seconds: 30)));

  // Every group resolves without throwing (see _discoverForMassGroup), so
  // Future.wait can't lose successful results just because another group
  // failed.
  final results = await Future.wait(entries.map(
      (e) => _discoverForMassGroup(ref, server: e.value.server, anchor: e.value.anchor)));

  if (!context.mounted) return;
  ScaffoldMessenger.of(context).clearSnackBars();

  // (databaseName, host) per servidor, same de-dup key as the single-IP
  // dialog — pre-selects everything newly found that isn't already there.
  final existingByGroup = <int, Set<(String, String)>>{};
  final selectedByGroup = <int, Set<String>>{};
  for (var i = 0; i < results.length; i++) {
    final server = entries[i].value.server;
    final host = entries[i].key.$2;
    final existing = {
      for (final db in server.databases) (db.databaseName, db.host)
    };
    existingByGroup[i] = existing;
    selectedByGroup[i] = {
      for (final name in results[i].found)
        if (!existing.contains((name, host))) name
    };
  }

  final anyFound = results.any((r) => r.found.isNotEmpty);

  final confirmed = await showAppDialog<bool>(
    context: context,
    title: 'Bases de datos encontradas',
    body: StatefulBuilder(
      builder: (context, setDialogState) => ConstrainedBox(
        constraints: const BoxConstraints(maxHeight: 400),
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              for (var i = 0; i < entries.length; i++) ...[
                if (i > 0) const SizedBox(height: 12),
                Text(
                  '${entries[i].value.server.name} · ${entries[i].key.$2}',
                  style: const TextStyle(fontWeight: FontWeight.bold),
                  overflow: TextOverflow.ellipsis,
                ),
                const SizedBox(height: 4),
                if (results[i].error != null)
                  Text(results[i].error!)
                else if (results[i].found.isEmpty)
                  const Text('No se encontraron bases de datos nuevas.')
                else
                  for (final name in results[i].found)
                    Builder(builder: (context) {
                      final host = entries[i].key.$2;
                      final alreadyExists =
                          existingByGroup[i]!.contains((name, host));
                      return CheckboxListTile(
                        dense: true,
                        contentPadding: EdgeInsets.zero,
                        controlAffinity: ListTileControlAffinity.leading,
                        value: selectedByGroup[i]!.contains(name),
                        enabled: !alreadyExists,
                        title: Text(name),
                        subtitle:
                            alreadyExists ? const Text('Ya agregada') : null,
                        onChanged: alreadyExists
                            ? null
                            : (checked) => setDialogState(() {
                                  if (checked == true) {
                                    selectedByGroup[i]!.add(name);
                                  } else {
                                    selectedByGroup[i]!.remove(name);
                                  }
                                }),
                      );
                    }),
              ],
            ],
          ),
        ),
      ),
    ),
    actions: anyFound
        ? [
            AppButton(
              label: 'Agregar seleccionadas',
              variant: AppButtonVariant.primary,
              autofocus: true,
              onPressed: () => Navigator.of(context).pop(true),
            ),
          ]
        : [
            AppButton(
              label: 'Cerrar',
              variant: AppButtonVariant.primary,
              autofocus: true,
              onPressed: () => Navigator.of(context).pop(false),
            ),
          ],
  );

  if (confirmed != true) return;

  final byServer = <String, List<DatabaseEntry>>{};
  for (var i = 0; i < entries.length; i++) {
    final server = entries[i].value.server;
    final host = entries[i].key.$2;
    for (final name in selectedByGroup[i]!) {
      byServer.putIfAbsent(server.id, () => []).add(DatabaseEntry(
          id: _uuid.v4(),
          name: name,
          host: host,
          databaseName: name,
          selected: true));
    }
  }
  if (byServer.isEmpty) return;

  final notifier = ref.read(serversProvider.notifier);
  for (final e in byServer.entries) {
    notifier.addDatabases(e.key, e.value);
  }
}
