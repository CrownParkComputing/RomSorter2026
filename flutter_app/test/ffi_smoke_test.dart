// Host-side proof that the Dart bindings match the Rust C ABI.
//
// Run with the Linux cdylib:
//   cargo build --lib          (in the repo root)
//   NSCB_LIB_PATH=../target/debug/libnscb.so flutter test test/ffi_smoke_test.dart
//
// Skipped when NSCB_LIB_PATH is not set so plain `flutter test` stays green.

import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:rom_sorter/src/native/nscb.dart';
import 'package:rom_sorter/src/native/nscb_bindings.dart';

void main() {
  final libPath = Platform.environment['NSCB_LIB_PATH'];
  final available = libPath != null && File(libPath).existsSync();

  test('every symbol resolves and strings round-trip', () {
    // Constructing the bindings looks up all 15 symbols eagerly; a signature
    // or name mismatch fails right here.
    final b = NscbBindings.instance;

    final dir = Directory.systemTemp.createTempSync('nscb_ffi');
    addTearDown(() => dir.deleteSync(recursive: true));

    final ok = Nscb.configureTempRoot(dir.path);
    expect(ok, startsWith('OK:'));

    expect(
      () => Nscb.configureTempRoot('   '),
      throwsA(isA<NscbException>()),
    );

    // Exercise an argument-heavy call end to end; without prod.keys it must
    // come back as the bridge's error convention, proving arg marshalling.
    expect(
      b.call((p) => b.scanDirectory(p[0], p[1], p[2]), [dir.path, '', '/tmp']),
      startsWith('ERROR:'),
    );

    Nscb.logs(); // must not crash or leak
  }, skip: available ? false : 'NSCB_LIB_PATH not set');
}
