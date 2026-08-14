import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';

import '../app_settings.dart';
import '../native/nscb.dart';
import '../op_runner.dart';
import 'home_screen.dart';

/// First-run wizard: confirm the library/import folders, get prod.keys in
/// place, then kick off the initial TitlesDB download in the background and
/// land on the home screen.
class SetupWizard extends StatefulWidget {
  const SetupWizard({super.key, required this.settings});

  final AppSettings settings;

  @override
  State<SetupWizard> createState() => _SetupWizardState();
}

class _SetupWizardState extends State<SetupWizard> {
  int _step = 0;
  late final TextEditingController _libraryCtrl =
      TextEditingController(text: widget.settings.libraryDir);
  late final TextEditingController _importCtrl =
      TextEditingController(text: widget.settings.importDir);
  String? _keysError;

  @override
  void dispose() {
    _libraryCtrl.dispose();
    _importCtrl.dispose();
    super.dispose();
  }

  bool get _keysReady => widget.settings.keysPresent;

  Future<void> _savePaths() async {
    widget.settings.libraryDir = _libraryCtrl.text.trim();
    widget.settings.importDir = _importCtrl.text.trim();
    await widget.settings.ensureDirs();
    await widget.settings.save();
  }

  Future<void> _importKeysViaPicker() async {
    setState(() => _keysError = null);
    try {
      final result = await FilePicker.platform.pickFiles();
      final path = result?.files.single.path;
      if (path == null) return;
      final dest = '${widget.settings.libraryDir}/prod.keys';
      await File(path).copy(dest);
      widget.settings.keysPath = dest;
      await widget.settings.save();
    } catch (e) {
      _keysError = '$e';
    }
    if (mounted) setState(() {});
  }

  void _detectDroppedKeys() {
    final found = widget.settings.findDroppedKeys();
    setState(() {
      if (found != null) {
        widget.settings.keysPath = found;
        widget.settings.save();
        _keysError = null;
      } else {
        _keysError = 'No prod.keys found yet — copy it into the library '
            'folder and tap "Check again".';
      }
    });
  }

  Future<void> _finish() async {
    widget.settings.setupComplete = true;
    await widget.settings.save();
    if (!mounted) return;
    Navigator.of(context).pushReplacement(
      MaterialPageRoute(builder: (_) => HomeScreen(settings: widget.settings)),
    );
    // Initial TitlesDB download continues in the background; progress is
    // visible in the Logs tab and completion in a snackbar-free, quiet way.
    OpRunner.instance.appendLog('Starting initial TitlesDB download...');
    OpRunner.instance
        .run('TitlesDB download',
            () => Nscb.refreshTitleDb(cacheDir: widget.settings.cacheDir))
        .then(OpRunner.instance.appendLog)
        .catchError((Object e) => OpRunner.instance.appendLog('$e'));
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(title: const Text('Welcome to ROM Sorter')),
      body: Stepper(
        currentStep: _step,
        onStepContinue: () async {
          if (_step == 0) {
            await _savePaths();
            setState(() => _step = 1);
          } else if (_step == 1) {
            _detectDroppedKeys();
            if (_keysReady) setState(() => _step = 2);
          } else {
            await _finish();
          }
        },
        onStepCancel:
            _step == 0 ? null : () => setState(() => _step = _step - 1),
        controlsBuilder: (context, details) => Padding(
          padding: const EdgeInsets.only(top: 16),
          child: Row(
            children: [
              FilledButton(
                onPressed: details.onStepContinue,
                child: Text(_step == 2 ? 'Finish' : 'Continue'),
              ),
              const SizedBox(width: 12),
              if (_step > 0)
                TextButton(
                  onPressed: details.onStepCancel,
                  child: const Text('Back'),
                ),
              if (_step == 1 && !_keysReady) ...[
                const Spacer(),
                TextButton(
                  onPressed: () => setState(() => _step = 2),
                  child: const Text('Skip for now'),
                ),
              ],
            ],
          ),
        ),
        steps: [
          Step(
            title: const Text('Folders'),
            isActive: _step >= 0,
            content: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  Platform.isIOS
                      ? 'Both folders live inside this app\'s storage and are '
                          'visible in the Files app under "ROM Sorter".'
                      : 'Both folders live under the app\'s external storage '
                          'and are reachable with a file manager or adb.',
                  style: theme.textTheme.bodySmall,
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: _libraryCtrl,
                  decoration: const InputDecoration(
                    labelText: 'Switch library folder',
                    helperText: 'Your organized collection lives here',
                    border: OutlineInputBorder(),
                  ),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: _importCtrl,
                  decoration: const InputDecoration(
                    labelText: 'Import folder',
                    helperText:
                        'Drop new files here, then use Tools → Import',
                    border: OutlineInputBorder(),
                  ),
                ),
              ],
            ),
          ),
          Step(
            title: const Text('prod.keys'),
            isActive: _step >= 1,
            content: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Icon(
                      _keysReady ? Icons.check_circle : Icons.key,
                      color: _keysReady ? Colors.green : null,
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(_keysReady
                          ? 'Keys found: ${widget.settings.keysPath}'
                          : 'Console keys are required to read NSP/XCI '
                              'metadata. Dump them from your own console '
                              '(Lockpick_RCM).'),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                Wrap(
                  spacing: 12,
                  children: [
                    OutlinedButton.icon(
                      onPressed: _importKeysViaPicker,
                      icon: const Icon(Icons.file_open_outlined),
                      label: const Text('Pick prod.keys file'),
                    ),
                    OutlinedButton.icon(
                      onPressed: _detectDroppedKeys,
                      icon: const Icon(Icons.refresh),
                      label: const Text('Check again'),
                    ),
                  ],
                ),
                if (_keysError != null) ...[
                  const SizedBox(height: 8),
                  Text(_keysError!,
                      style: TextStyle(color: theme.colorScheme.error)),
                ],
              ],
            ),
          ),
          Step(
            title: const Text('Title database'),
            isActive: _step >= 2,
            content: const Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('ROM Sorter uses a community TitlesDB for names, '
                    'artwork and latest-version checks.'),
                SizedBox(height: 8),
                Text('The initial download starts in the background when you '
                    'finish — watch the Logs tab. You can use the app '
                    'immediately; metadata fills in once it completes.'),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
