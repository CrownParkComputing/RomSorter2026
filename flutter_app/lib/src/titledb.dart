// Read-side access to the TitlesDB cache the Rust core maintains
// (<cacheDir>/nutdb.index.json + nutdb.versions.index.json). Parsing happens
// on a background isolate; entries are grouped by base title so the Database
// tab shows main titles with their latest version and linked DLC, not a flat
// dump of every DLC row.

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

/// A base title plus everything linked to it.
class DbGroup {
  DbGroup({
    required this.baseId,
    required this.name,
    this.publisher,
    this.iconUrl,
    this.releaseDate,
    this.latestVersion,
    this.dlc = const [],
  });

  final String baseId;
  final String name;
  final String? publisher;
  final String? iconUrl;
  final int? releaseDate;
  final int? latestVersion;
  final List<DbTitle> dlc;
}

/// Mirrors base_title_id() in src/nutdb.rs: base ends in 000; updates end in
/// 800 (zero the last three digits); DLC decrements the 13th hex digit and
/// zeroes the last three.
String baseTitleId(String titleId) {
  final id = titleId.trim().toUpperCase();
  if (id.endsWith('000')) return id;
  if (id.length != 16) return id;
  if (id.endsWith('800')) return '${id.substring(0, 13)}000';
  final nibble = int.tryParse(id[12], radix: 16);
  if (nibble == null) return id;
  final adjusted = (nibble - 1).clamp(0, 15).toRadixString(16).toUpperCase();
  return '${id.substring(0, 12)}${adjusted}000';
}

class TitleDb {
  TitleDb._();

  static String indexPath(String cacheDir) => '$cacheDir/nutdb.index.json';
  static String versionsPath(String cacheDir) =>
      '$cacheDir/nutdb.versions.index.json';

  static bool exists(String cacheDir) => File(indexPath(cacheDir)).existsSync();

  /// Loads base-title groups, sorted by name.
  static Future<List<DbGroup>> loadGroups(String cacheDir) {
    final path = indexPath(cacheDir);
    final vPath = versionsPath(cacheDir);
    return Isolate.run(() {
      final file = File(path);
      if (!file.existsSync()) return <DbGroup>[];
      final decoded =
          jsonDecode(file.readAsStringSync()) as Map<String, dynamic>;
      final titles = decoded['titles'] as Map<String, dynamic>? ?? const {};

      Map<String, dynamic> versions = const {};
      final vFile = File(vPath);
      if (vFile.existsSync()) {
        try {
          final vDecoded =
              jsonDecode(vFile.readAsStringSync()) as Map<String, dynamic>;
          versions = vDecoded['titles'] as Map<String, dynamic>? ?? const {};
        } catch (_) {}
      }

      DbTitle parse(String id, Map<String, dynamic> t) => DbTitle(
            id: id,
            name: (t['name'] as String?) ?? '',
            publisher: t['publisher'] as String?,
            iconUrl: (t['iconUrl'] ?? t['bannerUrl']) as String?,
            releaseDate: (t['releaseDate'] as num?)?.toInt(),
          );

      final bases = <String, DbTitle>{};
      final dlcByBase = <String, List<DbTitle>>{};
      titles.forEach((rawId, raw) {
        final id = rawId.toUpperCase();
        final t = parse(id, raw as Map<String, dynamic>);
        final base = baseTitleId(id);
        if (id == base) {
          bases[base] = t;
        } else if (!id.endsWith('800')) {
          (dlcByBase[base] ??= []).add(t);
        }
        // Update rows (…800) carry no info the versions index doesn't.
      });

      int? latestFor(String baseId) {
        final list = versions[baseId] as List?;
        if (list == null || list.isEmpty) return null;
        return (list.last as num).toInt();
      }

      final groups = <DbGroup>[];
      final claimed = <String>{};
      bases.forEach((baseId, base) {
        final dlc = List<DbTitle>.of(dlcByBase[baseId] ?? const [])
          ..sort((a, b) => a.name.toLowerCase().compareTo(b.name.toLowerCase()));
        claimed.add(baseId);
        if (base.name.isEmpty) return;
        groups.add(DbGroup(
          baseId: baseId,
          name: base.name,
          publisher: base.publisher,
          iconUrl: base.iconUrl,
          releaseDate: base.releaseDate,
          latestVersion: latestFor(baseId),
          dlc: dlc,
        ));
      });
      // DLC whose base game isn't in the index still deserves a row.
      dlcByBase.forEach((baseId, dlc) {
        if (claimed.contains(baseId) || dlc.isEmpty) return;
        dlc.sort(
            (a, b) => a.name.toLowerCase().compareTo(b.name.toLowerCase()));
        final first = dlc.first;
        groups.add(DbGroup(
          baseId: baseId,
          name: first.name,
          publisher: first.publisher,
          iconUrl: first.iconUrl,
          releaseDate: first.releaseDate,
          latestVersion: latestFor(baseId),
          dlc: dlc,
        ));
      });
      groups
          .sort((a, b) => a.name.toLowerCase().compareTo(b.name.toLowerCase()));
      return groups;
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
      return (titles[id] ?? titles[id.toLowerCase()]) as Map<String, dynamic>?;
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
