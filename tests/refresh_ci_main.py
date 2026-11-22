"""Refresh CI's translation payload from the integrated checked-in MainActivity.

Run: python3 tests/refresh_ci_main.py. The folder payload must also be regenerated
separately; the CI restore refuses mismatched overlapping sources until then.
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
    decode(entry)  # Refuse to replace a damaged prior payload.
    source = (ROOT / MAIN).read_text()
    without_translation_overlay(source)  # Require intact whole-book translation fragments.
    for marker in ('ReadOnlyFolderManagementAdapter(ctx)', 'FolderManagementDialog(folderManagementAdapter)',
                   'LaunchedEffect(Unit) { LibraryScanner.restore(ctx) }'):
        if marker not in source:
            raise ValueError('Missing integrated folder/scanner marker: ' + marker)
    data = source.encode('utf-8')
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
