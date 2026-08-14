import 'dart:async';

import 'package:flutter/material.dart';

import '../app_settings.dart';
import '../native/nscb.dart';
import '../op_runner.dart';
import 'database_screen.dart';
import 'library_screen.dart';
import 'logs_screen.dart';
import 'settings_screen.dart';
import 'tools_screen.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key, required this.settings});

  final AppSettings settings;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  int _index = 0;
  Timer? _logPoll;

  @override
  void initState() {
    super.initState();
    // Drain the native log buffer continuously so progress from background
    // isolate operations shows up in the Logs tab as it happens.
    _logPoll = Timer.periodic(const Duration(milliseconds: 800), (_) {
      final fresh = Nscb.logs();
      if (fresh.isNotEmpty) {
        OpRunner.instance.appendLog(fresh);
      }
    });
  }

  @override
  void dispose() {
    _logPoll?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final pages = [
      LibraryScreen(settings: widget.settings),
      DatabaseScreen(settings: widget.settings),
      ToolsScreen(settings: widget.settings),
      const LogsScreen(),
      SettingsScreen(settings: widget.settings),
    ];
    return Scaffold(
      body: SafeArea(child: IndexedStack(index: _index, children: pages)),
      bottomNavigationBar: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          ValueListenableBuilder<bool>(
            valueListenable: OpRunner.instance.busy,
            builder: (context, busy, _) => busy
                ? const LinearProgressIndicator(minHeight: 3)
                : const SizedBox(height: 3),
          ),
          NavigationBar(
            selectedIndex: _index,
            onDestinationSelected: (i) => setState(() => _index = i),
            destinations: const [
              NavigationDestination(
                  icon: Icon(Icons.video_library_outlined), label: 'Library'),
              NavigationDestination(
                  icon: Icon(Icons.storage_outlined), label: 'Database'),
              NavigationDestination(
                  icon: Icon(Icons.build_outlined), label: 'Tools'),
              NavigationDestination(
                  icon: Icon(Icons.article_outlined), label: 'Logs'),
              NavigationDestination(
                  icon: Icon(Icons.settings_outlined), label: 'Settings'),
            ],
          ),
        ],
      ),
    );
  }
}
