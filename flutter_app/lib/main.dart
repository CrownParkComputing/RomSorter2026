import 'package:flutter/material.dart';

import 'src/app_settings.dart';
import 'src/screens/home_screen.dart';
import 'src/screens/setup_wizard.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final settings = await AppSettings.load();
  runApp(RomSorterApp(settings: settings));
}

class RomSorterApp extends StatelessWidget {
  const RomSorterApp({super.key, required this.settings});

  final AppSettings settings;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'ROM Sorter',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFFE60012),
          brightness: Brightness.dark,
        ),
        useMaterial3: true,
      ),
      home: settings.setupComplete
          ? HomeScreen(settings: settings)
          : SetupWizard(settings: settings),
    );
  }
}
