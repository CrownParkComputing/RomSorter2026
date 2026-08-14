// Read-side access to the TitlesDB cache the Rust core maintains
// (<cacheDir>/nutdb.index.json). Parsing happens on a background isolate;
// the list model keeps only the fields the Database tab renders, because the
// full index (descriptions, screenshots) is tens of MB.

import 'dart:convert';
import 'dart:io';
import 'dart:isolate';

class DbTitle {
  DbTitle({
    required this.id,
    required this.name,
    this.publisher,
    this.iconUrl,
    this.releaseDate,
  });

  final String id;
  final String name;
  final String? publisher;
  final String? iconUrl;
  final int? releaseDate;
}

class TitleDb {
  TitleDb._();

  static String indexPath(String cacheDir) => '$cacheDir/nutdb.index.json';

  static bool exists(String cacheDir) => File(indexPath(cacheDir)).existsSync();

  /// Loads the light-weight title list, sorted by name.
  static Future<List<DbTitle>> loadIndex(String cacheDir) {
    final path = indexPath(cacheDir);
    return Isolate.run(() {
      final file = File(path);
      if (!file.existsSync()) return <DbTitle>[];
      final decoded =
          jsonDecode(file.readAsStringSync()) as Map<String, dynamic>;
      final titles = decoded['titles'] as Map<String, dynamic>? ?? const {};
      final list = <DbTitle>[];
      titles.forEach((id, raw) {
        final t = raw as Map<String, dynamic>;
        final name = t['name'] as String?;
        if (name == null || name.isEmpty) return;
        list.add(DbTitle(
          id: id,
          name: name,
          publisher: t['publisher'] as String?,
          iconUrl: (t['iconUrl'] ?? t['bannerUrl']) as String?,
          releaseDate: (t['releaseDate'] as num?)?.toInt(),
        ));
      });
      list.sort((a, b) => a.name.toLowerCase().compareTo(b.name.toLowerCase()));
      return list;
    });
  }

  /// Loads one title's full record (description, screenshots, ...).
  static Future<Map<String, dynamic>?> loadDetail(String cacheDir, String id) {
    final path = indexPath(cacheDir);
    return Isolate.run(() {
      final file = File(path);
      if (!file.existsSync()) return null;
      final decoded =
          jsonDecode(file.readAsStringSync()) as Map<String, dynamic>;
      final titles = decoded['titles'] as Map<String, dynamic>? ?? const {};
      return titles[id] as Map<String, dynamic>?;
    });
  }

  static String formatReleaseDate(int value) {
    final year = value ~/ 10000;
    final month = (value ~/ 100) % 100;
    final day = value % 100;
    if (year >= 1900 && month >= 1 && month <= 12 && day >= 1 && day <= 31) {
      return '$year-${month.toString().padLeft(2, '0')}-'
          '${day.toString().padLeft(2, '0')}';
    }
    return '$value';
  }
}
