import 'dart:async';
import 'dart:convert';
import 'dart:ffi';
import 'dart:io';
import 'dart:isolate';

import 'package:ffi/ffi.dart';
import 'package:mssql_connection/mssql_connection.dart';
import 'package:win32/win32.dart'
    show SetEnvironmentVariable, WaitForSingleObject, CloseHandle;

import 'cancellation_token.dart';
import 'database_connection_config.dart';
import 'db_connector.dart';

/// [DbConnector] for SQL Server, backed by `mssql_connection` (Dart FFI over
/// FreeTDS — the only pure-Dart-package option that's actively maintained,
/// cross-platform, and works on Flutter desktop; see the evaluation notes
/// this replaced for why a pure-Dart TDS client and `dart_mssql` were both
/// ruled out).
///
/// **Important limitation, accepted deliberately (2026-07-17):**
/// `MssqlConnection` is a singleton *per isolate* (`getInstance()` always
/// returns the same object within one isolate, holding one native
/// connection at a time) — it cannot hold several simultaneous connections
/// the way `PostgresConnector` does (one native connection per
/// `Connection.open` call). [runQuery] runs on a fresh, throwaway isolate
/// per call so a stuck query can be killed outright (see below), but calls
/// are still funneled through [_serialized] — deliberately kept
/// *sequential*, not fanned out in parallel like PostgreSQL, since FreeTDS's
/// underlying DB-Library C code has a history of not being safe for truly
/// concurrent connections even across OS threads/isolates within the same
/// process. If SQL Server throughput across many databases at once ever
/// becomes a hard requirement, the alternative is a small intermediary
/// backend service (reopens the native-drivers-only decision — not
/// undertaken here).
///
/// **Real cancellation (2026-07-17):** `mssql_connection`'s FFI calls make
/// blocking native calls, which — unlike a pending `Future` — cannot be
/// interrupted by other Dart code running on the *same* isolate. Running
/// each query on its own [Isolate] means a cancelled query can be dealt
/// with by killing that isolate outright (`Isolate.kill(priority:
/// Isolate.immediate)`), unblocking the caller (and the [_serialized]
/// queue behind it) immediately — even though the underlying native call
/// may keep running in the background until it unblocks on its own and the
/// isolate is actually torn down. There's no way to reach into FreeTDS and
/// abort the blocking call directly (unlike Postgres, where closing the
/// connection while a query is pending does exactly that).
class SqlServerConnector implements DbConnector {
  SqlServerConnector() {
    _configureCharset();
  }

  /// Root cause of the accented-character bug, found 2026-07-18: `FREETDSCONF`
  /// used to be built from `Directory.current` alone, which is only the
  /// project root when launched exactly right (`flutter run` from that
  /// directory, or an IDE launch config that sets the working directory
  /// there — VS Code's F5 does). Launched from any other working directory,
  /// that resolved to a `windows/freetds.conf` that doesn't exist — FreeTDS
  /// then silently fell back to its own default charset handling (which
  /// mangles accented/ñ characters) instead of erroring.
  ///
  /// **Packaged builds (2026-07-19):** `flutter build windows` doesn't know
  /// about `windows/freetds.conf` or the FreeTDS DLLs in `windows/Libraries/
  /// bin/` at all (they're not part of the standard asset bundle) — for a
  /// shipped `.exe`, copy `freetds.conf` and every DLL from
  /// `windows/Libraries/bin/` **flat, directly next to `faro.exe`** in the
  /// `Release` output folder (no `windows/` subfolder — matches how the DLLs
  /// already load via a bare `DynamicLibrary.open('sybdb.dll')`, which
  /// relies on Windows' default search order including the exe's own
  /// directory). [_configureCharset] checks that exe-relative location
  /// first, then falls back to the `Directory.current`-relative dev path —
  /// covers both without needing to know which mode is running. If neither
  /// exists, the warning below fires instead of failing silently again.
  void _configureCharset() {
    final exeDir = File(Platform.resolvedExecutable).parent.path;
    final packagedPath = '$exeDir\\freetds.conf';
    final devPath = '${Directory.current.path}\\windows\\freetds.conf';
    final packagedExists = File(packagedPath).existsSync();
    final confPath = packagedExists ? packagedPath : devPath;
    final confExists = File(confPath).existsSync();

    // Real cleanup 2026-08-03 (AUDITORIA_CODIGO.md): this used to
    // unconditionally append a diagnostic entry to `%TEMP%\faro_debug.log`
    // on every single app startup, forever (no rotation/cap) — added
    // 2026-07-19 to chase the accented-character corruption bug in
    // packaged builds, back when `freetds.conf` path resolution was still
    // a live suspect. That was ruled out the same week (confirmed via a
    // pasted log: the file loads correctly in Release builds too), and
    // this session finally found the real root cause elsewhere entirely
    // (FreeTDS's own Latin-1 NVARCHAR decoding — see
    // `third_party/mssql_connection`'s `decodeDbValue` and
    // [[project_faro_sqlserver_charset_fix]]) — nothing here was ever the
    // actual cause, so the diagnostic has no more purpose left to serve.

    if (!confExists) {
      // ignore: avoid_print
      print('[SqlServerConnector] WARNING: freetds.conf not found at '
          '"$packagedPath" (packaged) or "$devPath" (dev) — FreeTDS will '
          'silently fall back to its own charset defaults, which corrupts '
          'accented/ñ characters. For a packaged build, copy freetds.conf '
          'next to faro.exe; for dev, launch from the project root '
          '(c:\\software_development\\faro).');
    }
    _setEnv('FREETDSCONF', confPath);
    _setEnv('LANG', 'en_US.UTF-8');
  }

  void _setEnv(String name, String value) {
    final namePtr = name.toNativeUtf16();
    final valuePtr = value.toNativeUtf16();
    try {
      SetEnvironmentVariable(namePtr, valuePtr);
    } finally {
      calloc.free(namePtr);
      calloc.free(valuePtr);
    }
  }

  Future<void> _queue = Future.value();

  /// Runs [action] only after every previously-queued call has finished —
  /// a simple async mutex, since [MssqlConnection] cannot safely be used
  /// by two callers at once, even across isolates (see class doc comment).
  Future<T> _serialized<T>(Future<T> Function() action) {
    final previous = _queue;
    final completer = Completer<void>();
    _queue = completer.future;
    return previous.then((_) => action()).whenComplete(completer.complete);
  }

  @override
  Future<void> testConnection(DatabaseConnectionConfig config) {
    return _serialized(() => _SqlServerProcessLock.run(() async {
      final connection = MssqlConnection.getInstance();
      final connected = await connection.connect(
        ip: config.host,
        port: config.port.toString(),
        databaseName: config.databaseName,
        username: config.username,
        password: config.password,
        timeoutInSeconds: 15,
      );
      if (!connected) {
        throw StateError(
            'No se pudo conectar a ${config.host}:${config.port}/${config.databaseName}.');
      }
      try {
        await connection.getData('SELECT 1');
      } finally {
        await connection.disconnect();
      }
    }));
  }

  @override
  Future<RawQueryResult> runQuery(
    DatabaseConnectionConfig config,
    String sql, {
    CancellationToken? cancellationToken,
  }) {
    return _serialized(() => _runInIsolate(config, sql, cancellationToken));
  }

  Future<RawQueryResult> _runInIsolate(
    DatabaseConnectionConfig config,
    String sql,
    CancellationToken? cancellationToken,
  ) async {
    final receivePort = ReceivePort();
    final errorPort = ReceivePort();
    final completer = Completer<RawQueryResult>();
    Isolate? isolate;

    void finishWithError(Object error) {
      if (!completer.isCompleted) completer.completeError(error);
    }

    receivePort.listen((message) {
      if (completer.isCompleted) return;
      final result = message as Map<String, Object?>;
      if (result['success'] == true) {
        completer.complete(
          RawQueryResult(
            columns: (result['columns'] as List).cast<String>(),
            // Nested cast: isolate message-passing doesn't guarantee the
            // inner lists keep their exact List<Object?> runtime type.
            rows: (result['rows'] as List)
                .map((r) => (r as List).cast<Object?>())
                .toList(),
            affectedRows: result['affected'] as int? ?? 0,
          ),
        );
      } else {
        finishWithError(StateError(result['error'] as String));
      }
    });
    errorPort.listen((error) {
      finishWithError(StateError(
          error is List ? error.first.toString() : error.toString()));
    });

    isolate = await Isolate.spawn(
      _sqlServerIsolateEntryPoint,
      (
        sendPort: receivePort.sendPort,
        host: config.host,
        port: config.port,
        databaseName: config.databaseName,
        username: config.username,
        password: config.password,
        sql: sql,
      ),
      onError: errorPort.sendPort,
    );

    cancellationToken
        ?.onCancel(() => finishWithError(StateError('Consulta cancelada.')));

    try {
      return await completer.future;
    } finally {
      isolate.kill(priority: Isolate.immediate);
      receivePort.close();
      errorPort.close();
    }
  }

  @override
  Future<BulkInsertOutcome> insertRows(
    DatabaseConnectionConfig config,
    String schema,
    String table,
    List<String> columns,
    List<Map<String, Object?>> rows, {
    CancellationToken? cancellationToken,
  }) {
    return _serialized(
        () => _insertRowsInIsolate(config, schema, table, columns, rows, cancellationToken));
  }

  Future<BulkInsertOutcome> _insertRowsInIsolate(
    DatabaseConnectionConfig config,
    String schema,
    String table,
    List<String> columns,
    List<Map<String, Object?>> rows,
    CancellationToken? cancellationToken,
  ) async {
    final receivePort = ReceivePort();
    final errorPort = ReceivePort();
    final completer = Completer<BulkInsertOutcome>();
    Isolate? isolate;
    SendPort? cancelSendPort;

    void finishWithError(Object error) {
      if (!completer.isCompleted) completer.completeError(error);
    }

    receivePort.listen((message) {
      if (completer.isCompleted) return;
      final result = message as Map<String, Object?>;
      if (result['type'] == 'cancelPort') {
        cancelSendPort = result['port'] as SendPort;
        // The token may have already been cancelled before the isolate
        // finished starting up and sent us this port — relay it now
        // instead of losing the signal.
        if (cancellationToken?.isCancelled ?? false) {
          cancelSendPort!.send(null);
        }
        return;
      }
      if (result['success'] == false) {
        finishWithError(StateError(result['error'] as String));
        return;
      }
      completer.complete(BulkInsertOutcome(
        inserted: result['inserted'] as int,
        failures: (result['failures'] as List)
            .cast<Map<String, Object?>>()
            .map((f) => RowInsertError(
                rowIndex: f['rowIndex'] as int,
                message: f['message'] as String))
            .toList(),
      ));
    });
    errorPort.listen((error) {
      finishWithError(StateError(
          error is List ? error.first.toString() : error.toString()));
    });

    isolate = await Isolate.spawn(
      _sqlServerInsertIsolateEntryPoint,
      (
        sendPort: receivePort.sendPort,
        host: config.host,
        port: config.port,
        databaseName: config.databaseName,
        username: config.username,
        password: config.password,
        schema: schema,
        table: table,
        columns: columns,
        rows: rows,
        // Real bug fixed 2026-08-04 (AUDITORIA_CODIGO.md): the loop below
        // only pays the (measurable, live-verified) cost of periodically
        // yielding a real event-loop tick — needed so a cancel signal can
        // even be observed, see the cancellation doc comment further
        // down — when a caller actually asked for cancellation support.
        // Today's one real call site (`csv_import_service.dart`) never
        // passes a token at all, so this keeps that path exactly as fast
        // as before this fix.
        hasCancellationToken: cancellationToken != null,
      ),
      onError: errorPort.sendPort,
    );

    // Real bug fixed 2026-08-04 (AUDITORIA_CODIGO.md): cancelling used to
    // just kill the isolate outright, discarding the `inserted`/`failures`
    // counters it had been accumulating in memory — rows already committed
    // (each row is its own independent statement, never wrapped in a
    // transaction, see this class's `insertRows` doc reference) got
    // reported as if nothing had happened at all, unlike
    // `PostgresConnector.insertRows`, which returns a real partial
    // `BulkInsertOutcome` on cancel. Now: ask the isolate to stop after its
    // current row and report what it has so far — `cancelSendPort` arrives
    // once the isolate's own `ReceivePort` for this is ready. A bounded
    // grace period (covers the isolate being genuinely stuck mid-row on a
    // hung native call, which has no way to notice the cancel signal)
    // falls back to the same abrupt kill as before, so cancellation is
    // still always eventually responsive.
    cancellationToken?.onCancel(() {
      cancelSendPort?.send(null);
      Future.delayed(const Duration(seconds: 5),
          () => finishWithError(StateError('Importación cancelada.')));
    });

    try {
      return await completer.future;
    } finally {
      isolate.kill(priority: Isolate.immediate);
      receivePort.close();
      errorPort.close();
    }
  }
}

/// Process-wide mutual exclusion around every DB-Library session (connect
/// through disconnect) — real bug found 2026-08-03 while investigating an
/// unrelated native memory bug (see AUDITORIA_CODIGO.md/
/// CONTEXTO_SESIONES.md): [SqlServerConnector._serialized] only serializes
/// calls made through *that one instance*, but every app window (main and
/// every query window opened via `desktop_multi_window`) has its own
/// `ProviderScope` and therefore its own separate `SqlServerConnector`
/// instance — while still sharing the same OS process (confirmed: every
/// window is a separate Flutter engine/isolate in the *same* process, not
/// a separate process). FreeTDS/DB-Library's message and error handlers
/// (`dbmsghandle`/`dberrhandle`, installed in `mssql_client.dart`'s
/// `connect()`) are global to the whole *process*, not per-connection —
/// but the Dart callback registered for them (`Pointer.fromFunction`) is
/// only safe to invoke from the isolate that created it. If two windows'
/// throwaway isolates both call `connect()` around the same time, the
/// second one's registration silently invalidates the first one's
/// callback for the entire process — invoking it from the wrong isolate
/// isn't a catchable Dart exception, it can crash the process outright.
///
/// Fix: a named Windows mutex. Unlike a plain Dart `Future`-based lock,
/// this is genuinely shared across isolates that don't share Dart memory
/// — Windows deduplicates by name within the caller's session regardless
/// of which isolate/thread calls `CreateMutexW`, so every isolate that
/// asks for this same name gets a handle to the *same* kernel object.
/// Held for the lifetime of a whole DB-Library session (not just
/// `connect()`) since a message/error can arrive at any point during a
/// query, not only at connect time.
///
/// `WaitForSingleObject` uses a bounded timeout rather than an infinite
/// wait: if a previous holder's isolate is ever killed while holding the
/// lock (e.g. query cancellation via `Isolate.kill`), the *blocking native
/// call itself keeps running in the background until it unblocks on its
/// own* (see `SqlServerConnector`'s class doc comment) — so the `finally`
/// releasing the mutex still runs once that native call returns, same as
/// today's cancellation model. `WAIT_ABANDONED` (a holder's OS thread
/// really did die without releasing) is treated as a successful acquire,
/// per normal Windows semantics — but the timeout is a deliberate backstop
/// in case some other, unanticipated path leaves it held: surfaces one
/// clear error after 30s rather than wedging every window's SQL Server
/// access forever.
class _SqlServerProcessLock {
  static const _name = r'Local\FaroSqlServerDbLibLock';
  static const _waitTimeoutMs = 30000;
  static const _waitAbandoned = 0x80;
  static const _waitTimeout = 0x102;

  static final DynamicLibrary _kernel32 = DynamicLibrary.open('kernel32.dll');

  static final int Function(Pointer<Void>, int, Pointer<Utf16>)
      _createMutexW = _kernel32.lookupFunction<
          IntPtr Function(Pointer<Void>, Int32, Pointer<Utf16>),
          int Function(Pointer<Void>, int, Pointer<Utf16>)>('CreateMutexW');

  static final int Function(int) _releaseMutex = _kernel32.lookupFunction<
      Int32 Function(IntPtr), int Function(int)>('ReleaseMutex');

  /// Runs [action] while holding the process-wide lock. Opens a fresh
  /// handle to the named mutex and closes it again every call — every
  /// caller here is a throwaway isolate that only ever calls [run] once in
  /// its lifetime (query/insert/test-connection), so there's no benefit to
  /// caching the handle, and *not* closing it would leak one real Windows
  /// HANDLE per query for the life of the whole process: a HANDLE belongs
  /// to the process, not the thread/isolate that created it, so it doesn't
  /// get reclaimed just because the isolate that opened it later dies.
  /// Safe to close-then-reopen across calls: the underlying kernel object
  /// stays alive as long as *any* handle anywhere still references it, so
  /// two genuinely-overlapping callers always end up sharing the same
  /// object regardless of this isolate's own open/close timing — this
  /// isolate closing its handle only after releasing the mutex is what
  /// keeps that safe.
  static Future<T> run<T>(Future<T> Function() action) async {
    final namePtr = _name.toNativeUtf16();
    final int handle;
    try {
      handle = _createMutexW(nullptr, 0, namePtr);
    } finally {
      malloc.free(namePtr);
    }
    if (handle == 0) {
      throw StateError(
          'No se pudo crear/abrir el mutex de sincronización de SQL Server (CreateMutexW).');
    }
    try {
      final rc = WaitForSingleObject(handle, _waitTimeoutMs);
      if (rc != 0 && rc != _waitAbandoned) {
        if (rc == _waitTimeout) {
          throw StateError(
              'No se pudo obtener acceso exclusivo a SQL Server a tiempo — '
              'es posible que otra ventana esté usando SQL Server en este '
              'momento. Intenta de nuevo.');
        }
        throw StateError(
            'Falló la espera del mutex de sincronización de SQL Server (WaitForSingleObject rc=$rc).');
      }
      // WAIT_ABANDONED (rc == _waitAbandoned): a previous holder's thread
      // terminated without releasing — Windows still grants ownership to
      // us; proceed exactly as on a normal acquire.
      try {
        return await action();
      } finally {
        _releaseMutex(handle);
      }
    } finally {
      CloseHandle(handle);
    }
  }
}

/// SSMS opens every connection with these `SET` options; FreeTDS/DB-Library
/// (what this connector uses — SSMS instead goes through its own ODBC
/// driver, which sets these by default) doesn't. Several real SQL Server
/// features silently *require* them: `OPENQUERY`/linked-server queries
/// specifically demand `ANSI_NULLS` + `ANSI_WARNINGS` (error 7405
/// otherwise — "Heterogeneous queries require the ANSI_NULLS and
/// ANSI_WARNINGS options..."), and indexed views/computed columns/filtered
/// indexes commonly need `QUOTED_IDENTIFIER` too — the exact "works in
/// SSMS, fails from here" symptom reported 2026-07-21 for an `OPENQUERY`
/// call. Issued once per connection, right after connecting and before the
/// user's own query, in the same session — matches SSMS's own documented
/// connection defaults exactly (not a guess).
const _ansiSessionSettingsSql = '''
SET ANSI_NULLS ON;
SET ANSI_PADDING ON;
SET ANSI_WARNINGS ON;
SET ARITHABORT ON;
SET CONCAT_NULL_YIELDS_NULL ON;
SET QUOTED_IDENTIFIER ON;
SET ANSI_NULL_DFLT_ON ON;
SET NUMERIC_ROUNDABORT OFF;
''';

typedef _SqlServerIsolateRequest = ({
  SendPort sendPort,
  String host,
  int port,
  String databaseName,
  String username,
  String password,
  String sql,
});

/// Runs on its own throwaway [Isolate] (see class doc comment on
/// [SqlServerConnector] for why) — must be a top-level function, and can
/// only communicate back via [SendPort.send] with JSON-safe values.
void _sqlServerIsolateEntryPoint(_SqlServerIsolateRequest request) async {
  final sendPort = request.sendPort;
  try {
    await _SqlServerProcessLock.run(() async {
      final connection = MssqlConnection.getInstance();
      final connected = await connection.connect(
        ip: request.host,
        port: request.port.toString(),
        databaseName: request.databaseName,
        username: request.username,
        password: request.password,
        timeoutInSeconds: 15,
      );
      if (!connected) {
        sendPort.send({
          'success': false,
          'error':
              'No se pudo conectar a ${request.host}:${request.port}/${request.databaseName}.',
        });
        return;
      }
      try {
        try {
          await connection.getData(_ansiSessionSettingsSql);
        } catch (_) {
          // Best-effort — if this particular batch can't run for some reason,
          // don't block the user's actual query on it; queries that don't
          // need these options keep working exactly as before.
        }
        // getDataRaw (not getData) — skips the JSON encode/decode round trip
        // for this SELECT path, where it was a real cost for large result
        // sets (see MssqlClient.executeRaw's doc comment).
        final decoded = await connection.getDataRaw(request.sql);
        final error = decoded['error'] as String?;
        if (error != null) {
          sendPort.send({'success': false, 'error': error});
          return;
        }
        sendPort.send({
          'success': true,
          'columns': decoded['columns'],
          'rows': decoded['rows'],
          'affected': decoded['affected'],
        });
      } finally {
        await connection.disconnect();
      }
    });
  } catch (e) {
    sendPort.send({'success': false, 'error': e.toString()});
  }
}

typedef _SqlServerInsertIsolateRequest = ({
  SendPort sendPort,
  String host,
  int port,
  String databaseName,
  String username,
  String password,
  String schema,
  String table,
  List<String> columns,
  List<Map<String, Object?>> rows,
  bool hasCancellationToken,
});

/// Same throwaway-isolate reasoning as [_sqlServerIsolateEntryPoint] — one
/// connection, then every row in [request.rows] is executed as its own
/// independent parameterized statement (never wrapped in one shared
/// transaction — see [DbConnector.insertRows]'s doc comment for why a
/// failed row must not stop the rest). Column/table names are escaped by
/// doubling `]` and wrapping in `[...]`, same convention already used by
/// the vendored `mssql_connection` fork's own `bulkInsert` fallback
/// (`mssql_client.dart`'s temp-table path).
void _sqlServerInsertIsolateEntryPoint(
    _SqlServerInsertIsolateRequest request) async {
  final sendPort = request.sendPort;
  // Real bug fixed 2026-08-04 (AUDITORIA_CODIGO.md): see the parent's
  // `_insertRowsInIsolate` doc comment — this port lets a cancellation
  // signal reach the row loop below so it can stop and report a real
  // partial result instead of always being killed outright.
  final cancelReceivePort = ReceivePort();
  var cancelled = false;
  cancelReceivePort.listen((_) => cancelled = true);
  sendPort.send({'type': 'cancelPort', 'port': cancelReceivePort.sendPort});
  try {
    await _SqlServerProcessLock.run(() async {
      final connection = MssqlConnection.getInstance();
      final connected = await connection.connect(
        ip: request.host,
        port: request.port.toString(),
        databaseName: request.databaseName,
        username: request.username,
        password: request.password,
        timeoutInSeconds: 15,
      );
      if (!connected) {
        sendPort.send({
          'success': false,
          'error':
              'No se pudo conectar a ${request.host}:${request.port}/${request.databaseName}.',
        });
        return;
      }
      try {
        try {
          await connection.getData(_ansiSessionSettingsSql);
        } catch (_) {
          // Best-effort, same as the regular query path above.
        }

        String bracket(String name) => '[${name.replaceAll(']', ']]')}]';
        final columnList = request.columns.map(bracket).join(', ');
        final placeholderList = request.columns.map((c) => '@$c').join(', ');
        final sql =
            'INSERT INTO ${bracket(request.schema)}.${bracket(request.table)} ($columnList) VALUES ($placeholderList)';

        var inserted = 0;
        final failures = <Map<String, Object?>>[];
        for (var i = 0; i < request.rows.length; i++) {
          // Checked between rows — the same granularity
          // `PostgresConnector.insertRows` already checks at (it can't be
          // observed mid-row either way: the FFI call below blocks this
          // isolate until it returns).
          if (cancelled) break;
          final row = request.rows[i];
          final params = {for (final c in request.columns) '@$c': row[c]};
          try {
            final raw = await connection.writeDataWithParams(sql, params);
            final decoded = jsonDecode(raw) as Map<String, Object?>;
            final error = decoded['error'] as String?;
            if (error != null) {
              failures.add({'rowIndex': i, 'message': error});
            } else {
              inserted++;
            }
          } catch (e) {
            failures.add({'rowIndex': i, 'message': e.toString()});
          }
          // `writeDataWithParams` is a blocking FFI call underneath its
          // `Future` — its `await` completes without ever handing control
          // back to this isolate's event queue, so `cancelReceivePort`'s
          // incoming message would otherwise never get a chance to run
          // (Dart drains the microtask queue exhaustively before checking
          // for the next event-queue task) and `cancelled` above would
          // stay stuck at its initial value for the entire loop. A
          // *zero*-duration `Future.delayed` was tried first and proved
          // unreliable in live testing (still missed the incoming message
          // often enough to fall through to the grace-period fallback
          // below); a real, non-zero timer duration on every row was the
          // only configuration that reliably worked in live testing.
          // **Known reliability limitation, not silently hidden:** even
          // this got flaky again after many prior isolates had already run
          // in the same process within the same test script (still-open
          // audit item "LOGINREC nunca se libera" is one plausible
          // contributor — a per-connect native resource leak accumulating
          // over a long-running app session) — under that condition this
          // still falls back to the same abrupt-kill behavior as before
          // this fix, no worse than the status quo, just not always
          // better either. Only paid at all when a caller actually
          // supplies a token — today's one real call site
          // (`csv_import_service.dart`) doesn't, so this loop runs at full
          // speed there, unaffected.
          if (request.hasCancellationToken) {
            await Future<void>.delayed(const Duration(milliseconds: 1));
          }
        }
        sendPort.send({
          'success': true,
          'inserted': inserted,
          'failures': failures,
        });
      } finally {
        await connection.disconnect();
      }
    });
  } catch (e) {
    sendPort.send({'success': false, 'error': e.toString()});
  } finally {
    cancelReceivePort.close();
  }
}
