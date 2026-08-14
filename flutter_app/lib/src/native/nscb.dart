// High-level async API over the nscb native core.
//
// Long-running operations execute on a background isolate via Isolate.run so
// the UI stays responsive; the bindings re-open the library lazily inside each
// isolate. While an operation runs, poll [Nscb.logs] for progress lines.

import 'dart:convert';
import 'dart:isolate';

import 'nscb_bindings.dart';

class NscbException implements Exception {
  NscbException(this.message);
  final String message;

  @override
  String toString() => message;
}

/// Turns the bridge's "ERROR: ..." convention into a thrown exception.
String _check(String result) {
  if (result.startsWith('ERROR:')) {
    throw NscbException(result.substring('ERROR:'.length).trim());
  }
  return result;
}

class ScanFile {
  ScanFile.fromJson(Map<String, dynamic> json)
      : path = json['path'] as String,
        filename = json['filename'] as String,
        titleId = json['title_id'] as String,
        version = json['version'] as int,
        kind = json['kind'].toString();

  final String path;
  final String filename;
  final String titleId;
  final int version;
  final String kind;
}

class ScanGroup {
  ScanGroup.fromJson(Map<String, dynamic> json)
      : baseId = json['base_id'] as String,
        titleName = json['title_name'] as String,
        latestVersionDb = json['latest_version_db'] as int,
        items = (json['items'] as List)
            .map((e) => ScanFile.fromJson(e as Map<String, dynamic>))
            .toList();

  final String baseId;
  final String titleName;
  final int latestVersionDb;
  final List<ScanFile> items;
}

class LibraryTitleStatus {
  LibraryTitleStatus.fromJson(Map<String, dynamic> json)
      : titleId = json['title_id'] as String,
        titleName = json['title_name'] as String,
        localVersion = json['local_version'] as int,
        latestVersion = json['latest_version'] as int?,
        releaseDate = json['release_date'] as String?,
        publisher = json['publisher'] as String?,
        languages =
            (json['languages'] as List?)?.map((e) => e.toString()).toList() ??
                const [],
        description = json['description'] as String?,
        imageUrl = json['image_url'] as String?,
        screenshotUrls = (json['screenshot_urls'] as List?)
                ?.map((e) => e.toString())
                .toList() ??
            const [],
        status = json['status'] as String;

  final String titleId;
  final String titleName;
  final int localVersion;
  final int? latestVersion;
  final String? releaseDate;
  final String? publisher;
  final List<String> languages;
  final String? description;
  final String? imageUrl;
  final List<String> screenshotUrls;
  final String status;
}

class FaultyFile {
  FaultyFile.fromJson(Map<String, dynamic> json)
      : path = json['path'] as String,
        filename = json['filename'] as String,
        reason = json['reason'] as String;

  final String path;
  final String filename;
  final String reason;
}

class Nscb {
  Nscb._();

  static NscbBindings get _b => NscbBindings.instance;

  /// Fast; safe to call on the UI isolate.
  static String logs() {
    return _b.call((p) => _b.getLogs(), const []);
  }

  static String configureTempRoot(String root) {
    return _check(_b.call((p) => _b.configureTempRoot(p[0]), [root]));
  }

  static Future<String> merge({
    required List<String> inputs,
    required String outputPath,
    required String keysPath,
    required String outputType,
  }) {
    final joined = inputs.join('\n');
    return Isolate.run(() => _check(_b.call(
        (p) => _b.merge(p[0], p[1], p[2], p[3]),
        [joined, outputPath, keysPath, outputType])));
  }

  static Future<String> compress({
    required String inputPath,
    required String outputPath,
    required String keysPath,
    int level = 3,
  }) {
    return Isolate.run(() => _check(_b.call(
        (p) => _b.compress(p[0], p[1], p[2], level),
        [inputPath, outputPath, keysPath])));
  }

  static Future<String> decompress({
    required String inputPath,
    required String outputPath,
  }) {
    return Isolate.run(() => _check(_b.call(
        (p) => _b.decompress(p[0], p[1]), [inputPath, outputPath])));
  }

  static Future<String> renamePath({
    required String path,
    required String keysPath,
    required String cacheDir,
    // Defaults match the Android app's bulk-rename call.
    String renMode = 'skip_corr_tid',
    String addLangue = 'true',
    String noVersion = 'false',
    String dlcRname = 'tag',
  }) {
    return Isolate.run(() => _check(_b.call(
        (p) => _b.renamePath(p[0], p[1], p[2], p[3], p[4], p[5], p[6]),
        [path, keysPath, cacheDir, renMode, addLangue, noVersion, dlcRname])));
  }

  static Future<List<ScanGroup>> scanDirectory({
    required String path,
    required String keysPath,
    required String cacheDir,
  }) async {
    final json = await Isolate.run(() => _check(_b.call(
        (p) => _b.scanDirectory(p[0], p[1], p[2]),
        [path, keysPath, cacheDir])));
    return (jsonDecode(json) as List)
        .map((e) => ScanGroup.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  static Future<List<FaultyFile>> scanFaultyFiles({
    required String path,
    required String keysPath,
  }) async {
    final json = await Isolate.run(() => _check(
        _b.call((p) => _b.scanFaultyFiles(p[0], p[1]), [path, keysPath])));
    return (jsonDecode(json) as List)
        .map((e) => FaultyFile.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  static Future<String> refreshTitleDb({required String cacheDir}) {
    return Isolate.run(
        () => _check(_b.call((p) => _b.refreshTitleDb(p[0]), [cacheDir])));
  }

  static Future<List<LibraryTitleStatus>> libraryStatus({
    required String inputPath,
    required String keysPath,
    required String cacheDir,
  }) async {
    final json = await Isolate.run(() => _check(_b.call(
        (p) => _b.libraryStatus(p[0], p[1], p[2]),
        [inputPath, keysPath, cacheDir])));
    final decoded = jsonDecode(json) as Map<String, dynamic>;
    return (decoded['titles'] as List)
        .map((e) => LibraryTitleStatus.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  static Future<String> deleteFile(String path) {
    return Isolate.run(
        () => _check(_b.call((p) => _b.deleteFile(p[0]), [path])));
  }

  static Future<String> contentList({
    required String inputPath,
    required String keysPath,
  }) {
    return Isolate.run(() => _check(
        _b.call((p) => _b.contentList(p[0], p[1]), [inputPath, keysPath])));
  }

  static Future<String> fileList({
    required String inputPath,
    required String keysPath,
  }) {
    return Isolate.run(() => _check(
        _b.call((p) => _b.fileList(p[0], p[1]), [inputPath, keysPath])));
  }

  static Future<String> suggestedFileName({
    required List<String> inputs,
    required String keysPath,
    required String cacheDir,
    required String outputType,
  }) {
    final joined = inputs.join('\n');
    return Isolate.run(() => _check(_b.call(
        (p) => _b.suggestedFileName(p[0], p[1], p[2], p[3]),
        [joined, keysPath, cacheDir, outputType])));
  }
}
