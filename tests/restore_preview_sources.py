"""Verify both CI payloads fully, then restore exactly the sources CI builds."""
import base64
import hashlib
import json
from pathlib import Path, PurePosixPath
import zlib

ROOT = Path(__file__).resolve().parents[1]
OVERLAY_SHA256 = '48bfe9ed997b1040f12fa2641c7c04a610c96b691a5c6547632281448e8db149'
OVERLAY_PARTS = 19
OVERLAY_FILES = 47
TRANSLATION_FILES = {
    'app/src/main/java/com/example/myebookapp/MainActivity.kt',
    'app/src/main/java/com/example/myebookapp/ReaderActivity.kt',
    'app/src/main/java/com/example/myebookapp/EpubOfflineTranslator.kt',
}


def fail(message):
    raise ValueError(message)


def pairs(items):
    result = {}
    for key, value in items:
        if key in result:
            fail('Duplicate JSON key: ' + key)
        result[key] = value
    return result


def decompress(encoded):
    raw = base64.b64decode(encoded, validate=True)
    obj = zlib.decompressobj()
    data = obj.decompress(raw) + obj.flush()
    if not obj.eof or obj.unused_data or obj.unconsumed_tail:
        fail('Incomplete or trailing compressed data')
    return data


def safe_path(name, allowed):
    if not isinstance(name, str) or '\\' in name:
        fail('Invalid path')
    p = PurePosixPath(name)
    if p.is_absolute() or '..' in p.parts or str(p) != name or not p.parts or p.parts[0] not in allowed:
        fail('Unsafe path: ' + name)
    return p


def payloads(root=ROOT):
    root = Path(root)
    source = root / '.translation-build-input'
    entries = json.loads((source / 'manifest.json').read_text(), object_pairs_hook=pairs)
    if len(entries) != len(TRANSLATION_FILES) or {x['path'] for x in entries} != TRANSLATION_FILES:
        fail('Unexpected translation manifest entries')
    translation = {}
    referenced = []
    for entry in entries:
        names = entry['parts']
        if not names or any(not isinstance(n, str) or Path(n).name != n or n in ('.', '..') for n in names):
            fail('Unsafe or empty translation parts')
        referenced.extend(names)
        data = decompress(''.join((source / n).read_text() for n in names))
        if hashlib.sha256(data).hexdigest() != entry['sha256']:
            fail('Translation checksum mismatch: ' + entry['path'])
        safe_path(entry['path'], {'app'})
        translation[entry['path']] = data
    if len(referenced) != len(set(referenced)) or set(referenced) != {p.name for p in source.glob('*.part')}:
        fail('Unexpected or duplicated translation part')
    directory = root / '.folder-preview-input'
    parts = sorted(directory.glob('*.part'))
    if [p.name for p in parts] != [f'{i:03}.part' for i in range(OVERLAY_PARTS)]:
        fail('Unexpected folder overlay parts')
    raw = decompress(''.join(p.read_text() for p in parts))
    if hashlib.sha256(raw).hexdigest() != OVERLAY_SHA256:
        fail('Folder overlay checksum mismatch')
    data = json.loads(raw, object_pairs_hook=pairs)
    if len(data) != OVERLAY_FILES:
        fail('Unexpected folder overlay file count')
    folder = {}
    for name, text in data.items():
        safe_path(name, {'app', 'docs'})
        if not isinstance(text, str):
            fail('Non-text overlay value')
        folder[name] = text.encode('utf-8')
    for name in translation.keys() & folder.keys():
        if translation[name] != folder[name]:
            fail('Conflicting source payloads: ' + name)
    return translation, folder


def restore(root=ROOT):
    root = Path(root)
    translation, folder = payloads(root)
    for name, data in {**translation, **folder}.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        if path.is_symlink() or not path.resolve().is_relative_to(root.resolve()):
            fail('Unsafe destination: ' + name)
        path.write_bytes(data)
    print(f'Verified and restored {len(translation)} translation and {len(folder)} folder files')


if __name__ == '__main__':
    restore()
