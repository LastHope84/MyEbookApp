"""SQLite regression tests exercising the actual Room DAO SQL and 4->5 migration."""
import re
import sqlite3
import unittest
from pathlib import Path

SOURCE = (Path(__file__).resolve().parents[1] / 'app/src/main/java/com/example/myebookapp/BookDatabase.kt').read_text()
QUERIES = {name: sql for sql, name in re.findall(r'@Query\("([^"\n]*)"\) (?:suspend )?fun (\w+)\(', SOURCE)}
MIGRATION = SOURCE.split('internal val M45 =', 1)[1].split('fun getInstance', 1)[0]


class VisibilityTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.execute("CREATE TABLE hidden_scan_books(bookId INTEGER PRIMARY KEY, rootUri TEXT NOT NULL)")
        self.db.execute('PRAGMA foreign_keys = ON')
        self.db.executescript('''
          CREATE TABLE books(id INTEGER PRIMARY KEY, uriString TEXT, fileName TEXT,
            format TEXT, fileSize INTEGER, lastModified INTEGER, title TEXT,
            author TEXT, publishingDate TEXT, publisher TEXT, language TEXT,
            coverPath TEXT, lastLocatorJson TEXT, progressPercent REAL,
            lastOpenedAt INTEGER, metadataExtracted INTEGER, series TEXT,
            seriesIndex REAL, tags TEXT, isFavorite INTEGER, readingStatus TEXT);
          CREATE VIRTUAL TABLE books_fts USING fts4(author, publisher, series, tags, title);
          CREATE TABLE custom_shelves(id INTEGER PRIMARY KEY, name TEXT);
          CREATE TABLE custom_shelf_books(shelfId INTEGER, bookId INTEGER,
            PRIMARY KEY(shelfId, bookId), FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE);
          INSERT INTO custom_shelves VALUES(1, 'shelf'), (2, 'empty');
        ''')
        # Upgrade an existing library: migration must not alter old book rows.
        self.add(1, 'MOBI')
        self.add(2, 'EPUB')
        self.add(3, 'AZW3')
        for stmt in re.findall(r'db\.execSQL\("([^"\n]+)"\)', MIGRATION):
            self.db.execute(stmt)
        self.assertEqual(3, self.db.execute('SELECT COUNT(*) FROM books').fetchone()[0])
        self.assertEqual(0, self.db.execute('SELECT COUNT(*) FROM conversion_links').fetchone()[0])
        self.db.execute('INSERT INTO conversion_links VALUES(1, 2)')
        self.db.executemany('INSERT INTO custom_shelf_books VALUES(1, ?)', [(1,), (2,), (3,)])

    def tearDown(self):
        self.db.close()

    def add(self, id, fmt):
        self.db.execute('INSERT INTO books(id,uriString,fileName,format,fileSize,title,author,publisher,lastOpenedAt,metadataExtracted,isFavorite,readingStatus) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)',
                        (id, str(id), str(id), fmt, 100, 'shared title', 'author', 'publisher', id, 0, 1, 'READING'))
        self.db.execute('INSERT INTO books_fts(rowid,author,publisher,title) VALUES(?,?,?,?)', (id, 'author', 'publisher', 'shared title'))

    def run_query(self, name, show=False, **kwargs):
        args = dict(showConvertedOriginals=int(show), q='shared*', status='READING', shelfId=1)
        args.update(kwargs)
        return self.db.execute(QUERIES[name], args).fetchall()

    def test_all_library_queries_filter_and_reveal(self):
        # Run every actual parameterized visibility query, including the FTS variants.
        covered = [name for name, sql in QUERIES.items() if ':showConvertedOriginals' in sql]
        self.assertGreaterEqual(len(covered), 36)
        for name in covered:
            with self.subTest(name=name):
                hidden, shown = self.run_query(name), self.run_query(name, True)
                if name == 'observeCustomShelvesWithCounts':
                    self.assertEqual([('empty', 0), ('shelf', 2)], [(r[1], r[2]) for r in hidden])
                    self.assertEqual([('empty', 0), ('shelf', 3)], [(r[1], r[2]) for r in shown])
                elif name in ('observeTotalCount', 'observeFavoriteCount', 'observeStatusCount'):
                    self.assertEqual((2,), hidden[0]); self.assertEqual((3,), shown[0])
                elif name == 'observeToConvertCount':
                    self.assertEqual((1,), hidden[0]); self.assertEqual((2,), shown[0])
                elif name == 'observePendingMetadataCount':
                    self.assertEqual(hidden, shown)
                elif name in ('observeLastOpened', 'getLastOpenedOnce'):
                    self.assertEqual(3, hidden[0][0]); self.assertEqual(3, shown[0][0])
                else:
                    self.assertNotIn(1, [r[0] for r in hidden])
                    self.assertIn(1, [r[0] for r in shown])

    def test_output_delete_cascades_link_and_exposes_original(self):
        self.db.execute('DELETE FROM books WHERE id=2')
        self.assertEqual([], self.db.execute('SELECT * FROM conversion_links').fetchall())
        self.assertIn(1, [r[0] for r in self.run_query('getAllIds')])
        self.assertEqual((1,), self.db.execute('SELECT id FROM books WHERE id=1').fetchone())

    def test_source_delete_never_deletes_output(self):
        self.db.execute('DELETE FROM books WHERE id=1')
        self.assertEqual([], self.db.execute('SELECT * FROM conversion_links').fetchall())
        self.assertEqual((2,), self.db.execute('SELECT id FROM books WHERE id=2').fetchone())

    def test_unlink_reveals_without_deleting(self):
        self.db.execute(QUERIES['removeConversionLink'], {'sourceBookId': 1})
        self.assertEqual({1, 2, 3}, {r[0] for r in self.run_query('getAllIds')})

    def test_recently_added_limit_after_filter_and_search(self):
        # 51 extra newer items, one hidden; visible top 50 includes ID 4.
        for n in range(4, 55): self.add(n, 'EPUB')
        self.db.execute("UPDATE books SET format='MOBI' WHERE id=54")
        self.db.execute('INSERT INTO conversion_links VALUES(54, 2)')
        for name in ('recentlyAddedPaged', 'searchRecentlyAddedPaged', 'getRecentlyAddedIds', 'searchRecentlyAddedIds'):
            with self.subTest(name=name):
                hidden = [r[0] for r in self.run_query(name)]
                shown = [r[0] for r in self.run_query(name, True)]
                self.assertEqual(50, len(hidden)); self.assertEqual(50, len(shown))
                self.assertEqual(4, hidden[-1]); self.assertEqual(5, shown[-1])
                self.assertNotIn(54, hidden); self.assertIn(54, shown)

    def test_foreign_keys_reject_missing_endpoint(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute('INSERT INTO conversion_links VALUES(3, 999)')
        self.assertIn(3, [r[0] for r in self.run_query('getAllIds')])


if __name__ == '__main__':
    unittest.main()
