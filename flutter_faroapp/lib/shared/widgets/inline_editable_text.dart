import 'package:flutter/material.dart';

import '../../core/theme/app_theme.dart';

/// README.md Administración: "server name is an inline-editable text field
/// (borderless until hover/focus, then shows a border)" — same treatment
/// for database names. Commits [onChanged] when the field loses focus.
class InlineEditableText extends StatefulWidget {
  const InlineEditableText(
      {super.key,
      required this.value,
      required this.onChanged,
      this.style,
      this.hint});

  final String value;
  final ValueChanged<String> onChanged;
  final TextStyle? style;

  /// Shown in place of the value when empty — e.g. "host:puerto" for a
  /// database entry migrated from before the host field existed.
  final String? hint;

  @override
  State<InlineEditableText> createState() => _InlineEditableTextState();
}

class _InlineEditableTextState extends State<InlineEditableText> {
  late final TextEditingController _controller;
  final _focusNode = FocusNode();
  bool _hovering = false;

  @override
  void initState() {
    super.initState();
    _controller = TextEditingController(text: widget.value);
    _focusNode.addListener(() {
      if (!_focusNode.hasFocus) {
        final trimmed = _controller.text.trim();
        if (trimmed.isNotEmpty && trimmed != widget.value) {
          widget.onChanged(trimmed);
        } else if (trimmed.isEmpty) {
          // Real bug fixed 2026-08-03 (AUDITORIA_CODIGO.md): clearing the
          // field entirely never called onChanged (an empty name/host
          // isn't valid), but the controller was left showing blank text
          // forever — didUpdateWidget only resyncs when `widget.value`
          // itself changes, which it never does here since onChanged was
          // never called. Revert the visible text back to the real value
          // instead of leaving a misleading blank field.
          _controller.text = widget.value;
        }
        setState(() {});
      } else {
        setState(() {});
      }
    });
  }

  @override
  void didUpdateWidget(InlineEditableText oldWidget) {
    super.didUpdateWidget(oldWidget);
    // Without this, the controller only ever reflects whatever `value` was
    // at the moment this widget was first built — real bug found
    // 2026-07-18: importing a config in Administración replaces every
    // Server/DatabaseEntry with brand-new objects, but since nothing here
    // re-synced the controller, every name/host field kept showing the
    // *previous* config's text (Consulta's sidebar didn't have this problem
    // — it renders plain Text straight from props, no local controller to
    // go stale). Skipped while focused so this can't clobber an in-progress
    // edit if `value` happens to change out from under an open field.
    if (widget.value != oldWidget.value && !_focusNode.hasFocus) {
      _controller.text = widget.value;
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    _focusNode.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = context.appTheme.colors;
    final showBorder = _hovering || _focusNode.hasFocus;

    return MouseRegion(
      onEnter: (_) => setState(() => _hovering = true),
      onExit: (_) => setState(() => _hovering = false),
      child: TextField(
        controller: _controller,
        focusNode: _focusNode,
        style: widget.style,
        decoration: InputDecoration(
          isDense: true,
          contentPadding:
              const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
          filled: false,
          hintText: widget.hint,
          hintStyle: widget.style?.copyWith(color: colors.textMuted),
          enabledBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(8),
            borderSide:
                showBorder ? BorderSide(color: colors.border) : BorderSide.none,
          ),
          border: OutlineInputBorder(
            borderRadius: BorderRadius.circular(8),
            borderSide: BorderSide.none,
          ),
          focusedBorder: OutlineInputBorder(
            borderRadius: BorderRadius.circular(8),
            borderSide: BorderSide(color: colors.accent.base, width: 2),
          ),
        ),
      ),
    );
  }
}
