"""Regenerate the folder payload from explicit current checkout paths.

After running, update the three OVERLAY_* constants printed below in
restore_preview_sources.py. Refresh translation MainActivity separately.
"""
import base64
import hashlib
import json
from pathlib import Path
import zlib

ROOT = Path(__file__).resolve().parents[1]
paths = json.loads((ROOT / 'tests/folder_overlay_paths.json').read_text())
if paths != sorted(set(paths)) or any(not p.startswith(('app/', 'docs/')) or '..' in Path(p).parts for p in paths):
    raise ValueError('Invalid or duplicate folder paths')
data = {p: (ROOT / p).read_text(encoding='utf-8') for p in paths}
raw = json.dumps(data, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode('utf-8')
encoded = base64.b64encode(zlib.compress(raw, 9)).decode('ascii')
directory = ROOT / '.folder-preview-input'
for old in directory.glob('*.part'):
    old.unlink()
for i in range(0, len(encoded), 6000):
    (directory / f'{i // 6000:03}.part').write_text(encoded[i:i+6000], encoding='ascii')
print('OVERLAY_SHA256 =', repr(hashlib.sha256(raw).hexdigest()))
print('OVERLAY_PARTS =', (len(encoded) + 5999) // 6000)
print('OVERLAY_FILES =', len(data))
