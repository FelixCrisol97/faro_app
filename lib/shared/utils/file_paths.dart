/// `FilePicker.platform.saveFile`'s Windows "Guardar como" dialog lets the
/// user delete/omit the suggested extension even when `allowedExtensions`
/// was passed — that parameter only filters the dialog's type dropdown, it
/// does not force-append the extension to whatever the user actually typed
/// (e.g. typing `consulta` instead of `consulta.sql` saves a file with no
/// extension at all). Call this on the path `saveFile` returns, before
/// writing, so every export (SQL, CSV, JSON) always lands with the right
/// extension regardless of what the user typed in the dialog.
String ensureExtension(String path, String extension) {
  final suffix = '.$extension';
  return path.toLowerCase().endsWith(suffix.toLowerCase()) ? path : '$path$suffix';
}
