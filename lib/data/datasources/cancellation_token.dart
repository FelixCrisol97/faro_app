/// Backs the "Ejecutar/Cancelar" toggle button. Beyond the `isCancelled`
/// flag (checked by `QueryExecutionService.run` to discard a result that's
/// already fully arrived), [onCancel] lets each connector react
/// immediately — closing an in-flight connection (`PostgresConnector`) or
/// killing the isolate a query is running in (`SqlServerConnector`) —
/// instead of just waiting for a query that keeps running server-side.
class CancellationToken {
  bool _cancelled = false;
  final List<void Function()> _listeners = [];

  bool get isCancelled => _cancelled;

  void cancel() {
    if (_cancelled) return;
    _cancelled = true;
    for (final listener in List.of(_listeners)) {
      listener();
    }
    _listeners.clear();
  }

  /// Registers [listener] to run when [cancel] is called — immediately, if
  /// it already has been.
  void onCancel(void Function() listener) {
    if (_cancelled) {
      listener();
    } else {
      _listeners.add(listener);
    }
  }
}
