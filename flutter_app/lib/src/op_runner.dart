import 'package:flutter/foundation.dart';

/// Serializes native operations (the Rust core is not re-entrant safe for
/// heavy file ops) and exposes a busy flag plus the accumulated log text.
class OpRunner {
  OpRunner._();

  static final OpRunner instance = OpRunner._();

  final ValueNotifier<bool> busy = ValueNotifier(false);
  final ValueNotifier<String> currentOp = ValueNotifier('');
  final ValueNotifier<String> logText = ValueNotifier('');

  void appendLog(String text) {
    if (text.isEmpty) return;
    final existing = logText.value;
    logText.value = existing.isEmpty ? text : '$existing\n$text';
  }

  void clearLog() => logText.value = '';

  Future<T> run<T>(String name, Future<T> Function() op) async {
    if (busy.value) {
      throw StateError('Another operation is already running');
    }
    busy.value = true;
    currentOp.value = name;
    try {
      return await op();
    } finally {
      busy.value = false;
      currentOp.value = '';
    }
  }
}
