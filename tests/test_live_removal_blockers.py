"""Counterexamples for exclusion-only prototype; NOT Android/Room integration tests.

These models expose why origin-only cleanup must remain disabled. They do not assert
that SAF providers provide snapshots or that the Room implementation is race-free.
"""
from pathlib import Path
import re
import sqlite3
import unittest

BASE = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/example/myebookapp'
DB = (BASE / 'BookDatabase.kt').read_text()
SCANNER = (BASE / 'LibraryScanner.kt').read_text()
CORE = (BASE / 'FolderRemovalCore.kt').read_text()


def database():
    con = sqlite3.connect(':memory:')
    con.execute('PRAGMA foreign_keys=ON')
    con.execute('CREATE TABLE books (id INTEGER PRIMARY KEY, uriString TEXT UNIQUE, fileName TEXT, fileSize INTEGER)')
    for table in ('scan_book_origins', 'scan_sightings', 'scan_root_exclusions'):
        pattern = rf'db.execSQL\("(CREATE TABLE IF NOT EXISTS `{table}`[^\"]+)"\)'
        con.execute(re.search(pattern, DB).group(1))
    return con


class UnsafeOwnershipCounterexamples(unittest.TestCase):
    def test_other_root_can_be_shared_without_a_second_origin(self):
        con = database()
        con.execute("INSERT INTO books VALUES (1, 'rootA/file', 'same.epub', 10)")
        con.execute("INSERT INTO scan_book_origins VALUES (1, 'rootA', 'selected', 'doc')")
        # Scanner records the other sighting but suppresses a name/size duplicate.
        con.execute("INSERT INTO scan_sightings VALUES ('rootB', 'outside', 'doc', 0)")
        self.assertEqual(con.execute('SELECT COUNT(*) FROM scan_book_origins').fetchone()[0], 1)
        self.assertEqual(con.execute("SELECT COUNT(*) FROM scan_sightings WHERE rootUri='rootB' AND documentId='doc'").fetchone()[0], 1)
        batch = DB.split('@Transaction suspend fun insertScannedBatch', 1)[1].split('@Insert(onConflict', 1)[0]
        self.assertIn('key in knownKeys || key in acceptedKeys', batch)
        self.assertIn('if (id == -1L) continue', batch)
        self.assertLess(batch.index('if (id == -1L) continue'), batch.index('origins.add(ScanBookOrigin'))
        # Deleting by first origin would delete the only representation of a shared book.
        con.execute("DELETE FROM books WHERE id IN (SELECT bookId FROM scan_book_origins WHERE rootUri='rootA' AND parentDocumentId='selected')")
        self.assertEqual(con.execute('SELECT COUNT(*) FROM books').fetchone()[0], 0)

    def test_pending_other_root_can_be_suppressed_by_stale_keys_after_deletion(self):
        con = database()
        con.execute("INSERT INTO books VALUES (1, 'rootA/file', 'same.epub', 10)")
        con.execute("INSERT INTO scan_book_origins VALUES (1, 'rootA', 'selected', 'doc')")
        # walkTree snapshots keys once per root before committing its batches.
        keys = {f'{name.lower()}|{size}' for name, size in con.execute('SELECT fileName,fileSize FROM books')}
        self.assertIn('dao.getAllNameSizeKeys().toMutableSet()', SCANNER)
        con.execute('DELETE FROM books WHERE id=1')  # hypothetical unsafe cleanup
        pending_key = 'same.epub|10'
        if pending_key not in keys:  # same rule as insertScannedBatch
            con.execute("INSERT INTO books VALUES (2, 'rootB/file', 'same.epub', 10)")
        self.assertEqual(con.execute('SELECT COUNT(*) FROM books').fetchone()[0], 0)

    def test_25k_exclusion_scope_and_rollback_model(self):
        con = database()
        # An exclusion in one grant must not block the identical document ID in another.
        con.execute("INSERT INTO scan_root_exclusions VALUES ('rootA','selected')")
        con.executemany('INSERT INTO books VALUES (?, ?, ?, ?)',
            ((i, f'uri/{i}', f'{i}.epub', 1) for i in range(1, 25001)))
        con.commit()
        con.execute('BEGIN')
        con.execute("INSERT INTO scan_root_exclusions VALUES ('rootB','selected')")
        con.execute('DELETE FROM books WHERE id <= 200')
        con.rollback()
        self.assertEqual(con.execute('SELECT COUNT(*) FROM books').fetchone()[0], 25000)
        self.assertEqual(con.execute('SELECT rootUri FROM scan_root_exclusions').fetchall(), [('rootA',)])

    def test_exclusion_only_api_does_not_claim_cleanup(self):
        self.assertIn('return FolderRemovalResult(0, dao.countAllBooks(), 0, authority, directoryId)', CORE)
        self.assertIn('Provisional exclusion only: does NOT remove library rows', SCANNER)


if __name__ == '__main__':
    unittest.main()
