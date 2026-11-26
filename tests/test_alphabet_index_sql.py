"""SQLite regression tests for the alphabet rail's grouped initial queries."""
import re
import sqlite3
import unittest
from pathlib import Path

SOURCE = (Path(__file__).resolve().parents[1] / 'app/src/main/java/com/example/myebookapp/BookDatabase.kt').read_text()
QUERIES = {name: sql for sql, name in re.findall(r'@Query\("([^"\n]*)"\) (?:suspend )?fun (\w+)\(', SOURCE)}

PAIRS = [
    ('initialCountsAll', 'allPaged'),
    ('initialCountsFavorite', 'favoritePaged'),
    ('initialCountsStatus', 'statusPaged'),
    ('initialCountsFormat', 'formatPaged'),
    ('initialCountsCustomShelf', 'customShelfPaged'),
    ('initialCountsUnshelved', 'unshelvedPaged'),
    ('initialCountsToConvert', 'toConvertPaged'),
]

SCHEMA = '''
CREATE TABLE books(id INTEGER PRIMARY KEY, uriString TEXT, fileName TEXT,
  format TEXT, fileSize INTEGER, lastModified INTEGER, title TEXT,
  author TEXT, publishingDate TEXT, publisher TEXT, language TEXT,
  coverPath TEXT, lastLocatorJson TEXT, progressPercent REAL,
  lastOpenedAt INTEGER, metadataExtracted INTEGER, series TEXT,
  seriesIndex REAL, tags TEXT, isFavorite INTEGER, readingStatus TEXT,
  metadataEdited INTEGER, exportCoverPath TEXT, scanRootUri TEXT, scanParentId TEXT);
CREATE TABLE custom_shelves(id INTEGER PRIMARY KEY, name TEXT);
CREATE TABLE custom_shelf_books(shelfId INTEGER, bookId INTEGER,
  PRIMARY KEY(shelfId, bookId), FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE);
CREATE TABLE conversion_links(sourceBookId INTEGER PRIMARY KEY,
  outputBookId INTEGER, FOREIGN KEY(sourceBookId) REFERENCES books(id) ON DELETE CASCADE,
  FOREIGN KEY(outputBookId) REFERENCES books(id) ON DELETE CASCADE);
'''

# id, title, author, format, isFavorite, readingStatus
BOOKS = [
    (1, 'Apple', 'Zed', 'EPUB', 1, 'READING'),
    (2, 'apple pie', 'alice', 'EPUB', 0, 'NEW'),
    (3, 'Banana', 'Bob', 'MOBI', 1, 'READING'),
    (4, 'Éclair', 'Émile', 'EPUB', 0, 'FINISHED'),
    (5, '42 things', 'Doug', 'PDF', 1, 'TBR'),
    (6, '-dash', 'Eve', 'CBZ', 0, 'READING'),
    (7, '', '', 'EPUB', 0, 'NEW'),
    (8, 'Hidden', 'Hank', 'EPUB', 0, 'READING'),
]


def nocase(text):
    return ''.join(chr(ord(c) + 32) if 'A' <= c <= 'Z' else c for c in text)


class AlphabetIndexSqlTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.row_factory = sqlite3.Row
        self.db.execute('PRAGMA foreign_keys = ON')
        self.db.executescript(SCHEMA)
        for book_id, title, author, fmt, favorite, status in BOOKS:
            self.db.execute(
                'INSERT INTO books(id,uriString,fileName,format,fileSize,lastModified,title,author,'
                'lastOpenedAt,metadataExtracted,isFavorite,readingStatus) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)',
                (book_id, str(book_id), str(book_id), fmt, 100, book_id, title, author,
                 book_id, 1, favorite, status))
        # Book 8 is a successfully converted original: hidden unless revealed.
        self.db.execute('INSERT INTO conversion_links VALUES(8, 2)')
        self.db.executemany('INSERT INTO custom_shelf_books VALUES(?,?)',
                            [(1, 1), (1, 2), (1, 3), (1, 8), (2, 1)])

    def tearDown(self):
        self.db.close()

    def query(self, name, **kwargs):
        args = dict(showConvertedOriginals=0, byAuthor=0, status='READING',
                    shelfId=1, sort='title', reverse=0)
        args.update(kwargs)
        sql = QUERIES[name]
        # Room expands list parameters before SQLite sees them; do the same here.
        if ':formats' in sql:
            sql = sql.replace('IN (:formats)', "IN ('CBZ', 'CBR', 'PDF')")
        return self.db.execute(sql, args).fetchall()

    def test_grouped_queries_exist(self):
        for count_name, paged_name in PAIRS:
            with self.subTest(query=count_name):
                self.assertIn(count_name, QUERIES)
                self.assertIn(paged_name, QUERIES)
                self.assertIn(':byAuthor', QUERIES[count_name])
                self.assertIn(':showConvertedOriginals', QUERIES[count_name])

    def test_group_positions_match_the_paged_list(self):
        for count_name, paged_name in PAIRS:
            for by_author in (0, 1):
                for show in (0, 1):
                    sort = 'author' if by_author else 'title'
                    with self.subTest(query=count_name, byAuthor=by_author, showConvertedOriginals=show):
                        groups = self.query(count_name, byAuthor=by_author,
                                            showConvertedOriginals=show, sort=sort)
                        rows = self.query(paged_name, showConvertedOriginals=show, sort=sort)
                        desc = self.query(paged_name, showConvertedOriginals=show,
                                          sort=sort, reverse=1)

                        self.assertTrue(all(g['initial'] is not None for g in groups))
                        positions, running = [], 0
                        for group in groups:
                            positions.append((group['initial'], running, group['bookCount']))
                            running += group['bookCount']
                        self.assertEqual(len(rows), running, (count_name, by_author, show))

                        for initial, start, count in positions:
                            block = rows[start:start + count]
                            self.assertEqual(count, len(block))
                            for row in block:
                                self.assertEqual(nocase(initial), nocase(row[sort][:1]),
                                                 (count_name, initial, row[sort]))

                        total = len(rows)
                        for initial, start, count in positions:
                            low = total - (start + count)
                            block = desc[low:low + count]
                            self.assertEqual(count, len(block))
                            for row in block:
                                self.assertEqual(nocase(initial), nocase(row[sort][:1]),
                                                 ('reversed', count_name, initial, row[sort]))

    def test_group_order_is_ascending(self):
        for count_name, _ in PAIRS:
            for by_author in (0, 1):
                with self.subTest(query=count_name, byAuthor=by_author):
                    keys = [nocase(g['initial']) for g in self.query(count_name, byAuthor=by_author)]
                    self.assertEqual(sorted(keys), keys)

    def test_custom_shelf_join_does_not_duplicate(self):
        groups = self.query('initialCountsCustomShelf', shelfId=1, showConvertedOriginals=1)
        self.assertEqual(4, sum(g['bookCount'] for g in groups))
        groups = self.query('initialCountsCustomShelf', shelfId=2, showConvertedOriginals=1)
        self.assertEqual(1, sum(g['bookCount'] for g in groups))

    def test_to_convert_groups_only_unconverted_formats(self):
        groups = self.query('initialCountsToConvert', byAuthor=0)
        self.assertEqual([('B', 1)], [(g['initial'], g['bookCount']) for g in groups])


if __name__ == '__main__':
    unittest.main()
