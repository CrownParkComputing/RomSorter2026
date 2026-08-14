import 'package:flutter/material.dart';

import '../app_settings.dart';
import '../native/nscb.dart';
import '../op_runner.dart';
import '../titledb.dart';

class DatabaseScreen extends StatefulWidget {
  const DatabaseScreen({super.key, required this.settings});

  final AppSettings settings;

  @override
  State<DatabaseScreen> createState() => _DatabaseScreenState();
}

class _DatabaseScreenState extends State<DatabaseScreen> {
  List<DbGroup>? _all;
  List<DbGroup> _filtered = const [];
  String _query = '';
  bool _loading = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final groups = await TitleDb.loadGroups(widget.settings.cacheDir);
      if (!mounted) return;
      setState(() {
        _all = groups;
        _applyFilter();
      });
    } catch (e) {
      if (mounted) setState(() => _error = '$e');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _download() async {
    try {
      final result = await OpRunner.instance.run(
        'TitlesDB download',
        () => Nscb.refreshTitleDb(cacheDir: widget.settings.cacheDir),
      );
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(result)));
      }
      await _load();
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('$e')));
      }
    }
  }

  void _applyFilter() {
    final all = _all ?? const <DbGroup>[];
    final q = _query.trim().toLowerCase();
    _filtered = q.isEmpty
        ? all
        : all
            .where((g) =>
                g.name.toLowerCase().contains(q) ||
                g.baseId.toLowerCase().contains(q) ||
                (g.publisher?.toLowerCase().contains(q) ?? false) ||
                g.dlc.any((d) => d.name.toLowerCase().contains(q)))
            .toList();
  }

  Future<void> _showDetail(DbGroup group) async {
    final detail =
        await TitleDb.loadDetail(widget.settings.cacheDir, group.baseId);
    if (!mounted) return;
    final banner = (detail?['bannerUrl'] ?? detail?['iconUrl']) as String?;
    final description = detail?['description'] as String?;
    final screenshots = (detail?['screenshots'] as List?)
            ?.map((e) => e.toString())
            .toList() ??
        const <String>[];
    showModalBottomSheet<void>(
      context: context,
      showDragHandle: true,
      isScrollControlled: true,
      builder: (context) => DraggableScrollableSheet(
        expand: false,
        initialChildSize: 0.7,
        builder: (context, controller) => ListView(
          controller: controller,
          padding: const EdgeInsets.all(16),
          children: [
            Text(group.name, style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 4),
            Text(
              [
                group.baseId,
                if (group.publisher != null) group.publisher!,
                if (group.releaseDate != null)
                  TitleDb.formatReleaseDate(group.releaseDate!),
                if (group.latestVersion != null)
                  'latest v${group.latestVersion}',
              ].join(' · '),
              style: Theme.of(context).textTheme.bodySmall,
            ),
            const SizedBox(height: 12),
            if (banner != null)
              ClipRRect(
                borderRadius: BorderRadius.circular(12),
                child: Image.network(banner,
                    fit: BoxFit.cover,
                    errorBuilder: (_, _, _) => const SizedBox.shrink()),
              ),
            if (description != null) ...[
              const SizedBox(height: 12),
              Text(description, style: Theme.of(context).textTheme.bodyMedium),
            ],
            if (screenshots.isNotEmpty) ...[
              const SizedBox(height: 12),
              SizedBox(
                height: 120,
                child: ListView.separated(
                  scrollDirection: Axis.horizontal,
                  itemCount: screenshots.length,
                  separatorBuilder: (_, _) => const SizedBox(width: 8),
                  itemBuilder: (context, i) => ClipRRect(
                    borderRadius: BorderRadius.circular(8),
                    child: Image.network(screenshots[i],
                        errorBuilder: (_, _, _) => const SizedBox.shrink()),
                  ),
                ),
              ),
            ],
            if (group.dlc.isNotEmpty) ...[
              const SizedBox(height: 16),
              Text('DLC (${group.dlc.length})',
                  style: Theme.of(context).textTheme.titleMedium),
              for (final d in group.dlc)
                ListTile(
                  dense: true,
                  contentPadding: EdgeInsets.zero,
                  leading: const Icon(Icons.extension_outlined),
                  title: Text(d.name),
                  subtitle: Text(d.id),
                ),
            ],
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final all = _all;
    final hasDb = TitleDb.exists(widget.settings.cacheDir);
    return Scaffold(
      appBar: AppBar(
        title: Text(all == null || all.isEmpty
            ? 'Database'
            : 'Database (${all.length} titles)'),
        actions: [
          ValueListenableBuilder<bool>(
            valueListenable: OpRunner.instance.busy,
            builder: (context, busy, _) => IconButton(
              icon: const Icon(Icons.cloud_download_outlined),
              tooltip: 'Download / update TitlesDB',
              onPressed: busy ? null : _download,
            ),
          ),
        ],
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 8, 12, 4),
            child: TextField(
              decoration: const InputDecoration(
                prefixIcon: Icon(Icons.search),
                hintText: 'Search title, DLC, publisher or ID',
                border: OutlineInputBorder(),
                isDense: true,
              ),
              onChanged: (v) => setState(() {
                _query = v;
                _applyFilter();
              }),
            ),
          ),
          Expanded(
            child: _loading
                ? const Center(child: CircularProgressIndicator())
                : _error != null
                    ? Center(child: Text(_error!))
                    : (all == null || all.isEmpty)
                        ? Center(
                            child: Column(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Text(hasDb
                                    ? 'Database is empty.'
                                    : 'TitlesDB not downloaded yet.'),
                                const SizedBox(height: 12),
                                FilledButton.icon(
                                  onPressed: _download,
                                  icon: const Icon(
                                      Icons.cloud_download_outlined),
                                  label: const Text('Download now'),
                                ),
                              ],
                            ),
                          )
                        : ListView.builder(
                            itemCount: _filtered.length,
                            itemExtent: 64,
                            itemBuilder: (context, i) {
                              final g = _filtered[i];
                              final parts = [
                                if (g.publisher != null) g.publisher!,
                                if (g.latestVersion != null)
                                  'v${g.latestVersion}',
                                if (g.dlc.isNotEmpty)
                                  '${g.dlc.length} DLC',
                              ];
                              return ListTile(
                                leading: g.iconUrl != null
                                    ? ClipRRect(
                                        borderRadius:
                                            BorderRadius.circular(8),
                                        child: Image.network(
                                          g.iconUrl!,
                                          width: 44,
                                          height: 44,
                                          fit: BoxFit.cover,
                                          errorBuilder: (_, _, _) =>
                                              const Icon(
                                                  Icons.videogame_asset),
                                        ),
                                      )
                                    : const Icon(Icons.videogame_asset),
                                title: Text(g.name,
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis),
                                subtitle: Text(
                                  parts.isEmpty
                                      ? g.baseId
                                      : parts.join(' · '),
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                ),
                                trailing: g.dlc.isNotEmpty
                                    ? Badge(
                                        label:
                                            Text('${g.dlc.length}'),
                                        child: const Icon(
                                            Icons.extension_outlined),
                                      )
                                    : null,
                                onTap: () => _showDetail(g),
                              );
                            },
                          ),
          ),
        ],
      ),
    );
  }
}
