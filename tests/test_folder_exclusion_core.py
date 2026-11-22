"""Host-side SQLite and source contracts, not Android/Room/provider integration tests."""
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[1]
DB = (ROOT/'app/src/main/java/com/example/myebookapp/BookDatabase.kt').read_text()
SCAN = (ROOT/'app/src/main/java/com/example/myebookapp/LibraryScanner.kt').read_text()
CORE = (ROOT/'app/src/main/java/com/example/myebookapp/FolderRemovalCore.kt').read_text()
EVIDENCE = (ROOT/'app/src/main/java/com/example/myebookapp/FolderRemovalEvidence.kt').read_text()
M67 = DB.split('internal val M67', 1)[1].split('internal val M78', 1)[0]
M78 = DB.split('internal val M78', 1)[1].split('internal val M34', 1)[0]
SQL = re.findall(r'db\.execSQL\("([^"\n]+)"\)', M67 + M78)

class FolderExclusionCore(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.addCleanup(self.db.close)
        self.db.execute('CREATE TABLE books(id INTEGER PRIMARY KEY, uriString TEXT)')
        self.db.execute("INSERT INTO books VALUES(1,'content://legacy/doc')")
        self.db.commit()
        self.assertEqual(len(SQL), 3)
        for sql in SQL: self.db.execute(sql)
        self.db.commit()

    def test_migration_keeps_legacy_and_starts_empty(self):
        self.assertEqual(self.db.execute('SELECT * FROM books').fetchall(), [(1,'content://legacy/doc')])
        self.assertEqual(self.db.execute('SELECT * FROM scan_exclusions').fetchall(), [])
        self.assertEqual(self.db.execute('SELECT * FROM scan_allowed_roots').fetchall(), [])
        self.assertEqual(self.db.execute('SELECT * FROM scan_root_exclusions').fetchall(), [])
        self.assertIn('ScanRootExclusion::class], version = 8', DB)
        self.assertIn('addMigrations(M12, M23, M34, M45, M56, M67, M78)', DB)

    def test_exact_keys_and_transactional_rollback(self):
        self.db.executemany('INSERT INTO scan_exclusions VALUES(?,?)', [('provider','a'),('provider','a/b'),('other','a')])
        self.db.commit()
        self.assertEqual(self.db.execute("SELECT documentId FROM scan_exclusions WHERE authority='provider' AND documentId='a'").fetchall(), [('a',)])
        try:
            with self.db:
                self.db.execute("INSERT INTO scan_allowed_roots VALUES('root')")
                self.db.execute("INSERT INTO scan_exclusions VALUES('provider','a')")
        except sqlite3.IntegrityError: pass
        else: self.fail('expected rollback')
        self.assertEqual(self.db.execute('SELECT * FROM scan_allowed_roots').fetchall(), [])
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM books').fetchone()[0], 1)

    def test_scanner_enforces_root_and_legacy_exclusions_transactionally(self):
        walk = SCAN.split('private suspend fun walkTree', 1)[1]
        batch = DB.split('@Transaction suspend fun insertScannedBatch', 1)[1].split('@Insert(onConflict', 1)[0]
        self.assertIn('dao.rootExclusions(treeUri.toString())', walk)
        self.assertIn('dao.getScanExclusions().filter { it.authority == authority }', walk)
        self.assertIn('if (path.any { it in blocked }) continue', walk)
        self.assertIn('dao.insertScannedBatch(batch, keys, authority)', walk)
        self.assertIn('rootExclusions(root)', batch)
        self.assertIn('getScanExclusions().filter { it.authority == authority }', batch)
        self.assertIn('item.directoryPath.none { it in blocked }', batch)
        self.assertLess(batch.index('val allowed = items.filter'), batch.index('insertScanSightings(allowed.map'))
        self.assertLess(batch.index('insertScanSightings(allowed.map'), batch.index('insertIgnore(book)'))
        self.assertNotIn('getAllowedScanRoots()', SCAN)

    def test_unverified_provider_snapshot_never_deletes_books_or_files(self):
        body = CORE.split('suspend fun exclude(',1)[1]
        self.assertIn('dao.commitRootExclusion(', body)
        self.assertIn('FolderRemovalResult(0, dao.countAllBooks(), 0', body)
        self.assertNotIn('deleteById', body)
        self.assertNotIn('deleteDocument(', CORE + SCAN)
        self.assertNotIn('openOutputStream(', CORE + SCAN)
        self.assertIn('NOT evidence of exclusive book ownership', body)

if __name__ == '__main__': unittest.main()
