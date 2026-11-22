import hashlib, json
from pathlib import Path
manifest=json.loads(Path('journal-snapshot-manifest.json').read_text())
for name, expected in manifest.items():
    p=Path(name)
    if p.is_symlink() or not p.is_file() or hashlib.sha256(p.read_bytes()).hexdigest()!=expected:
        raise SystemExit('Snapshot mismatch: '+name)
print(f'Verified {len(manifest)} snapshot files byte-for-byte')
