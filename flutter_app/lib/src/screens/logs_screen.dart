import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../op_runner.dart';

class LogsScreen extends StatelessWidget {
  const LogsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Logs'),
        actions: [
          IconButton(
            icon: const Icon(Icons.copy),
            onPressed: () => Clipboard.setData(
                ClipboardData(text: OpRunner.instance.logText.value)),
          ),
          IconButton(
            icon: const Icon(Icons.delete_sweep_outlined),
            onPressed: OpRunner.instance.clearLog,
          ),
        ],
      ),
      body: ValueListenableBuilder<String>(
        valueListenable: OpRunner.instance.logText,
        builder: (context, text, _) => text.isEmpty
            ? const Center(child: Text('No log output yet.'))
            : SingleChildScrollView(
                reverse: true,
                padding: const EdgeInsets.all(12),
                child: SizedBox(
                  width: double.infinity,
                  child: Text(
                    text,
                    style:
                        const TextStyle(fontFamily: 'monospace', fontSize: 12),
                  ),
                ),
              ),
      ),
    );
  }
}
