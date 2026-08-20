/// Two thin `int` wrappers (Dart extension types — zero runtime cost, erase
/// to a plain `int`) that let the compiler catch what a bare `int` return
/// type can't: passing an absolute script offset where a statement-relative
/// one is expected, or vice versa.
///
/// Real bug class fixed 2026-08-19 (AUDITORIA_CODIGO.md): before this,
/// [AnalyzedStatement]'s absolute and relative offsets were both plain
/// `int`, so nothing stopped a future caller from handing an editor cursor
/// position (absolute) straight to `resolveScopeAt` (which expects
/// relative) without going through `AnalyzedStatement.toRelativeOffset`
/// first — a silent-until-wrong-highlight kind of bug, exactly the sort
/// this module's own "never throws" contract can't protect against on its
/// own. See `sql_script_analyzer.dart`'s `AnalyzedStatement` doc comment
/// for the two spaces these distinguish.
extension type const AbsoluteOffset(int value) {}

/// See [AbsoluteOffset].
extension type const RelativeOffset(int value) {}
