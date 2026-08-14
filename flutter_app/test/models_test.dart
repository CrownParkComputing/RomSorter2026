import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:rom_sorter/src/native/nscb.dart';
import 'package:rom_sorter/src/titledb.dart';

void main() {
  test('ScanGroup parses bridge JSON', () {
    const json = '''
    [{"base_id":"0100000000010000","title_name":"Example",
      "latest_version_db":131072,
      "items":[{"path":"/roms/example.nsp","filename":"example.nsp",
        "title_id":"0100000000010000","version":0,"kind":"Base"}]}]
    ''';
    final groups = (jsonDecode(json) as List)
        .map((e) => ScanGroup.fromJson(e as Map<String, dynamic>))
        .toList();
    expect(groups, hasLength(1));
    expect(groups.first.titleName, 'Example');
    expect(groups.first.latestVersionDb, 131072);
    expect(groups.first.items.single.kind, 'Base');
  });

  test('LibraryTitleStatus tolerates missing optional fields', () {
    const json = '''
    {"title_id":"0100000000010000","title_name":"Example",
     "local_version":0,"latest_version":null,"release_date":null,
     "publisher":null,"languages":[],"description":null,"image_url":null,
     "screenshot_urls":[],"status":"unknown"}
    ''';
    final status =
        LibraryTitleStatus.fromJson(jsonDecode(json) as Map<String, dynamic>);
    expect(status.latestVersion, isNull);
    expect(status.status, 'unknown');
    expect(status.languages, isEmpty);
  });

  test('FaultyFile parses', () {
    const json =
        '{"path":"/roms/bad.nsp","filename":"bad.nsp","reason":"truncated"}';
    final f = FaultyFile.fromJson(jsonDecode(json) as Map<String, dynamic>);
    expect(f.reason, 'truncated');
  });

  // Keep in lockstep with the base_title_id tests in src/nutdb.rs.
  test('baseTitleId matches the Rust rules', () {
    expect(baseTitleId('0100F8F0000A2000'), '0100F8F0000A2000'); // base
    expect(baseTitleId('0100F8F0000A2800'), '0100F8F0000A2000'); // update
    expect(baseTitleId('0100F8F0000A3401'), '0100F8F0000A2000'); // DLC
    expect(baseTitleId('0100b04011743035'), '0100B04011742000'); // DLC, lower
  });
}
