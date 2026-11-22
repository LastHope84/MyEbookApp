"""Groundwork checks, not a substitute for Android Room/instrumented concurrency tests."""
import pathlib
import re
import sqlite3
import unittest

BASE = pathlib.Path(__file__).resolve().parents[1] / 'app/src/main/java/com/example/myebookapp'
DB = (BASE / 'BookDatabase.kt').read_text()
SCANNER = (BASE / 'LibraryScanner.kt').read_text()
CORE = (BASE / 'FolderRemovalCore.kt').read_text()


class ScopedExclusionGroundwork(unittest.TestCase):
    def test_migration_preserves_data_and_scopes_by_root(self):
        sql = re.search(r'db.execSQL\("(CREATE TABLE IF NOT EXISTS `scan_root_exclusions`[^\"]+)"\)', DB).group(1)
        con = sqlite3.connect(':memory:')
        con.execute('CREATE TABLE books (id INTEGER PRIMARY KEY)')
        con.execute('INSERT INTO books VALUES (42)')
        con.execute(sql)
        con.execute('INSERT INTO scan_root_exclusions VALUES (?, ?)', ('root-a', 'opaque/id'))
        con.execute('INSERT INTO scan_root_exclusions VALUES (?, ?)', ('root-b', 'opaque/id'))
        self.assertEqual(con.execute('SELECT COUNT(*) FROM scan_root_exclusions').fetchone()[0], 2)
        self.assertEqual(con.execute('SELECT * FROM books').fetchall(), [(42,)])

    def test_batch_and_exclusion_have_transaction_annotations(self):
        self.assertIn('@Transaction suspend fun commitRootExclusion', DB)
        self.assertIn('@Transaction suspend fun insertScannedBatch', DB)
        self.assertIn('item.directoryPath.none { it in blocked }', DB)
        self.assertIn('item.sighting.documentId in blocked', DB)
        self.assertIn('insertScanSightings(allowed.map', DB)
        self.assertLess(DB.index('val allowed = items.filter'), DB.index('insertScanSightings(allowed.map'))

    def test_scanner_does_not_require_worker_pause_or_disallow_new_roots(self):
        method = SCANNER.split('suspend fun excludeFolderFromScans', 1)[1].split('private suspend fun walkTree', 1)[0]
        self.assertNotIn('processingMutex', method)
        self.assertNotIn('Stop or pause', method)
        self.assertNotIn('getAllowedScanRoots()', SCANNER)
        self.assertIn('dao.insertScannedBatch(batch, keys, authority)', SCANNER)
        self.assertIn('ScannedItem(sighting, book, path)', SCANNER)
        self.assertIn('return FolderRemovalResult(0,', CORE)

if __name__ == '__main__':
    unittest.main()
