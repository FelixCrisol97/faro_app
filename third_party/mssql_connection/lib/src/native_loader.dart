import 'dart:ffi';
import 'dart:io' show Platform, File, Directory;

import 'package:ffi/ffi.dart';

import 'native_logger.dart';

class NativeLoader {
  static DynamicLibrary loadDBLib() {
    NativeLogger.i('loadDBLib: platform=${Platform.operatingSystem}');
    if (Platform.isAndroid) {
      NativeLogger.i('Android: opening libsybdb.so');
      return DynamicLibrary.open('libsybdb.so');
    } else if (Platform.isIOS) {
      // iOS links the XCFramework statically via CocoaPods; use process.
      NativeLogger.i('iOS: using DynamicLibrary.process()');
      return DynamicLibrary.process();
    } else if (Platform.isMacOS) {
      // Expect the dylib to be available on the system or bundled appropriately.
      // Try common names in order.
      NativeLogger.i('macOS: trying common sybdb dylib names');
      for (final name in ['libsybdb.dylib', 'libsybdb.5.dylib']) {
        try {
          NativeLogger.i('macOS: trying $name');
          final lib = DynamicLibrary.open(name);
          NativeLogger.i('macOS: opened $name');
          return lib;
        } catch (e) {
          NativeLogger.w('macOS: failed $name -> $e');
        }
      }
    } else if (Platform.isLinux) {
      // Prefer bundled linux/Libraries first
      NativeLogger.i('Linux[DB]: building candidate directories');
      final candidateDirs = <String>[];
      try {
        final scriptDir = File.fromUri(Platform.script).parent;
        final root = scriptDir.parent; // repo root when running from tool/
        final rootPath = root.path;
        candidateDirs.add('$rootPath/linux/Libraries');
      } catch (_) {}
      try {
        final cwd = Directory.current.path;
        candidateDirs.add('$cwd/linux/Libraries');
      } catch (_) {}
      NativeLogger.i('Linux[DB]: candidateDirs=${candidateDirs.join('; ')}');
      for (final dir in candidateDirs) {
        for (final name in [
          'libsybdb.so',
          'libsybdb.so.5',
          'libsybdb.so.5.1.0',
        ]) {
          final p = '$dir/$name';
          try {
            NativeLogger.i('Linux[DB]: trying $p');
            final lib = DynamicLibrary.open(p);
            NativeLogger.i('Linux[DB]: opened $p');
            return lib;
          } catch (e) {
            NativeLogger.w('Linux[DB]: failed $p -> $e');
          }
        }
      }
      // Fallback to default name resolution on the system
      NativeLogger.i('Linux[DB]: falling back to system names');
      for (final name in [
        'libsybdb.so',
        'libsybdb.so.5',
        'libsybdb.so.5.1.0',
      ]) {
        try {
          NativeLogger.i('Linux[DB]: trying $name');
          final lib = DynamicLibrary.open(name);
          NativeLogger.i('Linux[DB]: opened $name');
          return lib;
        } catch (e) {
          NativeLogger.w('Linux[DB]: failed $name -> $e');
        }
      }
    } else if (Platform.isWindows) {
      final tried = <String>[];
      Object? lastErr;
      // Configure modern DLL search behavior to include AddDllDirectory entries
      _setDefaultDllDirectories();
      // Real bug (2026-07-19): this used to try `DynamicLibrary.open
      // ('sybdb.dll')` by bare name *first*, before anything else. In a dev
      // run the DLLs live in a subfolder (windows\Libraries\bin\, not next
      // to the running exe), so that attempt reliably failed and fell
      // through to the candidateDirs loop below — which explicitly
      // preloads ct.dll (with SetDllDirectory + LOAD_WITH_ALTERED_SEARCH_
      // PATH) *before* opening sybdb.dll. In a packaged build, though, all
      // the DLLs get copied flat, directly next to faro.exe — Windows'
      // default DLL search order finds 'sybdb.dll' there immediately, so
      // the bare-name attempt succeeded and skipped the entire careful
      // preload sequence. sybdb.dll evidently depends on ct.dll already
      // being resident for its charset/iconv handling to initialize
      // correctly — loaded without that preload step, it still connects
      // and queries fine, just silently corrupts non-ASCII characters
      // (NVARCHAR came back with accents mangled — see
      // sqlserver_connector.dart's history comment for the full chase).
      // Skipping the bare-name shortcut entirely and always going through
      // candidateDirs (which includes the exe's own directory as a
      // fallback, so the packaged flat-layout case still resolves) makes
      // dev and packaged builds initialize identically.

      // Build candidate directories (prefer bundled locations first)
      final candidateDirs = <String>[];
      try {
        final scriptDir = File.fromUri(Platform.script).parent;
        final root = scriptDir.parent; // repo root when running from tool/
        final rootPath = root.path;
        candidateDirs.addAll(['$rootPath\\windows\\Libraries\\bin']);
      } catch (_) {}
      // Also add fallbacks relative to the current working directory
      try {
        final cwd = Directory.current.path;
        candidateDirs.addAll(['$cwd\\windows\\Libraries\\bin', cwd]);
      } catch (_) {}
      // And relative to the running executable itself — Directory.current
      // usually matches this for a double-clicked exe, but isn't
      // guaranteed (e.g. a shortcut with a different "Start in"), so don't
      // rely on that coincidence alone for the packaged/flat-layout case.
      try {
        candidateDirs.add(File(Platform.resolvedExecutable).parent.path);
      } catch (_) {}
      NativeLogger.i('Windows[DB]: candidateDirs=${candidateDirs.join('; ')}');

      // Try to load from each candidate dir; ensure ct.dll first then sybdb.dll
      for (final dir in candidateDirs) {
        try {
          NativeLogger.i('Windows[DB]: trying dir=$dir');
          _setDllDirectory(dir);
          NativeLogger.i('Windows[DB]: SetDllDirectory($dir)');
          // Preload common dependencies if present (OpenSSL) — tries both
          // the 1.1 and 3.x naming conventions, not just 1.1. Real bug
          // fixed 2026-08-03 (AUDITORIA_CODIGO.md): hardcoding only the
          // 1.1 names meant a future rebuild against OpenSSL 3.x (or an
          // ARM64 build using different names) would silently preload
          // nothing here, reproducing the exact DLL-load-order bug this
          // same function already had to fix once (sybdb.dll depending on
          // a sibling DLL being resident first for its charset handling
          // to initialize correctly) — just triggered by a different
          // binary swap instead of the original bare-name-first shortcut.
          for (final name in ['libcrypto-3-x64.dll', 'libcrypto-1_1-x64.dll']) {
            final crypto = '$dir\\$name';
            if (File(crypto).existsSync()) {
              _preloadWithAlteredSearchPath(crypto);
              NativeLogger.i('Windows[DB]: preload $crypto');
              break;
            }
          }
          for (final name in ['libssl-3-x64.dll', 'libssl-1_1-x64.dll']) {
            final ssl = '$dir\\$name';
            if (File(ssl).existsSync()) {
              _preloadWithAlteredSearchPath(ssl);
              NativeLogger.i('Windows[DB]: preload $ssl');
              break;
            }
          }
          final ct = '$dir\\ct.dll';
          final db = '$dir\\sybdb.dll';
          // Preload using LoadLibraryExW so dependencies resolve from same dir
          _preloadWithAlteredSearchPath(ct);
          NativeLogger.i('Windows[DB]: preload $ct');
          // _preloadWithAlteredSearchPath(db);
          // NativeLogger.i('Windows[DB]: preload $db');
          tried.add(ct + (File(ct).existsSync() ? ' (exists)' : ' (missing)'));
          tried.add(db + (File(db).existsSync() ? ' (exists)' : ' (missing)'));
          NativeLogger.i('Windows[DB]: opening $db');
          // Ensure ct.dll is fully loaded before sybdb.dll
          if (File(ct).existsSync()) {
            try {
              DynamicLibrary.open(ct);
              NativeLogger.i('Windows[DB]: opened $ct');
            } catch (e) {
              NativeLogger.w('Windows[DB]: open ct.dll failed -> $e');
            }
          }
          return DynamicLibrary.open(db);
        } catch (e) {
          NativeLogger.w('Windows[DB]: failed -> $e');
          lastErr = e; /* try next dir */
        }
      }
      throw UnsupportedError(
        'Could not load FreeTDS DB-Lib for this platform. Tried: ${tried.join('; ')}${lastErr != null ? ' | Last error: $lastErr' : ''}',
      );
    }
    throw UnsupportedError('Could not load FreeTDS DB-Lib for this platform.');
  }

  // Real cleanup 2026-08-03 (AUDITORIA_CODIGO.md): `loadCTLib()` (FreeTDS
  // CT-Library, an alternative to the DB-Library API `loadDBLib()` above
  // actually uses) was dead code — confirmed via grep, nothing in this
  // package or the main app ever called it — and had already diverged
  // from `loadDBLib()`'s real fixes (it never called
  // `_setDefaultDllDirectories()` and never preloaded OpenSSL), so reviving
  // it without noticing that divergence would have reintroduced the exact
  // charset bug `loadDBLib()` had to fix. Removed outright rather than
  // patched to match, since nothing depends on CT-Library at all right
  // now — if that ever changes, it should be rebuilt against whatever
  // `loadDBLib()` looks like at that time, not resurrected as-is.

  static void _setDllDirectory(String dir) {
    try {
      final k32 = DynamicLibrary.open('kernel32.dll');
      final setDllDir = k32
          .lookupFunction<
            Int32 Function(Pointer<Utf16>),
            int Function(Pointer<Utf16>)
          >('SetDllDirectoryW');
      final p = dir.toNativeUtf16();
      setDllDir(p);
      malloc.free(p);
    } catch (_) {}
  }

  // Configure default DLL directory search behavior for Windows.
  // Uses SetDefaultDllDirectories to restrict search to SAFE directories and
  // adds the current working directory using AddDllDirectory.
  static void _setDefaultDllDirectories() {
    try {
      final k32 = DynamicLibrary.open('kernel32.dll');
      final setDefault = k32
          .lookupFunction<Int32 Function(Uint32), int Function(int)>(
            'SetDefaultDllDirectories',
          );
      // LOAD_LIBRARY_SEARCH_DEFAULT_DIRS = 0x00001000
      setDefault(0x00001000);
      final addDir = k32
          .lookupFunction<
            Pointer<Void> Function(Pointer<Utf16>),
            Pointer<Void> Function(Pointer<Utf16>)
          >('AddDllDirectory');
      final cwd = Directory.current.path.toNativeUtf16();
      addDir(cwd);
      malloc.free(cwd);
    } catch (_) {
      // Ignore if not supported (older OS); best-effort only.
    }
  }

  // Best-effort: Preload a DLL with altered search path so its dependencies are
  // resolved relative to the DLL's own directory. Only used on Windows.
  static void _preloadWithAlteredSearchPath(String dllPath) {
    try {
      final k32 = DynamicLibrary.open('kernel32.dll');
      final loadLibraryEx = k32
          .lookupFunction<
            Pointer<Void> Function(Pointer<Utf16>, Pointer<Void>, Uint32),
            Pointer<Void> Function(Pointer<Utf16>, Pointer<Void>, int)
          >('LoadLibraryExW');
      final p = dllPath.toNativeUtf16();
      // 0x00000008 = LOAD_WITH_ALTERED_SEARCH_PATH
      loadLibraryEx(p, nullptr, 0x00000008);
      malloc.free(p);
      // If h is null, ignore; DynamicLibrary.open will throw a useful error later.
    } catch (_) {
      // Ignore: not fatal; used as a hint only.
    }
  }
}
