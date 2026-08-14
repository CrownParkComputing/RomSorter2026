// Import pipeline: moves loose ROM files from the import folder into the
// library and extracts ROM files out of .zip archives (streamed, so
// multi-gigabyte zips don't load into memory). RAR archives are counted and
// reported as unsupported rather than silently ignored.

import 'dart:io';
import 'dart:isolate';

import 'package:archive/archive_io.dart';

const _romExts = {'.nsp', '.nsz', '.xci', '.xcz'};

bool _isRom(String path) {
  final p = path.toLowerCase();
  return _romExts.any(p.endsWith);
}

bool _isZip(String path) => path.toLowerCase().endsWith('.zip');

bool _isRar(String path) {
  final p = path.toLowerCase();
  return p.endsWith('.rar') || RegExp(r'\.r\d\d$').hasMatch(p);
}

/// Runs on a background isolate; returns a one-line summary for the UI.
Future<String> importFromFolder(String importDir, String libraryDir) {
  return Isolate.run(() async {
    final dir = Directory(importDir);
    if (!dir.existsSync()) return 'Import folder does not exist: $importDir';

    final roms = <String>[];
    final zips = <String>[];
    var rarCount = 0;
    for (final entry in dir.listSync(recursive: true).whereType<File>()) {
      final path = entry.path;
      if (_isRom(path)) {
        roms.add(path);
      } else if (_isZip(path)) {
        zips.add(path);
      } else if (_isRar(path)) {
        rarCount++;
      }
    }

    var moved = 0;
    for (final path in roms) {
      final dest = '$libraryDir/${path.split('/').last}';
      final src = File(path);
      try {
        await src.rename(dest);
      } on FileSystemException {
        // Cross-device move: copy then delete.
        await src.copy(dest);
        await src.delete();
      }
      moved++;
    }

    // Extract ROM entries out of zips; the archive itself is left in place.
    var extracted = 0;
    var zipErrors = 0;
    for (final zipPath in zips) {
      InputFileStream? input;
      try {
        input = InputFileStream(zipPath);
        final zip = ZipDecoder().decodeStream(input);
        for (final entry in zip) {
          if (!entry.isFile || !_isRom(entry.name)) continue;
          final dest = '$libraryDir/${entry.name.split('/').last}';
          if (File(dest).existsSync()) continue;
          final output = OutputFileStream(dest);
          try {
            entry.writeContent(output);
            extracted++;
          } finally {
            await output.close();
          }
        }
      } catch (_) {
        zipErrors++;
      } finally {
        await input?.close();
      }
    }

    final parts = <String>[
      if (moved > 0) 'moved $moved file(s)',
      if (extracted > 0) 'extracted $extracted from zip',
      if (zipErrors > 0) '$zipErrors zip(s) unreadable',
      if (rarCount > 0) 'skipped $rarCount RAR file(s) (not supported yet)',
    ];
    return parts.isEmpty
        ? 'Nothing to import in $importDir'
        : 'Import: ${parts.join(', ')}';
  });
}
