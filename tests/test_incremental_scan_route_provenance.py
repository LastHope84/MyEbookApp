"""Host SQLite migration and source contracts, NOT Android Room/provider execution."""
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[1]
DB = (ROOT / 'app/src/main/java/com/example/myebookapp/BookDatabase.kt').read_text()
SCAN = (ROOT / 'app/src/main/java/com/example/myebookapp/LibraryScanner.kt').read_text()
M89 = DB.split('internal val M89', 1)[1].split('private val M12', 1)[0]
SQL = re.findall(r'db\.execSQL\("([^"\n]+)"\)', M89)
BATCH = DB.split('@Transaction suspend fun insertScannedBatch', 1)[1].split('    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(', 1)[0]

class IncrementalScanRouteProvenance(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.addCleanup(self.db.close)
        self.db.execute('PRAGMA foreign_keys=ON')
        self.db.execute('CREATE TABLE books(id INTEGER PRIMARY KEY, uriString TEXT UNIQUE, title TEXT)')
        self.db.execute("INSERT INTO books VALUES(1, 'legacy', 'Edited title')")
        self.db.commit()
        self.assertEqual(len(SQL), 3)
        for statement in SQL: self.db.execute(statement)
        self.db.commit()

    def test_migration_preserves_rows_and_leaves_unknown_empty(self):
        self.assertEqual(self.db.execute('SELECT * FROM books').fetchall(), [(1, 'legacy', 'Edited title')])
        self.assertEqual(self.db.execute('SELECT * FROM scan_book_routes').fetchall(), [])
        self.assertEqual(self.db.execute('SELECT * FROM scan_unresolved_routes').fetchall(), [])
        self.assertIn('M78, M89, M910, M1011)', DB)
        self.assertIn('version = 11', DB)

    def test_identity_index_schema_and_query_plan(self):
        index_name = 'index_scan_book_routes_authority_documentId'
        indices = self.db.execute('PRAGMA index_list(scan_book_routes)').fetchall()
        index = next(row for row in indices if row[1] == index_name)
        self.assertEqual(index[2], 0)  # Non-unique: ambiguous identities must remain representable.
        self.assertEqual([row[2] for row in self.db.execute(f'PRAGMA index_info({index_name})')],
                         ['authority', 'documentId'])
        entity = DB.split('@Entity(tableName = "scan_book_routes"', 1)[1].split('data class ScanBookRoute', 1)[0]
        self.assertIn('Index(value = ["authority", "documentId"])', entity)
        self.db.execute("INSERT INTO books VALUES(2, 'another', 'Another')")
        self.db.executemany('INSERT INTO scan_book_routes VALUES(?,?,?,?,?)', [
            (1, 'root1', 'provider', 'doc', '["root1"]'),
            (1, 'root2', 'provider', 'doc', '["root2"]'),
            (2, 'root3', 'provider', 'doc', '["root3"]'),
            (2, 'root4', 'other', 'doc', '["root4"]'),
        ])
        query = 'SELECT DISTINCT documentId, bookId FROM scan_book_routes WHERE authority = ? AND documentId IN (?, ?)'
        self.assertEqual(set(self.db.execute(query, ('provider', 'doc', 'missing'))), {('doc', 1), ('doc', 2)})
        plan = self.db.execute('EXPLAIN QUERY PLAN ' + query, ('provider', 'doc', 'missing')).fetchall()
        self.assertTrue(any('SEARCH' in row[3].upper() and index_name in row[3] for row in plan), plan)

    def test_composite_paths_idempotent_and_no_cascade_to_books(self):
        rows = [(1, 'root', 'provider', 'doc', '["root","a"]'),
                (1, 'root', 'provider', 'doc', '["root","b"]')]
        self.db.executemany('INSERT OR IGNORE INTO scan_book_routes VALUES(?,?,?,?,?)', rows + rows)
        self.db.execute('INSERT INTO scan_unresolved_routes VALUES(?,?,?,?)', ('root', 'provider', 'other', '["root"]'))
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM scan_book_routes').fetchone()[0], 2)
        self.db.execute('DELETE FROM scan_unresolved_routes')
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM books').fetchone()[0], 1)
        self.db.execute('DELETE FROM books WHERE id=1')
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM scan_book_routes').fetchone()[0], 0)
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute('INSERT INTO scan_book_routes VALUES(99, ?, ?, ?, ?)', ('root','p','d','[]'))

    def test_failure_rolls_back_row_and_route(self):
        self.db.execute("CREATE TRIGGER fail_route BEFORE INSERT ON scan_book_routes BEGIN SELECT RAISE(ABORT, 'forced'); END")
        with self.assertRaises(sqlite3.IntegrityError):
            with self.db:
                self.db.execute("INSERT INTO books VALUES(2, 'new', 'New')")
                self.db.execute("INSERT INTO scan_book_routes VALUES(2, 'root', 'p', 'doc', '[\"root\"]')")
        self.assertEqual(self.db.execute('SELECT * FROM books').fetchall(), [(1, 'legacy', 'Edited title')])
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM scan_book_routes').fetchone()[0], 0)

    def test_batch_source_wires_exact_first_and_unresolved_without_aliasing(self):
        self.assertLess(BATCH.index('val allowed = items.filter'), BATCH.index('insertScanSightings(allowed.map'))
        self.assertLess(BATCH.index('val exact = getByUri(book.uriString)'), BATCH.index('if (route != null && candidateIds.size > 1)'))
        self.assertIn('bookIdsForScanIdentities(authority, chunk)', BATCH)
        self.assertNotIn('key in knownKeys', BATCH)
        self.assertIn('insertScanBookRoute(route.copy(bookId = exact.id))', BATCH)
        self.assertIn('insertScanBookRoute(route.copy(bookId = id))', BATCH)
        self.assertIn('insertUnresolvedScanRoute(ScanUnresolvedRoute', BATCH)
        self.assertNotIn('bookId', DB.split('data class ScanUnresolvedRoute(', 1)[1].split(')', 1)[0])
        self.assertIn('DocumentsContract.buildDocumentUriUsingTree(root, sighting.documentId)', DB)
        self.assertIn('path.first() != DocumentsContract.getTreeDocumentId(root)', DB)
        self.assertIn('dao.insertScannedBatch(batch, emptySet(), authority)', SCAN)
        self.assertNotIn('deleteDocument(', SCAN)

if __name__ == '__main__': unittest.main()
