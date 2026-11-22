"""Fail closed unless every hydrated app/src file equals the experiment snapshot."""
import hashlib
from pathlib import Path
root = Path(__file__).resolve().parents[1]
files = sorted((p for p in (root / 'app/src').rglob('*') if p.is_file()), key=lambda p: p.relative_to(root).as_posix())
if any(p.is_symlink() for p in files):
    raise ValueError('Symlink in sources')
manifest = ''.join(f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.relative_to(root).as_posix()}\n" for p in files)
actual = hashlib.sha256(manifest.encode()).hexdigest()
expected = '7f14c45b6e40c6da358e47d491a461402a2ce46800ab28614adefdb9b40c4fa1'
if len(files) != 78 or actual != expected:
    raise ValueError(f'Source snapshot mismatch: {len(files)} files, {actual}')
print(f'Verified {len(files)} experiment source hashes: {actual}')
