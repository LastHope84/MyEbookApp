"""Execute the DAO's actual SQLite statements; source contracts are not Room/SAF tests."""
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DB = (ROOT / 'app/src/main/java/com/example/myebookapp/BookDatabase.kt').read_text()
Q = {name: sql for sql, name in re.findall(r'@Query\("([^"\n]*)"\) (?:suspend )?fun (\w+)\(', DB)}

class ProjectionSQL(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.executescript('''
        PRAGMA foreign_keys=ON;
        CREATE TABLE books(id INTEGER PRIMARY KEY, title TEXT, progress REAL);
        CREATE TABLE scan_owned_books(bookId INTEGER PRIMARY KEY REFERENCES books(id));
        CREATE TABLE independent_books(bookId INTEGER PRIMARY KEY REFERENCES books(id));
        CREATE TABLE hidden_scan_books(bookId INTEGER PRIMARY KEY REFERENCES books(id), rootUri TEXT NOT NULL);
        CREATE TABLE custom_shelf_books(shelfId INTEGER,bookId INTEGER);
        INSERT INTO books VALUES (1,'exclusive',0.4),(2,'independent',0.7),(3,'legacy',0.8);
        INSERT INTO scan_owned_books VALUES(1),(2);
        INSERT INTO independent_books VALUES(2);
        INSERT INTO custom_shelf_books VALUES(9,1);
        ''')
        self.db.commit()
        self.addCleanup(self.db.close)

    def rows(self, table):
        return self.db.execute('SELECT * FROM ' + table + ' ORDER BY 1').fetchall()

    def test_candidate_sql_excludes_manual_and_legacy(self):
        self.assertEqual(self.db.execute(Q['possibleScanOnlyBookIds']).fetchall(), [(1,)])

    def test_insert_sql_only_projects_and_preserves_metadata(self):
        before = self.rows('books'), self.rows('custom_shelf_books')
        for book_id in (1, 2, 3, 999):
            self.db.execute(Q['insertProjectedHiddenBook'], {'bookId': book_id, 'rootUri': 'a'})
        self.assertEqual(self.rows('hidden_scan_books'), [(1, 'a')])
        self.db.execute(Q['insertProjectedHiddenBook'], {'bookId': 1, 'rootUri': 'b'})
        self.assertEqual(self.rows('hidden_scan_books'), [(1, 'a')])
        self.assertEqual((self.rows('books'), self.rows('custom_shelf_books')), before)
        self.db.execute(Q['unhideAllScanBooks'])
        self.assertEqual(self.rows('hidden_scan_books'), [])
        self.assertEqual((self.rows('books'), self.rows('custom_shelf_books')), before)

    def test_transaction_rollback_cannot_leave_partial_projection(self):
        self.db.execute('INSERT INTO hidden_scan_books VALUES(1,\'old\')')
        self.db.commit()
        self.db.execute('BEGIN')
        self.db.execute(Q['unhideAllScanBooks'])
        self.db.execute(Q['insertProjectedHiddenBook'], {'bookId': 1, 'rootUri': 'new'})
        self.db.rollback()
        self.assertEqual(self.rows('hidden_scan_books'), [(1, 'old')])

    def test_independent_added_before_projection_prevents_hide(self):
        self.db.execute('INSERT INTO independent_books VALUES(1)')
        self.db.execute(Q['insertProjectedHiddenBook'], {'bookId': 1, 'rootUri': 'a'})
        self.assertEqual(self.rows('hidden_scan_books'), [])

    def test_fail_closed_issuer_and_transaction_wiring(self):
        self.assertRegex(DB, r'private suspend fun completeVisibilityEvidence\([^\n]+\): MembershipEvidence\? = null')
        projector = DB.split('@Transaction suspend fun recomputePathVisibility()',1)[1].split('/** UI-disabled',1)[0]
        for phrase in ('unhideAllScanBooks()', 'allPathExclusions()', 'getScanExclusions().isNotEmpty()',
                       'removedScanRoots().isNotEmpty()', 'rootExclusions(it).isNotEmpty()',
                       'completeVisibilityEvidence(bookId, generation) ?: continue',
                       'BookMembershipDecision.decidePathQualified(evidence.copy(exclusions = currentExclusions))'):
            self.assertIn(phrase, projector)
        commit = DB.split('@Transaction suspend fun commitObservedPathExclusion',1)[1].split('@Transaction suspend fun restoreObservedPathExclusion',1)[0]
        self.assertLess(commit.index('invalidateScanInventory()'), commit.index('insertPathExclusion('))
        self.assertLess(commit.index('insertPathExclusion('), commit.index('recomputePathVisibility()'))
        restore = DB.split('@Transaction suspend fun restoreObservedPathExclusion',1)[1].split('@Query',1)[0]
        self.assertIn('recomputePathVisibility()', restore)
        batch = DB.split('@Transaction suspend fun insertScannedBatch',1)[1].split('@Insert(onConflict',1)[0]
        self.assertIn('(inventoryState()?.generation ?: 0L) == scanGeneration', batch)

if __name__ == '__main__':
    unittest.main()
