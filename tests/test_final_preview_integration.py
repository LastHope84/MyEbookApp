"""Exercise the actual CI restore on a scratch checkout; static tests, not Android tests."""
import base64
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zlib

import restore_preview_sources as restore

ROOT = Path(__file__).resolve().parents[1]
MAIN = 'app/src/main/java/com/example/myebookapp/MainActivity.kt'
HOST = 'app/src/main/java/com/example/myebookapp/FolderManagementHost.kt'
SCANNER = 'app/src/main/java/com/example/myebookapp/LibraryScanner.kt'


def require_features(main, host, scanner):
    for marker in (
        'LaunchedEffect(Unit) { LibraryScanner.restore(ctx) }',
        'ReadOnlyFolderManagementAdapter(ctx)', 'Text("Manage folders")',
        'FolderManagementDialog(folderManagementAdapter)',
        'WholeEpubTranslation.translate(', 'onTranslate = {',
        'translationEnabled = ThemePrefs.isOfflineTranslationEnabled(ctx)',
        'translationJob?.cancel()', 'original remains unchanged',
        'Source language code', 'Target language code',
    ):
        if marker not in main:
            raise AssertionError('Missing main feature: ' + marker)
    for marker in ('override val canRemove = false', 'override val canRestore = false',
                   'if (adapter.canRemove)', 'if (adapter.canRestore)'):
        if marker not in host:
            raise AssertionError('Missing read-only guard: ' + marker)
    for marker in ('object LibraryScanner', 'fun restore('):
        if marker not in scanner:
            raise AssertionError('Missing scanner: ' + marker)
    settings = next(x for x in main.splitlines() if 'if (showSet) AppSettingsBottomSheet(' in x)
    if '{}, {}, {}, {showSet=false}' not in settings:
        raise AssertionError('Unsafe duplicate cleanup callback connected')


class FinalPreviewIntegration(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        for name in ('app', 'docs', 'tests', '.translation-build-input', '.folder-preview-input', '.github'):
            shutil.copytree(ROOT / name, self.root / name, ignore=shutil.ignore_patterns('__pycache__'))

    def run_restore(self):
        return subprocess.run(['python3', 'tests/restore_preview_sources.py'], cwd=self.root,
                              capture_output=True, text=True)

    def test_real_workflow_restore_produces_both_features(self):
        translation, folder = restore.payloads(self.root)
        # Simulate absent/obsolete checkout versions and force the CI restore to write them.
        for name in set(translation) | set(folder):
            (self.root / name).unlink(missing_ok=True)
        result = self.run_restore()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        for name, data in {**translation, **folder}.items():
            self.assertEqual((self.root / name).read_bytes(), data, name)
            self.assertEqual((ROOT / name).read_bytes(), data, name + ' differs from integrated checkout')
        require_features(*[(self.root / n).read_text() for n in (MAIN, HOST, SCANNER)])

    def test_final_feature_guard_rejects_missing_wiring(self):
        result = self.run_restore()
        self.assertEqual(result.returncode, 0, result.stderr)
        sources = [(self.root / n).read_text() for n in (MAIN, HOST, SCANNER)]
        require_features(*sources)
        for index, marker in ((0, 'WholeEpubTranslation.translate('),
                              (0, 'onTranslate = {'),
                              (0, 'ReadOnlyFolderManagementAdapter(ctx)'),
                              (0, 'LaunchedEffect(Unit) { LibraryScanner.restore(ctx) }'),
                              (1, 'override val canRemove = false'),
                              (1, 'override val canRestore = false'),
                              (2, 'object LibraryScanner')):
            with self.subTest(marker=marker):
                broken = sources.copy()
                self.assertIn(marker, broken[index])
                broken[index] = broken[index].replace(marker, 'REMOVED_MARKER', 1)
                with self.assertRaises(AssertionError):
                    require_features(*broken)

    def test_conflicting_valid_translation_payload_fails_before_writes(self):
        folder = self.root / '.translation-build-input'
        manifest = folder / 'manifest.json'
        entries = json.loads(manifest.read_text())
        entry = next(e for e in entries if e['path'] == MAIN)
        data = restore.decompress(''.join((folder / n).read_text() for n in entry['parts'])) + b'\n// conflicting version\n'
        encoded = base64.b64encode(zlib.compress(data, 9)).decode()
        old = entry['parts']
        prefix = hashlib.sha256(MAIN.encode()).hexdigest()[:12]
        entry['parts'] = [f'{prefix}-{i // 6000:03}.part' for i in range(0, len(encoded), 6000)]
        for name in old:
            (folder / name).unlink()
        for i, name in enumerate(entry['parts']):
            (folder / name).write_text(encoded[i*6000:(i+1)*6000])
        entry['sha256'] = hashlib.sha256(data).hexdigest()
        manifest.write_text(json.dumps(entries, separators=(',', ':')))
        destination = self.root / MAIN
        destination.write_text('sentinel: must not be overwritten')
        result = self.run_restore()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Conflicting source payloads', result.stderr)
        self.assertEqual(destination.read_text(), 'sentinel: must not be overwritten')

    def test_damaged_overlay_fails_before_writes(self):
        part = self.root / '.folder-preview-input/000.part'
        text = part.read_text()
        part.write_text(('A' if text[0] != 'A' else 'B') + text[1:])
        destination = self.root / MAIN
        destination.write_text('sentinel')
        result = self.run_restore()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(destination.read_text(), 'sentinel')

    def test_payload_cannot_overwrite_tests_or_inputs(self):
        translation, folder = restore.payloads(self.root)
        self.assertTrue(all(n.startswith(('app/', 'docs/')) for n in folder))
        self.assertTrue(all(n.startswith('app/') for n in translation))
        self.assertEqual(set(translation) & set(folder), {MAIN})
        self.assertEqual(translation[MAIN], folder[MAIN])

    def test_workflow_runs_actual_restore_and_guards_before_build(self):
        workflow = (self.root / '.github/workflows/build.yml').read_text()
        restore_command = 'python3 tests/restore_preview_sources.py'
        integration = "python3 -m unittest discover -s tests -p 'test_final_preview_integration.py' -v"
        consistency = "python3 -m unittest discover -s tests -p 'test_ci_input_consistency.py' -v"
        self.assertEqual(workflow.count(restore_command), 1)
        self.assertLess(workflow.index(restore_command), workflow.index(integration))
        self.assertLess(workflow.index(restore_command), workflow.index(consistency))
        self.assertLess(workflow.index(integration), workflow.index('gradle clean assembleDebug'))
        self.assertLess(workflow.index(consistency), workflow.index('gradle clean assembleDebug'))


if __name__ == '__main__':
    unittest.main()
