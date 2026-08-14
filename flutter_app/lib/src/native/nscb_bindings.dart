// Raw dart:ffi bindings for the nscb C bridge (src/ffi_bridge.rs).
//
// Contract: every native call returns a heap-allocated C string that must be
// released with nscb_string_free. Errors come back as "ERROR: ..." strings.

import 'dart:ffi';
import 'dart:io';

import 'package:ffi/ffi.dart';

typedef CStrFn0 = Pointer<Utf8> Function();
typedef CStrFn1 = Pointer<Utf8> Function(Pointer<Utf8>);
typedef CStrFn2 = Pointer<Utf8> Function(Pointer<Utf8>, Pointer<Utf8>);
typedef CStrFn3 = Pointer<Utf8> Function(
    Pointer<Utf8>, Pointer<Utf8>, Pointer<Utf8>);
typedef CStrFn4 = Pointer<Utf8> Function(
    Pointer<Utf8>, Pointer<Utf8>, Pointer<Utf8>, Pointer<Utf8>);
typedef CStrFn7 = Pointer<Utf8> Function(Pointer<Utf8>, Pointer<Utf8>,
    Pointer<Utf8>, Pointer<Utf8>, Pointer<Utf8>, Pointer<Utf8>, Pointer<Utf8>);
typedef CompressNative = Pointer<Utf8> Function(
    Pointer<Utf8>, Pointer<Utf8>, Pointer<Utf8>, Int32);
typedef CompressDart = Pointer<Utf8> Function(
    Pointer<Utf8>, Pointer<Utf8>, Pointer<Utf8>, int);
typedef FreeNative = Void Function(Pointer<Utf8>);
typedef FreeDart = void Function(Pointer<Utf8>);

class NscbBindings {
  NscbBindings._(DynamicLibrary lib)
      : configureTempRoot = lib
            .lookupFunction<CStrFn1, CStrFn1>('nscb_configure_temp_root'),
        merge = lib.lookupFunction<CStrFn4, CStrFn4>('nscb_merge'),
        compress =
            lib.lookupFunction<CompressNative, CompressDart>('nscb_compress'),
        decompress = lib.lookupFunction<CStrFn2, CStrFn2>('nscb_decompress'),
        renamePath = lib.lookupFunction<CStrFn7, CStrFn7>('nscb_rename_path'),
        scanDirectory =
            lib.lookupFunction<CStrFn3, CStrFn3>('nscb_scan_directory'),
        scanFaultyFiles =
            lib.lookupFunction<CStrFn2, CStrFn2>('nscb_scan_faulty_files'),
        refreshTitleDb =
            lib.lookupFunction<CStrFn1, CStrFn1>('nscb_refresh_titledb'),
        libraryStatus =
            lib.lookupFunction<CStrFn3, CStrFn3>('nscb_library_status'),
        getLogs = lib.lookupFunction<CStrFn0, CStrFn0>('nscb_get_logs'),
        deleteFile = lib.lookupFunction<CStrFn1, CStrFn1>('nscb_delete_file'),
        contentList =
            lib.lookupFunction<CStrFn2, CStrFn2>('nscb_content_list'),
        fileList = lib.lookupFunction<CStrFn2, CStrFn2>('nscb_file_list'),
        suggestedFileName = lib
            .lookupFunction<CStrFn4, CStrFn4>('nscb_suggested_file_name'),
        stringFree =
            lib.lookupFunction<FreeNative, FreeDart>('nscb_string_free');

  final CStrFn1 configureTempRoot;
  final CStrFn4 merge;
  final CompressDart compress;
  final CStrFn2 decompress;
  final CStrFn7 renamePath;
  final CStrFn3 scanDirectory;
  final CStrFn2 scanFaultyFiles;
  final CStrFn1 refreshTitleDb;
  final CStrFn3 libraryStatus;
  final CStrFn0 getLogs;
  final CStrFn1 deleteFile;
  final CStrFn2 contentList;
  final CStrFn2 fileList;
  final CStrFn4 suggestedFileName;
  final FreeDart stringFree;

  static NscbBindings? _instance;

  static NscbBindings get instance => _instance ??= NscbBindings._(_open());

  static DynamicLibrary _open() {
    // NSCB_LIB_PATH lets host-side tests point at target/{debug,release}.
    final override = Platform.environment['NSCB_LIB_PATH'];
    if (override != null && override.isNotEmpty) {
      return DynamicLibrary.open(override);
    }
    if (Platform.isIOS) {
      // Statically linked into the Runner binary via -force_load.
      return DynamicLibrary.process();
    }
    if (Platform.isAndroid) {
      return DynamicLibrary.open('libnscb.so');
    }
    if (Platform.isLinux) {
      return DynamicLibrary.open('libnscb.so');
    }
    if (Platform.isMacOS) {
      return DynamicLibrary.open('libnscb.dylib');
    }
    throw UnsupportedError(
        'nscb is not available on ${Platform.operatingSystem}');
  }

  /// Calls a native function with UTF-8 string arguments, copies the returned
  /// string, and frees the native allocation.
  String call(Pointer<Utf8> Function(List<Pointer<Utf8>>) fn,
      List<String> args) {
    final pointers = args.map((a) => a.toNativeUtf8()).toList();
    try {
      final resultPtr = fn(pointers);
      if (resultPtr == nullptr) {
        return 'ERROR: native call returned null';
      }
      final result = resultPtr.toDartString();
      stringFree(resultPtr);
      return result;
    } finally {
      for (final p in pointers) {
        malloc.free(p);
      }
    }
  }
}
