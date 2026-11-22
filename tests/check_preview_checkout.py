"""Fail before restore if either CI payload is stale relative to the checkout."""
import json
from pathlib import Path
from restore_preview_sources import ROOT, payloads


def check(root=ROOT):
    root = Path(root)
    translation, folder = payloads(root)
    paths = json.loads((root / 'tests/folder_overlay_paths.json').read_text())
    if paths != sorted(set(paths)) or set(paths) != set(folder):
        raise ValueError('Folder overlay paths differ from manifest')
    for name, data in {**translation, **folder}.items():
        path = root / name
        if path.is_symlink() or not path.is_file() or path.read_bytes() != data:
            raise ValueError('Payload differs from checkout: ' + name)
    print(f'Checkout matches {len(translation)} translation and {len(folder)} folder files')


if __name__ == '__main__':
    check()
