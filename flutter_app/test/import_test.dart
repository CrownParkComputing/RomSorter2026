import 'dart:io';

import 'package:archive/archive_io.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rom_sorter/src/import.dart';

void main() {
  late Directory tmp;
  late String importDir;
  late String libraryDir;

  setUp(() {
    tmp = Directory.systemTemp.createTempSync('import_test');
    importDir = '${tmp.path}/import';
    libraryDir = '${tmp.path}/library';
    Directory(importDir).createSync(recursive: true);
    Directory(libraryDir).createSync(recursive: true);
  });

  tearDown(() => tmp.deleteSync(recursive: true));

  test('moves loose ROMs, extracts zips, reports RARs', () async {
    // Loose ROM in a subfolder (import scans recursively).
    Directory('$importDir/sub').createSync();
    File('$importDir/sub/GameA [0100000000010000].NSP')
        .writeAsBytesSync(List.filled(64, 1));

    // Zip containing a ROM and a readme.
    final encoder = ZipFileEncoder()..create('$importDir/bundle.zip');
    final romInZip = File('${tmp.path}/GameB [0100000000020000].nsz')
      ..writeAsBytesSync(List.filled(64, 2));
    final readme = File('${tmp.path}/readme.txt')..writeAsStringSync('hi');
    await encoder.addFile(romInZip);
    await encoder.addFile(readme);
    await encoder.close();

    // RAR (content irrelevant; only counted).
    File('$importDir/GameC.part1.rar').writeAsBytesSync(List.filled(8, 3));

    final summary = await importFromFolder(importDir, libraryDir);

    expect(summary, contains('moved 1 file(s)'));
    expect(summary, contains('extracted 1 from zip'));
    expect(summary, contains('1 RAR'));
    expect(
        File('$libraryDir/GameA [0100000000010000].NSP').existsSync(), isTrue);
    expect(
        File('$libraryDir/GameB [0100000000020000].nsz').existsSync(), isTrue);
    expect(File('$libraryDir/readme.txt').existsSync(), isFalse);
    // Loose ROM was moved out of import; zip stays.
    expect(
        File('$importDir/sub/GameA [0100000000010000].NSP').existsSync(),
        isFalse);
    expect(File('$importDir/bundle.zip').existsSync(), isTrue);
  });

  test('empty folder reports nothing to import', () async {
    final summary = await importFromFolder(importDir, libraryDir);
    expect(summary, contains('Nothing to import'));
  });
}
