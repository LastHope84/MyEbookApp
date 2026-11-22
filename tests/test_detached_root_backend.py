"""Host SQLite and source contracts only, not Room, scanner concurrency or SAF tests."""
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[1]
DB = (ROOT / 'app/src/main/java/com/example/myebookapp/BookDatabase.kt').read_text()
SCAN = (ROOT / 'app/src/main/java/com/example/myebookapp/LibraryScanner.kt').read_text()
POLICY = (ROOT / 'app/src/main/java/com/example/myebookapp/RemovedScanRootPolicy.kt').read_text()
MIGRATION = DB.split('internal val M910', 1)[1].split('// No backfill', 1)[0]
SQL = re.findall(r'db\.execSQL\("([^"\n]+)"\)', MIGRATION)

class DetachedRootBackend(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.addCleanup(self.db.close)
        self.db.execute('CREATE TABLE books(id INTEGER PRIMARY KEY, title TEXT)')
        self.db.execute("INSERT INTO books VALUES (1, 'edited')")
        self.db.commit()
        self.assertEqual(len(SQL), 1)
        self.db.execute(SQL[0])
        self.db.commit()

    def test_migration_and_restore_preserve_books(self):
        self.assertEqual(self.db.execute('SELECT * FROM removed_scan_roots').fetchall(), [])
        self.db.execute("INSERT INTO removed_scan_roots VALUES('tree', 'p', 'opaque')")
        self.db.execute("DELETE FROM removed_scan_roots WHERE rootUri='tree'")
        self.assertEqual(self.db.execute('SELECT * FROM books').fetchall(), [(1, 'edited')])

    def test_duplicate_root_rolls_back(self):
        self.db.execute("INSERT INTO removed_scan_roots VALUES('tree', 'p', 'id')")
        self.db.commit()
        with self.assertRaises(sqlite3.IntegrityError):
            with self.db:
                self.db.execute("INSERT INTO removed_scan_roots VALUES('another', 'p', 'id')")
                self.db.execute("INSERT INTO removed_scan_roots VALUES('tree', 'p', 'id')")
        self.assertEqual(self.db.execute('SELECT rootUri FROM removed_scan_roots').fetchall(), [('tree',)])

    def test_scanner_gate_order_and_transaction_check(self):
        detach = SCAN.split('suspend fun detachWholeScanRoot', 1)[1].split('suspend fun restoreDetachedScanRoot', 1)[0]
        gate = SCAN.split('private suspend fun <T> withScannerQuiescent', 1)[1].split('/** Provisional exclusion', 1)[0]
        batch = DB.split('@Transaction suspend fun insertScannedBatch', 1)[1].split('@Insert(onConflict', 1)[0]
        self.assertLess(detach.index('dao.detachWithInventory('), detach.index('putStringSet('))
        self.assertIn('processingMutex.withLock { reconcileFolderConfig(app); work() }', gate)
        self.assertIn('activeJob?.cancel()', gate)
        self.assertIn('removedRootBlocks(root, authority, removedScanRoots())', batch)
        self.assertIn('removedRootBlocks(treeUri.toString(), authority, dao.removedScanRoots())', SCAN)
        self.assertIn('it.authority == authority', POLICY)
        self.assertNotIn('deleteDocument(', detach)
        self.assertNotIn('releasePersistableUriPermission', detach)
        self.assertNotIn('deleteById', detach)

if __name__ == '__main__': unittest.main()
