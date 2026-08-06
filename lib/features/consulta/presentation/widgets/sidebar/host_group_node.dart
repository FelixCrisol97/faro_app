import 'package:flutter/material.dart';
import 'package:flutter_lucide/flutter_lucide.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../../core/constants/db_engine.dart';
import '../../../../../core/theme/app_spacing.dart';
import '../../../../../core/theme/app_theme.dart';
import '../../../../../data/models/database_entry.dart';
import '../../../../../data/models/server.dart';
import '../../../../../shared/widgets/discover_databases_dialog.dart';
import 'database_check_row.dart';

/// One IP/host within a servidor, as a collapsible sub-node — user request,
/// 2026-07-24: a servidor can group databases across genuinely different
/// hosts (see [Server]'s doc comment), but until now they all rendered as
/// one flat list, with no way to tell which databases actually share an IP
/// or to look for more databases on a *specific* one of those IPs (the
/// "Descubrir"/"Agregar todas" actions up on [ServerNode] always guessed
/// the servidor's *first* database's host — see
/// `discover_databases_dialog.dart`'s bootstrap fix for the connection-side
/// half of this bug). `ServerNode` only renders one of these per distinct
/// host, and only once a servidor actually has more than one — a
/// single-host servidor (still the common case) keeps looking exactly like
/// it did before, no extra tree level.
class HostGroupNode extends ConsumerStatefulWidget {
  const HostGroupNode({
    super.key,
    required this.server,
    required this.host,
    required this.databases,
    required this.engine,
  });

  final Server server;
  final String host;
  final List<DatabaseEntry> databases;
  final DbEngine engine;

  @override
  ConsumerState<HostGroupNode> createState() => _HostGroupNodeState();
}

class _HostGroupNodeState extends ConsumerState<HostGroupNode> {
  bool _expanded = true;

  @override
  Widget build(BuildContext context) {
    final colors = context.appTheme.colors;
    final typography = context.appTheme.typography;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Material(
          color: Colors.transparent,
          borderRadius: const BorderRadius.all(Radius.circular(8)),
          child: InkWell(
            borderRadius: const BorderRadius.all(Radius.circular(8)),
            onTap: () => setState(() => _expanded = !_expanded),
            child: Padding(
              padding: const EdgeInsets.symmetric(
                  horizontal: AppSpacing.space1, vertical: 4),
              child: Row(
                children: [
                  AnimatedRotation(
                    turns: _expanded ? 0.25 : 0,
                    duration: const Duration(milliseconds: 120),
                    child: Icon(LucideIcons.chevron_right,
                        size: 12, color: colors.textMuted),
                  ),
                  const SizedBox(width: 2),
                  Icon(LucideIcons.network, size: 12, color: colors.textMuted),
                  const SizedBox(width: 6),
                  Expanded(
                    child: Text(
                      widget.host.isEmpty ? '(sin host)' : widget.host,
                      overflow: TextOverflow.ellipsis,
                      style: typography.caption.copyWith(
                        fontSize: 11,
                        fontWeight: FontWeight.w600,
                        color: colors.textMuted,
                      ),
                    ),
                  ),
                  Text('${widget.databases.length}',
                      style: typography.caption
                          .copyWith(fontSize: 11, color: colors.textMuted)),
                  Tooltip(
                    message: 'Descubrir más bases de datos en esta IP',
                    child: InkWell(
                      borderRadius: const BorderRadius.all(Radius.circular(6)),
                      onTap: widget.host.isEmpty
                          ? null
                          : () => showDiscoverDatabasesDialog(
                              context, ref, widget.server,
                              from: widget.databases.first),
                      child: Padding(
                        padding: const EdgeInsets.all(4),
                        child: Icon(LucideIcons.database_zap,
                            size: 12, color: colors.accent.base),
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
        if (_expanded)
          for (final db in widget.databases)
            DatabaseCheckRow(
              key: ValueKey(db.id),
              serverId: widget.server.id,
              server: widget.server,
              database: db,
              engine: widget.engine,
            ),
      ],
    );
  }
}
