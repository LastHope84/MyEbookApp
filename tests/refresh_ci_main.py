"""Refresh CI's MainActivity translation overlay after editing checked-in MainActivity.

Run from any directory: python3 tests/refresh_ci_main.py
The existing validated payload is the source of the translation-only fragments.
Refuses ambiguous anchors or damaged payloads rather than guessing.
"""
import base64
import hashlib
import json
import zlib

from test_ci_input_consistency import INPUT, MAIN, ROOT, decode, without_translation_overlay


def once(text, old, new):
    if text.count(old) != 1:
        raise ValueError('Missing or ambiguous anchor: ' + repr(old))
    return text.replace(old, new, 1)


def main():
    manifest = INPUT / 'manifest.json'
    entries = json.loads(manifest.read_text())
    matches = [entry for entry in entries if entry['path'] == MAIN]
    if len(matches) != 1:
        raise ValueError('Expected exactly one MainActivity entry')
    entry = matches[0]
    _, raw = decode(entry)
    previous = raw.decode('utf-8')
    # Validate all existing translation fragments before lifting them.
    without_translation_overlay(previous)
    state_start = 'var translatingBook by remember { mutableStateOf<BookEntity?>(null) }\n'
    state_end = 'var converting by remember { mutableStateOf(false) }\n'
    state = previous.split(state_start, 1)[1].split(state_end, 1)[0]
    state = state_start + state
    callback_anchor = 'statusChangeEnabled = !shelfSaving'
    callback_end = '\n)\n'
    callback = previous.split(callback_anchor, 1)[1].split(callback_end, 1)[0]
    dialog_start = 'translatingBook?.let { book ->\n'
    dialog_end = 'if (showCreateShelf) CreateShelfDialog'
    dialog = dialog_start + previous.split(dialog_start, 1)[1].split(dialog_end, 1)[0]
    source = (ROOT / MAIN).read_text()
    result = once(source, state_end, state + state_end)
    result = once(result, callback_anchor, callback_anchor + callback)
    result = once(result, dialog_end, dialog + dialog_end)
    if without_translation_overlay(result) != source:
        raise ValueError('Restored source does not match checkout')
    data = result.encode('utf-8')
    encoded = base64.b64encode(zlib.compress(data, 9)).decode('ascii')
    old_parts = entry['parts']
    if any('/' in p or '\\' in p for p in old_parts):
        raise ValueError('Unsafe part name')
    prefix = hashlib.sha256(MAIN.encode()).hexdigest()[:12]
    names = [f'{prefix}-{i // 6000:03d}.part' for i in range(0, len(encoded), 6000)]
    for index, name in enumerate(names):
        (INPUT / name).write_text(encoded[index * 6000:(index + 1) * 6000])
    entry['parts'] = names
    entry['sha256'] = hashlib.sha256(data).hexdigest()
    manifest.write_text(json.dumps(entries, separators=(',', ':')))
    for name in set(old_parts) - set(names):
        (INPUT / name).unlink()
    print(f'Refreshed {MAIN}: {len(data)} bytes, {len(names)} parts, sha256={entry["sha256"]}')


if __name__ == '__main__':
    main()
