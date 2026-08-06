import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../../core/theme/app_radii.dart';
import '../../../../data/models/database_entry.dart';
import '../../../../shared/widgets/app_tag.dart';

class StatusPill extends StatelessWidget {
  const StatusPill({super.key, required this.status, this.errorMessage});
  final ConnectionTestStatus status;
  final String? errorMessage;

  @override
  Widget build(BuildContext context) {
    return switch (status) {
      ConnectionTestStatus.idle => const SizedBox.shrink(),
      ConnectionTestStatus.testing =>
        const AppTag(label: 'Probando…', variant: AppTagVariant.neutral),
      ConnectionTestStatus.connected =>
        const AppTag(label: 'Conectado', variant: AppTagVariant.success),
      // Same tooltip + copy-to-clipboard treatment results_card.dart already
      // gives a failed per-database query outcome — this used to be a bare
      // "Error" tag with the real cause only reaching debugPrint, never the
      // UI.
      ConnectionTestStatus.failed => Tooltip(
          message: '${errorMessage ?? 'Error desconocido'}\n(clic para copiar)',
          child: InkWell(
            borderRadius: AppRadii.chipRadius,
            onTap: () =>
                _copyError(context, errorMessage ?? 'Error desconocido'),
            child: const AppTag(label: 'Error', variant: AppTagVariant.error),
          ),
        ),
    };
  }

  Future<void> _copyError(BuildContext context, String message) async {
    await Clipboard.setData(ClipboardData(text: message));
    if (!context.mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
          content: Text('Error copiado al portapapeles'),
          duration: Duration(seconds: 2)),
    );
  }
}
