"""Source-level and SQLite checks for saved quotes."""
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/example/myebookapp'
DB = (SRC / 'BookDatabase.kt').read_text()
BACKUP = (SRC / 'BackupRestore.kt').read_text()
SHEET = (SRC / 'QuotesSheet.kt').read_text()
READER = (SRC / 'ReaderActivity.kt').read_text()
PAGE_READER = (SRC / 'PageReaderActivity.kt').read_text()
COMPONENTS = (SRC / 'LibraryComponents.kt').read_text()
GUIDE = (SRC / 'UserGuideScreen.kt').read_text()
DOC = (ROOT / 'docs/USER_GUIDE.md').read_text()

QUERIES = {name: sql for sql, name in re.findall(r'@Query\("([^"\n]*)"\) (?:suspend )?fun (\w+)\(', DB)}

_start = DB.index('CREATE TABLE IF NOT EXISTS `quotes`')
QUOTES_DDL = DB[_start:DB.index(')"', _start) + 1]
ENTITY = re.search(r'data class QuoteEntity\((.*?)\n\)', DB, re.S).group(1)
ENTITY_COLUMNS = re.findall(r'val (\w+):', ENTITY)

SCHEMA = '''
CREATE TABLE books(id INTEGER PRIMARY KEY, title TEXT, author TEXT);
''' + QUOTES_DDL + ';\n'


class QuotesSchemaTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.row_factory = sqlite3.Row
        self.db.execute('PRAGMA foreign_keys = ON')
        self.db.executescript(SCHEMA)

    def test_migration_creates_every_entity_column(self):
        self.assertEqual(
            ENTITY_COLUMNS,
            ['id', 'bookId', 'text', 'locatorJson', 'pageIndex', 'label', 'createdAt']
        )
        for column in ENTITY_COLUMNS:
            self.assertIn('`%s`' % column, QUOTES_DDL)
        self.assertIn('ON DELETE CASCADE', QUOTES_DDL)

    def test_database_version_and_migration_registered(self):
        self.assertIn('QuoteEntity::class', DB)
        self.assertIn('version = 9', DB)
        self.assertIn('Migration(8, 9)', DB)
        self.assertIn('M89', DB)
        self.assertIn('addMigrations(M12, M23, M34, M45, M56, M67, M78, M89)', DB)
        self.assertIn('CREATE INDEX IF NOT EXISTS `index_quotes_bookId`', DB)

    def test_deleting_a_book_removes_its_quotes(self):
        self.db.execute("INSERT INTO books(id, title, author) VALUES (1, 'A', 'X')")
        self.db.execute("INSERT INTO books(id, title, author) VALUES (2, 'B', 'Y')")
        self.db.execute("INSERT INTO quotes(bookId, text, createdAt) VALUES (1, 'one', 10)")
        self.db.execute("INSERT INTO quotes(bookId, text, createdAt) VALUES (1, 'two', 20)")
        self.db.execute("INSERT INTO quotes(bookId, text, createdAt) VALUES (2, 'three', 30)")
        self.db.execute('DELETE FROM books WHERE id = 1')
        rows = self.db.execute('SELECT text FROM quotes ORDER BY id').fetchall()
        self.assertEqual([r['text'] for r in rows], ['three'])

    def test_quote_queries_use_the_entity_table(self):
        self.assertIn('FROM quotes WHERE bookId = :bookId', QUERIES['observeQuotes'])
        self.assertIn('ORDER BY createdAt DESC, id DESC', QUERIES['observeQuotes'])
        self.assertIn('COUNT(*) FROM quotes', QUERIES['observeQuoteCount'])
        self.assertIn('DELETE FROM quotes WHERE id = :id', QUERIES['deleteQuote'])

    def test_quotes_are_ordered_newest_first(self):
        self.db.execute("INSERT INTO books(id, title, author) VALUES (1, 'A', 'X')")
        for text, created in [('old', 100), ('new', 300), ('middle', 200)]:
            self.db.execute(
                'INSERT INTO quotes(bookId, text, createdAt) VALUES (1, ?, ?)', (text, created)
            )
        rows = self.db.execute(QUERIES['observeQuotes'], {'bookId': 1}).fetchall()
        self.assertEqual([r['text'] for r in rows], ['new', 'middle', 'old'])
        count = self.db.execute(QUERIES['observeQuoteCount'], {'bookId': 1}).fetchone()[0]
        self.assertEqual(count, 3)


class QuotesWiringTest(unittest.TestCase):
    def test_epub_reader_saves_a_quote_and_reopens_it(self):
        self.assertIn('"Save quote"', READER)
        self.assertIn('saveSelectionQuote', READER)
        self.assertIn('locator.toJSON().toString()', READER)
        self.assertIn('"quote_locator"', READER)
        self.assertIn('"quote_locator"', COMPONENTS)

    def test_pdf_reader_saves_a_quote_and_reopens_it(self):
        self.assertIn('Text("Quote")', PAGE_READER)
        self.assertIn('onSaveQuote', PAGE_READER)
        self.assertIn('"book_page"', PAGE_READER)
        self.assertIn('"book_page"', COMPONENTS)

    def test_quotes_sheet_offers_read_copy_share_delete(self):
        for label in ('"Copy"', '"Share"', '"Open"', '"Delete"'):
            self.assertIn(label, SHEET)
        self.assertIn('onOpen', SHEET)
        self.assertIn('Quotes (', (SRC / 'BookDetailsSheet.kt').read_text())

    def test_quotes_travel_in_backups(self):
        self.assertIn('getAllQuotesOnce', BACKUP)
        self.assertIn('put("quotes"', BACKUP)
        self.assertIn('deleteAllQuotes', BACKUP)
        self.assertIn('restoreQuotes', BACKUP)
        self.assertIn('it.bookId in bookIds', BACKUP)

    def test_guides_mention_saved_quotes(self):
        for text in (GUIDE, DOC):
            self.assertIn('Save quote', text)
            self.assertIn('Quotes', text)


if __name__ == '__main__':
    unittest.main()
