"""SQLite-model and source checks; these do not execute Room/Compose code."""
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[1]
DAO = (ROOT / 'app/src/main/java/com/example/myebookapp/BookDatabase.kt').read_text()
UI = (ROOT / 'app/src/main/java/com/example/myebookapp/MainActivity.kt').read_text()
CHUNK = int(re.search(r'BULK_ID_CHUNK_SIZE = (\d+)', DAO).group(1))


def query(name):
    return re.search(r'@Query\("([^"]+)"\) suspend fun ' + name + r'\(', DAO).group(1)


def sql_chunks(con, name, ids, params=None):
    sql = query(name)
    for start in range(0, len(ids), CHUNK):
        batch = ids[start:start + CHUNK]
        bound = dict(params or {}, **{f'id{i}': x for i, x in enumerate(batch)})
        expanded = sql.replace(':ids', ','.join(f':id{i}' for i in range(len(batch))))
        yield con.execute(expanded, bound)


class BulkSafety(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.execute('PRAGMA foreign_keys=ON')
        self.db.setlimit(sqlite3.SQLITE_LIMIT_VARIABLE_NUMBER, 999)
        self.db.executescript('''CREATE TABLE books(id INTEGER PRIMARY KEY, isFavorite INTEGER NOT NULL DEFAULT 0, readingStatus TEXT NOT NULL DEFAULT 'NEW');
          CREATE TABLE custom_shelves(id INTEGER PRIMARY KEY);
          CREATE TABLE custom_shelf_books(shelfId INTEGER REFERENCES custom_shelves(id), bookId INTEGER REFERENCES books(id), PRIMARY KEY(shelfId, bookId));
          INSERT INTO custom_shelves VALUES (1);''')
        self.db.executemany('INSERT INTO books(id) VALUES(?)', [(i,) for i in range(1, 1502)])
        self.db.commit()
        self.ids = list(range(1, 1502))

    def tearDown(self):
        self.db.close()

    def test_large_chunked_reads_updates_and_removes(self):
        self.assertLessEqual(CHUNK + 2, 999)
        with self.db:
            self.assertEqual(sum(len(c.fetchall()) for c in sql_chunks(self.db, 'getByIdsChunk', self.ids)), 1501)
            self.assertEqual(sum(c.rowcount for c in sql_chunks(self.db, 'setFavoriteForIdsChunk', self.ids, {'favorite': 1})), 1501)
            self.assertEqual(sum(c.rowcount for c in sql_chunks(self.db, 'setReadingStatusForIdsChunk', self.ids, {'status': 'READING'})), 1501)
            self.db.executemany('INSERT INTO custom_shelf_books VALUES(1, ?)', [(i,) for i in self.ids])
            self.assertEqual(sum(c.rowcount for c in sql_chunks(self.db, 'removeBooksFromCustomShelfChunk', self.ids, {'shelfId': 1})), 1501)
        self.assertEqual(self.db.execute("SELECT count(*) FROM books WHERE isFavorite=1 AND readingStatus='READING'").fetchone()[0], 1501)

    def test_transaction_rolls_back_on_late_membership_failure(self):
        with self.assertRaises(sqlite3.IntegrityError):
            with self.db:
                self.db.executemany('INSERT OR IGNORE INTO custom_shelf_books VALUES(1, ?)', [(i,) for i in self.ids] + [(999999,)])
        self.assertEqual(self.db.execute('SELECT count(*) FROM custom_shelf_books').fetchone()[0], 0)

    def test_transaction_rolls_back_late_chunk_failure(self):
        self.db.executescript("CREATE TRIGGER fail_late BEFORE UPDATE ON books WHEN new.id=1001 BEGIN SELECT RAISE(ABORT, 'injected'); END;")
        with self.assertRaises(sqlite3.IntegrityError):
            with self.db:
                list(sql_chunks(self.db, 'setFavoriteForIdsChunk', self.ids, {'favorite': 1}))
        self.assertEqual(self.db.execute('SELECT count(*) FROM books WHERE isFavorite=1').fetchone()[0], 0)

    def test_transaction_rolls_back_late_custom_shelf_removal(self):
        self.db.executemany('INSERT INTO custom_shelf_books VALUES(1, ?)', [(i,) for i in self.ids])
        self.db.commit()
        self.db.executescript("CREATE TRIGGER fail_remove BEFORE DELETE ON custom_shelf_books WHEN old.bookId=1001 BEGIN SELECT RAISE(ABORT, 'injected'); END;")
        with self.assertRaises(sqlite3.IntegrityError):
            with self.db:
                list(sql_chunks(self.db, 'removeBooksFromCustomShelfChunk', self.ids, {'shelfId': 1}))
        self.assertEqual(self.db.execute('SELECT count(*) FROM custom_shelf_books').fetchone()[0], 1501)

    def test_conversion_checks_every_selected_book_before_filtering(self):
        action = UI.split('showBulkActions.let', 1)[1].split('showBulkMove.let', 1)[0]
        load = action.index('val selected=dao.getByIds(ids)')
        check = action.index('check(selected.isNotEmpty() && selected.size == ids.size)')
        filter_epubs = action.index('selected.filter { it.format != "EPUB" }')
        convert = action.index('convertBooks(books, false)')
        self.assertLess(load, check)
        self.assertLess(check, filter_epubs)
        self.assertLess(filter_epubs, convert)
        self.assertIn('Some selected books are no longer in the library. No books were converted.', action)
        self.assertIn('e.localizedMessage', action)
        self.assertIn('finally { bulkSaving=false }', action)

    def test_dao_and_ui_wiring_guards(self):
        for method in ('getByIds', 'setFavoriteForIds', 'setReadingStatusForIds', 'addBooksToCustomShelf', 'removeBooksFromCustomShelf', 'removeBooksFromAllCustomShelves', 'deleteByIds'):
            self.assertRegex(DAO, r'@Transaction suspend fun ' + method + r'\(')
        self.assertIn('ids.distinct().chunked(BULK_ID_CHUNK_SIZE)', DAO)
        self.assertIn('generation == selectAllGeneration && selMode', UI)
        self.assertIn('cancelSelectAll(); dbQry = qry', UI)
        self.assertIn('Could not select books on this shelf.', UI)
        self.assertIn('Could not update reading shelf.', UI)
        self.assertIn('dao.addBooksToCustomShelf(shelf.id, ids)', UI)
        self.assertIn('dao.removeBooksFromCustomShelf(shelf.id, ids)', UI)

if __name__ == '__main__':
    unittest.main()
