import 'package:flutter/material.dart';

import '../../../../../core/theme/app_colors.dart';

/// Maps `re_highlight`'s SQL/PostgreSQL token classes to Faro's own design
/// tokens — deliberately NOT one of `re_highlight`'s built-in fixed
/// palettes (atom-one-*, github, etc.), which know nothing about the
/// user's chosen accent color or light/dark theme. Same color assignments
/// `sql_syntax_highlighter.dart` already used for the hand-rolled editor
/// this replaces: keywords in the accent (bold), strings in success-green,
/// numbers in warn-amber, comments muted+italic — so migrating editor
/// engines doesn't change how a script looks to the user.
///
/// Class names confirmed by reading `re_highlight`'s `languages/pgsql.dart`
/// grammar directly (not guessed): `keyword`, `built_in`, `type` (native
/// types like `NUMERIC`/`TIMESTAMPTZ`), `string`, `number`, `comment`,
/// `meta` (`%TYPE`, `$1` positional params), `symbol` (`<<label>>`),
/// `doctag` (TODO/FIXME inside comments).
Map<String, TextStyle> codeHighlightThemeFor(AppColors colors) {
  final keyword =
      TextStyle(color: colors.accent.base, fontWeight: FontWeight.w700);
  final string = TextStyle(color: colors.success.base);
  final number = TextStyle(color: colors.warn.base);
  final comment =
      TextStyle(color: colors.textMuted, fontStyle: FontStyle.italic);
  final plain = TextStyle(color: colors.text);

  return {
    'root': TextStyle(color: colors.text, backgroundColor: colors.surfaceAlt),
    'keyword': keyword,
    'built_in': keyword,
    'type': keyword,
    'literal': keyword,
    'string': string,
    'number': number,
    'comment': comment,
    'doctag': comment,
    'meta': plain,
    'symbol': plain,
    'operator': plain,
  };
}
