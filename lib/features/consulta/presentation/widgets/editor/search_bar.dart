import 'package:flutter/material.dart';
import 'package:flutter_lucide/flutter_lucide.dart';

import '../../../../../core/theme/app_shadows.dart';
import '../../../../../core/theme/app_theme.dart';

/// Small, unobtrusive trigger for the find bar (Ctrl+F also opens it) —
/// sits in a corner of the editor (see `sql_editor.dart`'s `Stack`) so it
/// doesn't compete with the query text for space until it's actually
/// needed. User request: "un ícono de lupa... que no moleste la
/// visualización."
class SearchToggleButton extends StatelessWidget {
  const SearchToggleButton({super.key, required this.onTap});

  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final colors = context.appTheme.colors;
    return Tooltip(
      message: 'Buscar en el script (Ctrl+F)',
      child: Material(
        color: colors.surface.withValues(alpha: 0.85),
        shape: const CircleBorder(),
        child: InkWell(
          customBorder: const CircleBorder(),
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.all(6),
            child: Icon(LucideIcons.search, size: 15, color: colors.textMuted),
          ),
        ),
      ),
    );
  }
}

/// The find bar itself — query field, match counter, previous/next, close.
/// Pure UI: all real state (query text, matches, which one is active) lives
/// in `_SqlEditorState`/`HighlightingController`, passed in and reported
/// back via callbacks. [focusNode] arrives pre-wired with its own
/// `onKeyEvent` (Enter/Shift+Enter/Esc — see `_SqlEditorState
/// ._handleSearchKeyEvent`), same convention the main editor's own
/// `FocusNode` already uses, rather than a second, competing key-handling
/// mechanism living here.
class EditorSearchBar extends StatelessWidget {
  const EditorSearchBar({
    super.key,
    required this.controller,
    required this.focusNode,
    required this.matchCount,
    required this.activeMatchIndex,
    required this.onChanged,
    required this.onNext,
    required this.onPrevious,
    required this.onClose,
  });

  final TextEditingController controller;
  final FocusNode focusNode;
  final int matchCount;
  final int activeMatchIndex;
  final ValueChanged<String> onChanged;
  final VoidCallback onNext;
  final VoidCallback onPrevious;
  final VoidCallback onClose;

  @override
  Widget build(BuildContext context) {
    final colors = context.appTheme.colors;
    final typography = context.appTheme.typography;
    final hasQuery = controller.text.isNotEmpty;
    final counterText = !hasQuery
        ? ''
        : matchCount == 0
            ? 'Sin resultados'
            : '${activeMatchIndex + 1} de $matchCount';

    return Material(
      elevation: 0,
      borderRadius: const BorderRadius.all(Radius.circular(8)),
      child: Container(
        width: 260,
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
        decoration: BoxDecoration(
          color: colors.surface,
          borderRadius: const BorderRadius.all(Radius.circular(8)),
          boxShadow: AppShadows.md,
        ),
        child: Row(
          children: [
            Expanded(
              child: SizedBox(
                height: 28,
                child: TextField(
                  controller: controller,
                  focusNode: focusNode,
                  autofocus: true,
                  onChanged: onChanged,
                  style: typography.bodySmall,
                  decoration: InputDecoration(
                    isDense: true,
                    border: InputBorder.none,
                    hintText: 'Buscar…',
                    hintStyle:
                        typography.bodySmall.copyWith(color: colors.textMuted),
                  ),
                ),
              ),
            ),
            if (hasQuery) ...[
              const SizedBox(width: 4),
              Text(counterText,
                  style: typography.caption.copyWith(color: colors.textMuted)),
            ],
            _SearchBarIconButton(
              icon: LucideIcons.chevron_up,
              tooltip: 'Anterior (Shift+Enter)',
              onTap: matchCount == 0 ? null : onPrevious,
            ),
            _SearchBarIconButton(
              icon: LucideIcons.chevron_down,
              tooltip: 'Siguiente (Enter)',
              onTap: matchCount == 0 ? null : onNext,
            ),
            _SearchBarIconButton(
              icon: LucideIcons.x,
              tooltip: 'Cerrar (Esc)',
              onTap: onClose,
            ),
          ],
        ),
      ),
    );
  }
}

class _SearchBarIconButton extends StatelessWidget {
  const _SearchBarIconButton(
      {required this.icon, required this.tooltip, required this.onTap});

  final IconData icon;
  final String tooltip;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final colors = context.appTheme.colors;
    final enabled = onTap != null;
    return Tooltip(
      message: tooltip,
      child: InkWell(
        borderRadius: const BorderRadius.all(Radius.circular(4)),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(4),
          child: Icon(icon,
              size: 14,
              color: enabled ? colors.textMuted : colors.textMuted.withValues(alpha: 0.35)),
        ),
      ),
    );
  }
}
