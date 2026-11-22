"""Materialize three large CI transport files from the authenticated overlay.

Only these three explicitly named tracked sources are hydrated. The ordinary
checkout check then verifies every source against both verified payloads.
"""
from restore_preview_sources import ROOT, payloads

PATHS = (
    'app/src/main/java/com/example/myebookapp/BookDatabase.kt',
    'app/src/main/java/com/example/myebookapp/LibraryScanner.kt',
    'app/src/main/java/com/example/myebookapp/MainActivity.kt',
)
translation, folder = payloads(ROOT)
for name in PATHS:
    path = ROOT / name
    if name not in folder or path.is_symlink() or not path.resolve().is_relative_to(ROOT.resolve()):
        raise ValueError('Unsafe or missing CI transport source: ' + name)
for name in PATHS:
    (ROOT / name).write_bytes(folder[name])
print('Hydrated three large CI transport sources from verified overlay')
