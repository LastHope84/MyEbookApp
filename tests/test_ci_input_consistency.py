"""Guard the CI source overlay against silently dropping current app features.

The checkout now includes the whole-book translation UI. CI's encoded sources
must match it byte-for-byte; stripping translation from only one side is obsolete.
After changing MainActivity, update both encoded payloads as well as its source.
Run: python3 -m unittest discover -s tests -p 'test_ci_input_consistency.py'
"""
import base64
import hashlib
import json
import pathlib
import tempfile
import unittest
import zlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
INPUT = ROOT / '.translation-build-input'
MAIN = 'app/src/main/java/com/example/myebookapp/MainActivity.kt'
EXPECTED = {
    MAIN,
    'app/src/main/java/com/example/myebookapp/ReaderActivity.kt',
    'app/src/main/java/com/example/myebookapp/EpubOfflineTranslator.kt',
}


def decode(entry):
    encoded = ''.join((INPUT / part).read_text() for part in entry['parts'])
    raw = base64.b64decode(encoded, validate=True)
    decompressor = zlib.decompressobj()
    data = decompressor.decompress(raw) + decompressor.flush()
    if not decompressor.eof or decompressor.unused_data or decompressor.unconsumed_tail:
        raise ValueError('Incomplete zlib stream or trailing compressed data')
    if hashlib.sha256(data).hexdigest() != entry['sha256']:
        raise ValueError('Checksum mismatch: ' + entry['path'])
    return encoded, data


def remove_once(text, fragment):
    if text.count(fragment) != 1:
        raise ValueError('Translation overlay fragment missing or duplicated')
    return text.replace(fragment, '', 1)


def without_translation_overlay(text):
    """Strip the three known translation-only additions; retain everything else."""
    state = '''var translatingBook by remember { mutableStateOf<BookEntity?>(null) }
var translationJob by remember { mutableStateOf<Job?>(null) }
var translationProgress by remember { mutableStateOf<String?>(null) }
var translationError by remember { mutableStateOf<String?>(null) }
var translatingSource by remember { mutableStateOf("") }
var translatingTarget by remember { mutableStateOf("") }
'''
    text = remove_once(text, state)
    callback = ''',
translationEnabled = ThemePrefs.isOfflineTranslationEnabled(ctx) && translationJob == null,
onTranslate = { translatingSource = ""; translatingTarget = ""; translatingBook = b }'''
    text = remove_once(text, callback)
    start, end = 'translatingBook?.let { book ->\n', 'if (showCreateShelf) CreateShelfDialog'
    if text.count(start) != 1 or text.count(end) != 1:
        raise ValueError('Translation dialog boundaries missing or duplicated')
    before, overlay = text.split(start, 1)
    dialogs, after = overlay.split(end, 1)
    # Keep the translation behavior from being silently lost while retaining markers.
    for expected in ('WholeEpubTranslation.translate(', 'translationJob?.cancel()',
                     'translationError?.let', 'original remains unchanged',
                     'Source language code', 'Target language code'):
        if expected not in dialogs:
            raise ValueError('Missing translation behavior: ' + expected)
    return before + end + after


class CIInputConsistencyTest(unittest.TestCase):
    def test_manifest_payloads_and_reproducible_restore(self):
        entries = json.loads((INPUT / 'manifest.json').read_text())
        self.assertEqual(len(entries), len(EXPECTED))
        self.assertEqual({e['path'] for e in entries}, EXPECTED)
        referenced = []
        with tempfile.TemporaryDirectory() as tmp:
            for entry in entries:
                parts = entry['parts']
                self.assertTrue(parts)
                self.assertTrue(all(pathlib.PurePosixPath(p).name == p for p in parts))
                referenced.extend(parts)
                encoded, data = decode(entry)
                # Fixed level and chunk size used to generate these payloads.
                self.assertEqual(encoded, base64.b64encode(zlib.compress(data, 9)).decode())
                self.assertEqual([len((INPUT / p).read_text()) for p in parts],
                                 [len(encoded[i:i + 6000]) for i in range(0, len(encoded), 6000)])
                target = pathlib.Path(tmp) / entry['path']
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
                self.assertEqual(target.read_bytes(), data)
        self.assertEqual(len(referenced), len(set(referenced)))
        self.assertEqual(set(referenced), {p.name for p in INPUT.glob('*.part')})

    def test_restored_main_preserves_source_and_translation(self):
        entries = json.loads((INPUT / 'manifest.json').read_text())
        entry = next(e for e in entries if e['path'] == MAIN)
        _, data = decode(entry)
        without_translation_overlay(data.decode('utf-8'))  # validate translation fragments too
        self.assertEqual(data, (ROOT / MAIN).read_bytes(),
                         'CI MainActivity differs from integrated source; refresh both payloads')

    def test_guard_detects_stale_scanner_ui(self):
        entry = next(e for e in json.loads((INPUT / 'manifest.json').read_text()) if e['path'] == MAIN)
        _, data = decode(entry)
        integrated = data.decode('utf-8')
        self.assertEqual(integrated, (ROOT / MAIN).read_text())
        self.assertIn('LaunchedEffect(Unit) { LibraryScanner.restore(ctx) }', integrated)
        stale = integrated.replace('LaunchedEffect(Unit) { LibraryScanner.restore(ctx) }\n', '', 1)
        self.assertNotEqual(stale, (ROOT / MAIN).read_text())

    def test_guard_detects_stale_folder_management_wiring(self):
        entry = next(e for e in json.loads((INPUT / 'manifest.json').read_text()) if e['path'] == MAIN)
        _, data = decode(entry)
        integrated = data.decode('utf-8')
        self.assertEqual(integrated, (ROOT / MAIN).read_text())
        for marker in ('ReadOnlyFolderManagementAdapter(ctx)', 'Text("Manage folders")',
                       'FolderManagementDialog(folderManagementAdapter)'):
            self.assertIn(marker, integrated)
            self.assertNotEqual(integrated.replace(marker, '', 1), (ROOT / MAIN).read_text())


if __name__ == '__main__':
    unittest.main()
