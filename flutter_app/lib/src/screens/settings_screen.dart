import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';

import '../app_settings.dart';
import '../native/nscb.dart';
import '../op_runner.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key, required this.settings});

  final AppSettings settings;

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  late final TextEditingController _libraryCtrl =
      TextEditingController(text: widget.settings.libraryDir);
  late final TextEditingController _keysCtrl =
      TextEditingController(text: widget.settings.keysPath);

  @override
  void dispose() {
    _libraryCtrl.dispose();
    _keysCtrl.dispose();
    super.dispose();
  }

  void _toast(String message) {
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(message)));
  }

  Future<void> _save() async {
    widget.settings.libraryDir = _libraryCtrl.text.trim();
    widget.settings.keysPath = _keysCtrl.text.trim();
    await Directory(widget.settings.libraryDir).create(recursive: true);
    await widget.settings.save();
    if (mounted) setState(() {});
    _toast('Settings saved');
  }

  Future<void> _importKeys() async {
    // file_picker copies the picked document into the app sandbox; we then
    // persist it beside the library so it survives tmp cleanup.
    final result = await FilePicker.platform.pickFiles();
    final path = result?.files.single.path;
    if (path == null) return;
    final dest = '${widget.settings.libraryDir}/prod.keys';
    await File(path).copy(dest);
    _keysCtrl.text = dest;
    await _save();
  }

  Future<void> _refreshTitleDb() async {
    try {
      final result = await OpRunner.instance.run(
        'TitlesDB refresh',
        () => Nscb.refreshTitleDb(cacheDir: widget.settings.cacheDir),
      );
      _toast(result);
    } catch (e) {
      _toast('$e');
    }
  }

  @override
  Widget build(BuildContext context) {
    final keysOk = widget.settings.keysPresent;
    return Scaffold(
      appBar: AppBar(title: const Text('Settings')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          TextField(
            controller: _libraryCtrl,
            decoration: const InputDecoration(
              labelText: 'Library folder',
              helperText:
                  'On iOS this defaults to the app Documents folder — visible '
                  'in the Files app, where you drop your ROM files.',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 16),
          TextField(
            controller: _keysCtrl,
            decoration: InputDecoration(
              labelText: 'prod.keys path',
              border: const OutlineInputBorder(),
              suffixIcon: Icon(
                keysOk ? Icons.check_circle : Icons.error_outline,
                color: keysOk ? Colors.green : Colors.orange,
              ),
              helperText: keysOk
                  ? 'Keys found'
                  : 'Keys missing — import below or copy via the Files app',
            ),
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              FilledButton.icon(
                onPressed: _save,
                icon: const Icon(Icons.save_outlined),
                label: const Text('Save'),
              ),
              const SizedBox(width: 12),
              OutlinedButton.icon(
                onPressed: _importKeys,
                icon: const Icon(Icons.key),
                label: const Text('Import prod.keys'),
              ),
            ],
          ),
          const Divider(height: 32),
          ValueListenableBuilder<bool>(
            valueListenable: OpRunner.instance.busy,
            builder: (context, busy, _) => ListTile(
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.cloud_sync_outlined),
              title: const Text('Refresh TitlesDB'),
              subtitle: const Text(
                  'Download the latest title metadata and version index'),
              onTap: busy ? null : _refreshTitleDb,
            ),
          ),
          const SizedBox(height: 8),
          Text(
            'Cache: ${widget.settings.cacheDir}\n'
            'Temp: ${widget.settings.tempRoot}',
            style: Theme.of(context).textTheme.bodySmall,
          ),
        ],
      ),
    );
  }
}
