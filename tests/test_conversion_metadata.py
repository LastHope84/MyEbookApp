"""SQLite shelf-copy tests plus source contracts; not Android/Room integration tests."""
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DAO = (ROOT / 'app/src/main/java/com/example/myebookapp/BookDatabase.kt').read_text()
MAIN = (ROOT / 'app/src/main/java/com/example/myebookapp/MainActivity.kt').read_text()
COPY = re.search(r'@Query\("([^"\n]+)"\)\s+suspend fun copyCustomShelfMemberships', DAO).group(1)

class ConversionMetadataTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.execute('PRAGMA foreign_keys=ON')
        self.db.executescript('''
          CREATE TABLE books(id INTEGER PRIMARY KEY, uriString TEXT UNIQUE, format TEXT,
            isFavorite INTEGER, readingStatus TEXT, title TEXT);
          CREATE TABLE custom_shelves(id INTEGER PRIMARY KEY);
          CREATE TABLE custom_shelf_books(shelfId INTEGER REFERENCES custom_shelves(id),
            bookId INTEGER REFERENCES books(id), PRIMARY KEY(shelfId, bookId));
          CREATE TABLE conversion_links(sourceBookId INTEGER PRIMARY KEY REFERENCES books(id),
            outputBookId INTEGER REFERENCES books(id));
          INSERT INTO books VALUES(1, 'source', 'MOBI', 1, 'READING', 'original');
          INSERT INTO custom_shelves VALUES(10), (11), (12);
          INSERT INTO custom_shelf_books VALUES(10, 1), (11, 1);
        ''')
        self.db.commit()

    def tearDown(self):
        self.db.close()

    def simulate_transaction(self, output='output', fail=False):
        # Mirrors the Room DAO control flow; actual INSERT SELECT SQL is from the DAO.
        with self.db:
            source = self.db.execute('SELECT id,uriString,format,isFavorite,readingStatus FROM books WHERE id=1').fetchone()
            cur = self.db.execute('INSERT OR IGNORE INTO books(uriString,format,isFavorite,readingStatus,title) VALUES(?,\'EPUB\',?,?,\'parsed\')', (output, source[3], source[4]))
            out = self.db.execute('SELECT id FROM books WHERE uriString=?', (output,)).fetchone()[0]
            if cur.rowcount:
                self.db.execute(COPY, dict(sourceBookId=source[0], outputBookId=out))
            self.db.execute('INSERT INTO conversion_links VALUES(?, ?)', (1, 999 if fail else out))
            return out

    def test_new_output_copies_user_shelves_without_moving_original(self):
        out = self.simulate_transaction()
        self.assertEqual((1, 'READING'), self.db.execute('SELECT isFavorite,readingStatus FROM books WHERE id=?', (out,)).fetchone())
        self.assertEqual([(10, 1), (10, out), (11, 1), (11, out)], self.db.execute('SELECT * FROM custom_shelf_books ORDER BY shelfId, bookId').fetchall())
        self.assertEqual((1, out), self.db.execute('SELECT * FROM conversion_links').fetchone())
        self.assertEqual(('original',), self.db.execute('SELECT title FROM books WHERE id=1').fetchone())

    def test_existing_output_is_not_modified_or_merged(self):
        self.db.execute("INSERT INTO books VALUES(2, 'output', 'EPUB', 0, 'FINISHED', 'user title')")
        self.db.execute('INSERT INTO custom_shelf_books VALUES(12, 2)')
        self.db.commit()
        self.assertEqual(2, self.simulate_transaction())
        self.assertEqual((0, 'FINISHED', 'user title'), self.db.execute('SELECT isFavorite,readingStatus,title FROM books WHERE id=2').fetchone())
        self.assertEqual([(12,)], self.db.execute('SELECT shelfId FROM custom_shelf_books WHERE bookId=2').fetchall())

    def test_link_failure_rolls_back_new_output_and_shelves(self):
        with self.assertRaises(sqlite3.IntegrityError): self.simulate_transaction(fail=True)
        self.assertEqual(1, self.db.execute('SELECT COUNT(*) FROM books').fetchone()[0])
        self.assertEqual(2, self.db.execute('SELECT COUNT(*) FROM custom_shelf_books').fetchone()[0])
        self.assertEqual(0, self.db.execute('SELECT COUNT(*) FROM conversion_links').fetchone()[0])

    def test_kotlin_transaction_and_selection_guards_present(self):
        section = DAO.split('@Transaction suspend fun indexConvertedOutput', 1)[1].split('@Query("SELECT * FROM custom_shelves', 1)[0]
        for item in ('source.uriString == sourceUri', 'sourceUri != entry.uriString', 'insertIgnore(entry.copy(isFavorite = source.isFavorite, readingStatus = source.readingStatus))', 'if (insertedId != -1L)', 'copyCustomShelfMemberships(source.id, output.id)', 'recordSuccessfulConversion(source.id, output.id)'):
            self.assertIn(item, section)
        conversion = MAIN.split('fun convertBooks(', 1)[1].split('fun convertBook(', 1)[0]
        self.assertIn('cancelSelectAll(); selIds = emptySet(); selMode = false', conversion)
        self.assertIn('dao.indexConvertedOutput(book.id, book.uriString, entry)', conversion)
        self.assertIn('java.util.UUID.randomUUID()', conversion)
        tile = MAIN.split('val b = paged[i];', 1)[1].split('BookPlaceholderTile()', 1)[0]
        self.assertEqual(3, tile.count('!bulkSaving && !converting'))

if __name__ == '__main__':
    unittest.main()
