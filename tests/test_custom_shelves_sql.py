"""SQLite-level regression tests; Room migration validation still requires Android tests."""
import pathlib
import re
import sqlite3
import unittest

SOURCE = (pathlib.Path(__file__).resolve().parents[1] / "app/src/main/java/com/example/myebookapp/BookDatabase.kt").read_text()
MIGRATION_34 = SOURCE.split('internal val M34', 1)[1].split('internal val M45', 1)[0]
MIGRATION_45 = SOURCE.split('internal val M45', 1)[1].split('internal val M56', 1)[0]
STATEMENTS_34 = re.findall(r'db\.execSQL\("([^"]+)"\)', MIGRATION_34)
STATEMENTS_45 = re.findall(r'db\.execSQL\("([^"]+)"\)', MIGRATION_45)


def query(method):
    match = re.search(r'@Query\("([^"]+)"\) fun ' + method + r'\(', SOURCE)
    if not match:
        raise AssertionError(f"Query {method} not found")
    return match.group(1)


class CustomShelvesSqlTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.execute('PRAGMA foreign_keys=ON')
        self.db.execute('CREATE TABLE books (id INTEGER PRIMARY KEY, uriString TEXT UNIQUE, title TEXT, author TEXT, publisher TEXT, series TEXT, tags TEXT, isFavorite INTEGER, readingStatus TEXT, progressPercent REAL, lastLocatorJson TEXT, lastOpenedAt INTEGER)')
        self.db.execute('CREATE VIRTUAL TABLE books_fts USING fts4(content=`books`, author, publisher, series, tags, title)')
        self.db.executemany('INSERT INTO books VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)', [
            (1, 'uri:1', 'Alpha', 'Ada', 'Pub', 'Saga', 'magic', 1, 'READING', 0.75, 'locator', 100),
            (2, 'uri:2', 'Beta', 'Bob', 'Pub', None, None, 0, 'FINISHED', 1.0, 'other', 200)])
        self.db.execute("INSERT INTO books_fts(books_fts) VALUES ('rebuild')")
        self.before = self.db.execute('SELECT * FROM books ORDER BY id').fetchall()
        self.assertEqual(len(STATEMENTS_34), 4)
        self.assertEqual(len(STATEMENTS_45), 2)
        for sql in STATEMENTS_34 + STATEMENTS_45:
            self.db.execute(sql)

    def tearDown(self):
        self.db.close()

    def test_migration_preserves_books(self):
        self.assertEqual(self.db.execute('SELECT * FROM books ORDER BY id').fetchall(), self.before)
        self.assertEqual(self.db.execute("SELECT rowid FROM books_fts WHERE books_fts MATCH 'Alpha*'").fetchall(), [(1,)])

    def test_membership_search_and_independence(self):
        self.db.executemany('INSERT INTO custom_shelves(id, name) VALUES (?, ?)', [(1, 'ew'), (2, 'Loved')])
        self.db.executemany('INSERT INTO custom_shelf_books VALUES (?, ?)', [(1, 1), (2, 1), (1, 2)])
        self.assertEqual([r[0] for r in self.db.execute(query('customShelfPaged'), {'shelfId': 1, 'showConvertedOriginals': 0, 'sort': 'title', 'reverse': 0})], [1, 2])
        self.assertEqual([r[0] for r in self.db.execute(query('searchCustomShelfPaged'), {'shelfId': 1, 'q': 'Alpha*', 'showConvertedOriginals': 0, 'sort': 'title', 'reverse': 0})], [1])
        self.assertEqual(list(self.db.execute(query('searchCustomShelfPaged'), {'shelfId': 2, 'q': 'Beta*', 'showConvertedOriginals': 0, 'sort': 'title', 'reverse': 0})), [])
        self.db.execute('DELETE FROM custom_shelf_books WHERE shelfId=1 AND bookId=1')
        self.assertEqual(self.db.execute('SELECT * FROM custom_shelf_books WHERE bookId=1').fetchall(), [(2, 1)])
        self.assertEqual(self.db.execute('SELECT * FROM books ORDER BY id').fetchall(), self.before)
        self.db.execute("INSERT OR IGNORE INTO books VALUES (3, 'uri:1', 'replacement', '', '', NULL, NULL, 0, 'NEW', 0, NULL, 0)")
        self.assertEqual(self.db.execute('SELECT * FROM books ORDER BY id').fetchall(), self.before)
        self.assertEqual(self.db.execute('SELECT * FROM custom_shelf_books WHERE bookId=1').fetchall(), [(2, 1)])

    def test_unique_names_memberships_and_cascades(self):
        self.db.executemany('INSERT INTO custom_shelves(id, name) VALUES (?, ?)', [(1, 'ew'), (2, 'Other')])
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO custom_shelves(name) VALUES ('EW')")
        self.db.executemany('INSERT INTO custom_shelf_books VALUES (?, ?)', [(1, 1), (2, 1), (2, 2)])
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute('INSERT INTO custom_shelf_books VALUES (1, 1)')
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute('INSERT INTO custom_shelf_books VALUES (1, 999)')
        self.db.execute('DELETE FROM custom_shelves WHERE id=1')
        self.assertEqual(self.db.execute('SELECT * FROM books ORDER BY id').fetchall(), self.before)
        self.assertEqual(self.db.execute('SELECT * FROM custom_shelf_books ORDER BY bookId').fetchall(), [(2, 1), (2, 2)])
        self.db.execute('DELETE FROM books WHERE id=1')
        self.assertEqual(self.db.execute('SELECT * FROM custom_shelf_books').fetchall(), [(2, 2)])


if __name__ == '__main__':
    unittest.main()
