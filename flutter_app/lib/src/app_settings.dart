import 'dart:io';

import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'native/nscb.dart';

/// Paths the app operates on.
///
/// iOS model: everything lives under the app's Documents directory, which is
/// exposed to the user in the Files app (UIFileSharingEnabled +
/// LSSupportsOpeningDocumentsInPlace). The user drops ROMs and prod.keys
/// there; outputs are written next to them.
class AppSettings {
  AppSettings._({
    required this.libraryDir,
    required this.importDir,
    required this.keysPath,
    required this.cacheDir,
    required this.tempRoot,
    required this.setupComplete,
  });

  String libraryDir;
  String importDir;
  String keysPath;
  String cacheDir;
  String tempRoot;
  bool setupComplete;

  static const _kLibraryDir = 'libraryDir';
  static const _kImportDir = 'importDir';
  static const _kKeysPath = 'keysPath';
  static const _kSetupComplete = 'setupComplete';

  static Future<AppSettings> load() async {
    final prefs = await SharedPreferences.getInstance();
    // On Android use the app's external-storage folder
    // (/sdcard/Android/data/<pkg>/files): reachable by adb push and file
    // managers with no runtime permissions. iOS keeps Documents (Files app).
    final docs = Platform.isAndroid
        ? (await getExternalStorageDirectory() ??
            await getApplicationDocumentsDirectory())
        : await getApplicationDocumentsDirectory();
    final support = await getApplicationSupportDirectory();
    final temp = await getTemporaryDirectory();

    final settings = AppSettings._(
      libraryDir: prefs.getString(_kLibraryDir) ?? '${docs.path}/library',
      importDir: prefs.getString(_kImportDir) ?? '${docs.path}/import',
      keysPath: prefs.getString(_kKeysPath) ?? '${docs.path}/prod.keys',
      cacheDir: '${support.path}/titledb',
      tempRoot: '${temp.path}/nscb',
      setupComplete: prefs.getBool(_kSetupComplete) ?? false,
    );

    await settings.ensureDirs();
    // Large intermediate files must land on the app's own volume, not /tmp.
    Nscb.configureTempRoot(settings.tempRoot);
    return settings;
  }

  Future<void> ensureDirs() async {
    await Directory(libraryDir).create(recursive: true);
    await Directory(importDir).create(recursive: true);
    await Directory(cacheDir).create(recursive: true);
  }

  Future<void> save() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_kLibraryDir, libraryDir);
    await prefs.setString(_kImportDir, importDir);
    await prefs.setString(_kKeysPath, keysPath);
    await prefs.setBool(_kSetupComplete, setupComplete);
  }

  bool get keysPresent => File(keysPath).existsSync();

  /// Detects a prod.keys the user already dropped in a well-known spot.
  String? findDroppedKeys() {
    for (final candidate in [
      keysPath,
      '$libraryDir/prod.keys',
      '$importDir/prod.keys',
      '${Directory(libraryDir).parent.path}/prod.keys',
    ]) {
      if (File(candidate).existsSync()) return candidate;
    }
    return null;
  }
}
