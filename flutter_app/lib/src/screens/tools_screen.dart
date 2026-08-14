import 'dart:io';

import 'package:flutter/material.dart';

import '../app_settings.dart';
import '../native/nscb.dart';
import '../op_runner.dart';

class ToolsScreen extends StatefulWidget {
  const ToolsScreen({super.key, required this.settings});

  final AppSettings settings;

  @override
  State<ToolsScreen> createState() => _ToolsScreenState();
}

class _ToolsScreenState extends State<ToolsScreen> {
  List<String> _listRomFiles({String? root}) {
    final dir = Directory(root ?? widget.settings.libraryDir);
    if (!dir.existsSync()) return const [];
    const exts = {'.nsp', '.nsz', '.xci', '.xcz'};
    return dir
        .listSync(recursive: true)
        .whereType<File>()
        .where((f) {
          final name = f.path.toLowerCase();
          return exts.any(name.endsWith);
        })
        .map((f) => f.path)
        .toList()
      ..sort();
  }

  void _toast(String message) {
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(message)));
  }

  Future<void> _guarded(String name, Future<String> Function() op) async {
    if (!widget.settings.keysPresent) {
      _toast('prod.keys not found — set it up in Settings first');
      return;
    }
    try {
      final result = await OpRunner.instance.run(name, op);
      _toast(result);
    } catch (e) {
      _toast('$e');
    }
  }

  Future<List<String>?> _pickFiles({required bool multi, String? title}) async {
    final files = _listRomFiles();
    if (files.isEmpty) {
      _toast('No ROM files in the library folder');
      return null;
    }
    final selected = <String>{};
    final result = await showDialog<List<String>>(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, setDialogState) => AlertDialog(
          title: Text(title ?? (multi ? 'Select files' : 'Select file')),
          content: SizedBox(
            width: double.maxFinite,
            child: ListView(
              shrinkWrap: true,
              children: [
                for (final f in files)
                  multi
                      ? CheckboxListTile(
                          dense: true,
                          value: selected.contains(f),
                          title: Text(f.split('/').last),
                          onChanged: (v) => setDialogState(() =>
                              v == true ? selected.add(f) : selected.remove(f)),
                        )
                      : ListTile(
                          dense: true,
                          title: Text(f.split('/').last),
                          onTap: () => Navigator.pop(context, [f]),
                        ),
              ],
            ),
          ),
          actions: [
            TextButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('Cancel')),
            if (multi)
              FilledButton(
                onPressed: selected.isEmpty
                    ? null
                    : () => Navigator.pop(context, selected.toList()),
                child: const Text('OK'),
              ),
          ],
        ),
      ),
    );
    return result;
  }

  Future<void> _import() async {
    final files = _listRomFiles(root: widget.settings.importDir);
    if (files.isEmpty) {
      _toast('No ROM files in ${widget.settings.importDir}');
      return;
    }
    try {
      final moved = await OpRunner.instance.run('Import', () async {
        var count = 0;
        for (final path in files) {
          final dest =
              '${widget.settings.libraryDir}/${path.split('/').last}';
          final src = File(path);
          try {
            await src.rename(dest);
          } on FileSystemException {
            // Cross-device move: copy then delete.
            await src.copy(dest);
            await src.delete();
          }
          count++;
        }
        return count;
      });
      _toast('Imported $moved file(s) into the library');
    } catch (e) {
      _toast('$e');
    }
  }

  Future<void> _bulkRename() => _guarded(
        'Bulk rename',
        () => Nscb.renamePath(
          path: widget.settings.libraryDir,
          keysPath: widget.settings.keysPath,
          cacheDir: widget.settings.cacheDir,
        ),
      );

  Future<void> _faultyScan() async {
    if (!widget.settings.keysPresent) {
      _toast('prod.keys not found — set it up in Settings first');
      return;
    }
    try {
      final faulty = await OpRunner.instance.run(
        'Faulty-file scan',
        () => Nscb.scanFaultyFiles(
          path: widget.settings.libraryDir,
          keysPath: widget.settings.keysPath,
        ),
      );
      if (!mounted) return;
      if (faulty.isEmpty) {
        _toast('No faulty files found');
        return;
      }
      showModalBottomSheet<void>(
        context: context,
        showDragHandle: true,
        builder: (context) => ListView(
          padding: const EdgeInsets.all(16),
          children: [
            Text('${faulty.length} faulty file(s)',
                style: Theme.of(context).textTheme.titleMedium),
            for (final f in faulty)
              ListTile(
                dense: true,
                contentPadding: EdgeInsets.zero,
                title: Text(f.filename),
                subtitle: Text(f.reason),
              ),
          ],
        ),
      );
    } catch (e) {
      _toast('$e');
    }
  }

  Future<void> _merge() async {
    final inputs =
        await _pickFiles(multi: true, title: 'Select files to merge');
    if (inputs == null || inputs.isEmpty) return;
    final name = await OpRunner.instance.run(
      'Suggesting name',
      () => Nscb.suggestedFileName(
        inputs: inputs,
        keysPath: widget.settings.keysPath,
        cacheDir: widget.settings.cacheDir,
        outputType: 'nsp',
      ),
    ).catchError((_) => 'merged.nsp');
    await _guarded(
      'Merge',
      () => Nscb.merge(
        inputs: inputs,
        outputPath: '${widget.settings.libraryDir}/$name',
        keysPath: widget.settings.keysPath,
        outputType: 'nsp',
      ),
    );
  }

  Future<void> _compress() async {
    final picked = await _pickFiles(multi: false, title: 'Compress which file?');
    if (picked == null) return;
    final input = picked.first;
    final output = input
        .replaceAll(RegExp(r'\.nsp$', caseSensitive: false), '.nsz')
        .replaceAll(RegExp(r'\.xci$', caseSensitive: false), '.xcz');
    if (output == input) {
      _toast('Only NSP/XCI files can be compressed');
      return;
    }
    await _guarded(
      'Compress',
      () => Nscb.compress(
        inputPath: input,
        outputPath: output,
        keysPath: widget.settings.keysPath,
      ),
    );
  }

  Future<void> _decompress() async {
    final picked =
        await _pickFiles(multi: false, title: 'Decompress which file?');
    if (picked == null) return;
    final input = picked.first;
    final output = input
        .replaceAll(RegExp(r'\.nsz$', caseSensitive: false), '.nsp')
        .replaceAll(RegExp(r'\.xcz$', caseSensitive: false), '.xci');
    if (output == input) {
      _toast('Only NSZ/XCZ files can be decompressed');
      return;
    }
    try {
      final result = await OpRunner.instance.run(
          'Decompress', () => Nscb.decompress(inputPath: input, outputPath: output));
      _toast(result);
    } catch (e) {
      _toast('$e');
    }
  }

  Future<void> _contentList() async {
    final picked = await _pickFiles(multi: false, title: 'Inspect which file?');
    if (picked == null) return;
    try {
      final text = await OpRunner.instance.run(
        'Content list',
        () => Nscb.contentList(
            inputPath: picked.first, keysPath: widget.settings.keysPath),
      );
      if (!mounted) return;
      showModalBottomSheet<void>(
        context: context,
        showDragHandle: true,
        isScrollControlled: true,
        builder: (context) => Padding(
          padding: const EdgeInsets.all(16),
          child: SingleChildScrollView(
            child: Text(text,
                style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
          ),
        ),
      );
    } catch (e) {
      _toast('$e');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Tools')),
      body: ValueListenableBuilder<bool>(
        valueListenable: OpRunner.instance.busy,
        builder: (context, busy, _) => ListView(
          children: [
            if (busy)
              ValueListenableBuilder<String>(
                valueListenable: OpRunner.instance.currentOp,
                builder: (context, op, _) => ListTile(
                  leading: const SizedBox(
                      width: 24,
                      height: 24,
                      child: CircularProgressIndicator(strokeWidth: 2)),
                  title: Text('Running: $op'),
                  subtitle: const Text('Watch the Logs tab for progress'),
                ),
              ),
            _ToolTile(
              icon: Icons.download_outlined,
              title: 'Import new files',
              subtitle: 'Move everything from the import folder into the library',
              onTap: busy ? null : _import,
            ),
            _ToolTile(
              icon: Icons.drive_file_rename_outline,
              title: 'Bulk rename',
              subtitle: 'Rename all library files from package metadata + TitlesDB',
              onTap: busy ? null : _bulkRename,
            ),
            _ToolTile(
              icon: Icons.merge_type,
              title: 'Merge to NSP',
              subtitle: 'Combine base + update + DLC into one file',
              onTap: busy ? null : _merge,
            ),
            _ToolTile(
              icon: Icons.compress,
              title: 'Compress (NSP→NSZ / XCI→XCZ)',
              subtitle: 'zstd-compress a container',
              onTap: busy ? null : _compress,
            ),
            _ToolTile(
              icon: Icons.unarchive_outlined,
              title: 'Decompress (NSZ→NSP / XCZ→XCI)',
              subtitle: 'Restore a compressed container',
              onTap: busy ? null : _decompress,
            ),
            _ToolTile(
              icon: Icons.fact_check_outlined,
              title: 'Verify library',
              subtitle: 'Scan for corrupt or truncated files',
              onTap: busy ? null : _faultyScan,
            ),
            _ToolTile(
              icon: Icons.list_alt,
              title: 'Content viewer',
              subtitle: 'Show the contents of a single file',
              onTap: busy ? null : _contentList,
            ),
          ],
        ),
      ),
    );
  }
}

class _ToolTile extends StatelessWidget {
  const _ToolTile({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.onTap,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return ListTile(
      leading: Icon(icon),
      title: Text(title),
      subtitle: Text(subtitle),
      onTap: onTap,
      enabled: onTap != null,
    );
  }
}
