import 'package:flutter/material.dart';

import '../app_settings.dart';
import '../native/nscb.dart';
import '../op_runner.dart';

class LibraryScreen extends StatefulWidget {
  const LibraryScreen({super.key, required this.settings});

  final AppSettings settings;

  @override
  State<LibraryScreen> createState() => _LibraryScreenState();
}

class _LibraryScreenState extends State<LibraryScreen> {
  List<ScanGroup>? _groups;
  String? _error;

  Future<void> _scan() async {
    if (!widget.settings.keysPresent) {
      setState(() => _error =
          'prod.keys not found at ${widget.settings.keysPath}. '
          'Copy it there via the Files app, or set the path in Settings.');
      return;
    }
    setState(() => _error = null);
    try {
      final groups = await OpRunner.instance.run(
        'Scanning library',
        () => Nscb.scanDirectory(
          path: widget.settings.libraryDir,
          keysPath: widget.settings.keysPath,
          cacheDir: widget.settings.cacheDir,
        ),
      );
      if (mounted) setState(() => _groups = groups);
    } catch (e) {
      if (mounted) setState(() => _error = '$e');
    }
  }

  Future<void> _showDetails(ScanGroup group) async {
    final file = group.items.first;
    List<LibraryTitleStatus> statuses = const [];
    try {
      statuses = await OpRunner.instance.run(
        'Loading title info',
        () => Nscb.libraryStatus(
          inputPath: file.path,
          keysPath: widget.settings.keysPath,
          cacheDir: widget.settings.cacheDir,
        ),
      );
    } catch (_) {}
    if (!mounted) return;
    final meta = statuses.isNotEmpty ? statuses.first : null;
    showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      isScrollControlled: true,
      builder: (context) => DraggableScrollableSheet(
        expand: false,
        initialChildSize: 0.6,
        builder: (context, controller) => ListView(
          controller: controller,
          padding: const EdgeInsets.all(16),
          children: [
            Text(group.titleName,
                style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 8),
            if (meta?.imageUrl != null)
              ClipRRect(
                borderRadius: BorderRadius.circular(12),
                child: Image.network(meta!.imageUrl!,
                    height: 180,
                    fit: BoxFit.cover,
                    errorBuilder: (_, _, _) => const SizedBox.shrink()),
              ),
            const SizedBox(height: 8),
            if (meta != null) ...[
              if (meta.publisher != null) Text('Publisher: ${meta.publisher}'),
              if (meta.releaseDate != null) Text('Released: ${meta.releaseDate}'),
              Text('Status: ${meta.status}'
                  ' (local v${meta.localVersion}'
                  '${meta.latestVersion != null ? ', latest v${meta.latestVersion}' : ''})'),
              if (meta.description != null) ...[
                const SizedBox(height: 8),
                Text(meta.description!,
                    style: Theme.of(context).textTheme.bodySmall),
              ],
              const Divider(height: 24),
            ],
            Text('Files', style: Theme.of(context).textTheme.titleMedium),
            for (final item in group.items)
              ListTile(
                dense: true,
                contentPadding: EdgeInsets.zero,
                title: Text(item.filename),
                subtitle: Text(
                    '${item.kind} · v${item.version} · ${item.titleId}'),
                trailing: IconButton(
                  icon: const Icon(Icons.delete_outline),
                  onPressed: () => _confirmDelete(item),
                ),
              ),
          ],
        ),
      ),
    );
  }

  Future<void> _confirmDelete(ScanFile item) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Delete file?'),
        content: Text(item.filename),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Cancel')),
          FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('Delete')),
        ],
      ),
    );
    if (ok != true) return;
    try {
      await Nscb.deleteFile(item.path);
      if (mounted) Navigator.pop(context);
      await _scan();
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('$e')));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final groups = _groups;
    return Scaffold(
      appBar: AppBar(
        title: const Text('Library'),
        actions: [
          ValueListenableBuilder<bool>(
            valueListenable: OpRunner.instance.busy,
            builder: (context, busy, _) => IconButton(
              icon: const Icon(Icons.refresh),
              onPressed: busy ? null : _scan,
            ),
          ),
        ],
      ),
      body: _error != null
          ? Padding(
              padding: const EdgeInsets.all(24),
              child: Center(child: Text(_error!, textAlign: TextAlign.center)),
            )
          : groups == null
              ? Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Text('No scan yet.'),
                      const SizedBox(height: 12),
                      FilledButton.icon(
                        onPressed: _scan,
                        icon: const Icon(Icons.search),
                        label: const Text('Scan library'),
                      ),
                    ],
                  ),
                )
              : groups.isEmpty
                  ? const Center(
                      child: Text(
                          'No NSP/NSZ/XCI/XCZ files found in the library folder.'))
                  : RefreshIndicator(
                      onRefresh: _scan,
                      child: ListView.builder(
                        itemCount: groups.length,
                        itemBuilder: (context, i) {
                          final g = groups[i];
                          final newest = g.items
                              .map((e) => e.version)
                              .fold<int>(0, (a, b) => a > b ? a : b);
                          final outdated = g.latestVersionDb > newest;
                          return ListTile(
                            title: Text(g.titleName),
                            subtitle: Text(
                                '${g.items.length} file(s) · ${g.baseId}'),
                            trailing: outdated
                                ? const Chip(label: Text('update'))
                                : null,
                            onTap: () => _showDetails(g),
                          );
                        },
                      ),
                    ),
    );
  }
}
