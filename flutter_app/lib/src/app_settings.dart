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
    required this.keysPath,
    required this.cacheDir,
    required this.tempRoot,
  });

  String libraryDir;
  String keysPath;
  String cacheDir;
  String tempRoot;

  static const _kLibraryDir = 'libraryDir';
  static const _kKeysPath = 'keysPath';

  static Future<AppSettings> load() async {
    final prefs = await SharedPreferences.getInstance();
    final docs = await getApplicationDocumentsDirectory();
    final support = await getApplicationSupportDirectory();
    final temp = await getTemporaryDirectory();

    final settings = AppSettings._(
      libraryDir: prefs.getString(_kLibraryDir) ?? docs.path,
      keysPath: prefs.getString(_kKeysPath) ?? '${docs.path}/prod.keys',
      cacheDir: '${support.path}/titledb',
      tempRoot: '${temp.path}/nscb',
    );

    await Directory(settings.libraryDir).create(recursive: true);
    await Directory(settings.cacheDir).create(recursive: true);
    // Large intermediate files must land on the app's own volume, not /tmp.
    Nscb.configureTempRoot(settings.tempRoot);
    return settings;
  }

  Future<void> save() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_kLibraryDir, libraryDir);
    await prefs.setString(_kKeysPath, keysPath);
  }

  bool get keysPresent => File(keysPath).existsSync();
}
