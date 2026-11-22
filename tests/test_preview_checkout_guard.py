"""Verify stale inputs are rejected before the workflow restores them."""
import json
from pathlib import Path
import shutil
import tempfile
import unittest

from check_preview_checkout import check
from restore_preview_sources import ROOT


class CheckoutGuardTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        for n in ('app', 'docs', 'tests', '.translation-build-input', '.folder-preview-input'):
            shutil.copytree(ROOT / n, self.root / n, ignore=shutil.ignore_patterns('__pycache__'))

    def test_current_payloads_match(self):
        check(self.root)

    def test_stale_checkout_source_rejected(self):
        p = self.root / 'app/src/main/java/com/example/myebookapp/RemovedScanRootPolicy.kt'
        p.write_text(p.read_text() + '\n// stale\n')
        with self.assertRaisesRegex(ValueError, 'Payload differs from checkout'):
            check(self.root)

    def test_missing_manifest_entry_rejected(self):
        p = self.root / 'tests/folder_overlay_paths.json'
        paths = json.loads(p.read_text())
        p.write_text(json.dumps(paths[:-1]))
        with self.assertRaisesRegex(ValueError, 'Folder overlay paths differ'):
            check(self.root)

    def test_preflight_runs_before_restore(self):
        text = (ROOT / '.github/workflows/build.yml').read_text()
        self.assertLess(text.index('python3 tests/check_preview_checkout.py'), text.index('python3 tests/restore_preview_sources.py'))
