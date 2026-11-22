"""Narrow source contracts and SQLite checks; NOT Android lifecycle/provider tests."""
from pathlib import Path
import re
import sqlite3
import unittest

SRC = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/example/myebookapp'


def source(name):
    return (SRC / name).read_text()


def query(method):
    match = re.search(r'@Query\("([^"\n]+)"\)\s*suspend\s+fun\s+' + method + r'\s*\(', source('BookDatabase.kt'))
    assert match, method
    return match.group(1)


class ScanSourceContracts(unittest.TestCase):
    def test_idle_service_does_not_wait_for_missing_callback(self):
        scanner = source('LibraryScanner.kt')
        service = source('ScanService.kt')
        self.assertIn('fun startWorker(context: Context, finished: () -> Unit): Boolean', scanner)
        self.assertIn('return@synchronized activeJob?.isActive == true', scanner)
        self.assertIn('job.invokeOnCompletion', scanner)
        self.assertLess(scanner.index('job.invokeOnCompletion'), scanner.index('job.start()'))
        self.assertIn('if (!hasWorker) stopSelfResult(latestStartId)', service)
        self.assertIn('if (promoted) ensureWorker()', service)
        self.assertIn('promoted = false\n        LibraryScanner.interrupt', service)

    def test_provider_extras_checked_before_and_after_enumeration(self):
        scanner = source('LibraryScanner.kt')
        walk = scanner[scanner.index('private suspend fun walkTree'):scanner.index('private suspend fun processMetadata')]
        self.assertIn('DocumentsContract.EXTRA_LOADING', walk)
        self.assertIn('DocumentsContract.EXTRA_ERROR', walk)
        self.assertEqual(walk.count('                requireCompleteResults()'), 2)
        self.assertLess(walk.index('requireCompleteResults()\n                while'), walk.index('while (c.moveToNext())'))
        self.assertIn('Providers may update extras', walk)

    def test_cover_uses_unique_file_and_guarded_cleanup(self):
        scanner = source('LibraryScanner.kt')
        metadata = scanner[scanner.index('private suspend fun processMetadata'):scanner.index('fun saveWebpThumbnail')]
        self.assertIn('File.createTempFile("scan-", ".webp", dir)', metadata)
        self.assertIn('withContext(NonCancellable) {\n                        dao.updatePendingMetadata', metadata)
        self.assertIn('withContext(NonCancellable) {\n                        try { if (dao.countCoverReferences(file.absolutePath) == 0) file.delete()', metadata)
        self.assertIn('catch (_: Exception) { /* keep it if ownership is uncertain */ }', metadata)

    def test_notification_action_is_saveable_and_uses_values(self):
        activity = source('MainActivity.kt')
        self.assertIn('pendingScanAction by rememberSaveable', activity)
        self.assertIn('pendingScanFolder by rememberSaveable', activity)
        # Folder onboarding is deliberately blocked in the recovery-guard snapshot.
        self.assertNotIn('folderP.launch(', activity)
        self.assertIn('Add Folder (temporarily disabled)', activity)
        self.assertIn('requestScan("rescan")', activity)
        self.assertIn('requestScan("resume")', activity)
        self.assertIn('if (action != null) runScanAction(action, folder)', activity)


class CoverReferenceSqlTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.addCleanup(self.db.close)
        self.db.execute('CREATE TABLE books(id INTEGER PRIMARY KEY, coverPath TEXT, metadataExtracted INTEGER, title TEXT, author TEXT, publishingDate TEXT, publisher TEXT, language TEXT, series TEXT, seriesIndex REAL, tags TEXT)')
        self.db.execute('INSERT INTO books(id, coverPath, metadataExtracted) VALUES(1, ?, 0)', ('old',))
        self.params = dict(id=1, t='title', a='author', d='', p='', l='', c='new', s=None, si=None, tags=None)

    def count(self, path):
        return self.db.execute(query('countCoverReferences'), dict(path=path)).fetchone()[0]

    def test_update_commit_is_referenced(self):
        self.db.execute(query('updatePendingMetadata'), self.params)
        self.assertEqual(self.count('new'), 1)
        self.assertEqual(self.count('old'), 0)

    def test_stale_extraction_is_unreferenced_and_edit_unchanged(self):
        self.db.execute("UPDATE books SET metadataExtracted=1, coverPath='edited' WHERE id=1")
        result = self.db.execute(query('updatePendingMetadata'), self.params)
        self.assertEqual(result.rowcount, 0)
        self.assertEqual(self.count('new'), 0)
        self.assertEqual(self.count('edited'), 1)


if __name__ == '__main__':
    unittest.main()
