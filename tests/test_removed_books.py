"""Checks that removed books stay removed instead of returning on the next scan."""
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/example/myebookapp'
DB = (SRC / 'BookDatabase.kt').read_text()
SCANNER = (SRC / 'LibraryScanner.kt').read_text()
MAIN = (SRC / 'MainActivity.kt').read_text()
IMPORT = (SRC / 'ExternalImport.kt').read_text()
SETTINGS = (SRC / 'AppSettingsSheet.kt').read_text()
SCREEN = (SRC / 'RemovedBooksScreen.kt').read_text()

QUERIES = {name: sql for sql, name in re.findall(r'@Query\("([^"\n]*)"\) (?:suspend )?fun (\w+)\(', DB)}

_start = DB.index('CREATE TABLE IF NOT EXISTS `removed_books`')
REMOVED_DDL = DB[_start:DB.index(')"', _start) + 1]

SCHEMA = '''
CREATE TABLE books(id INTEGER PRIMARY KEY, uriString TEXT UNIQUE, fileName TEXT);
''' + REMOVED_DDL + ';\n'


class RemovedBooksSchemaTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.row_factory = sqlite3.Row
        self.db.executescript(SCHEMA)

    def test_migration_and_version(self):
        self.assertIn('RemovedBook::class', DB)
        self.assertIn('version = 10', DB)
        self.assertIn('Migration(9, 10)', DB)
        self.assertIn('M910', DB)
        self.assertIn('CREATE INDEX IF NOT EXISTS `index_removed_books_identity`', DB)

    def test_schema_has_every_entity_column(self):
        for column in ('uriString', 'identity', 'fileName', 'removedAt'):
            self.assertIn('`%s`' % column, REMOVED_DDL)
        self.assertIn('PRIMARY KEY(`uriString`)', REMOVED_DDL)

    def test_tombstones_are_listed_and_cleared(self):
        self.db.execute(
            "INSERT INTO removed_books VALUES ('content://a/tree/x/document/y', 'a|y', 'Book.epub', 100)"
        )
        self.db.execute(
            "INSERT INTO removed_books VALUES ('content://a/tree/x/document/z', 'a|z', 'Old.pdf', 200)"
        )
        rows = self.db.execute(QUERIES['getRemovedBooks']).fetchall()
        self.assertEqual([r['fileName'] for r in rows], ['Old.pdf', 'Book.epub'])
        identities = {r[0] for r in self.db.execute(QUERIES['getRemovedIdentities'])}
        self.assertEqual(identities, {'a|y', 'a|z'})
        self.db.execute(QUERIES['clearRemoved'], {'uriString': 'content://a/tree/x/document/z'})
        remaining = [r[0] for r in self.db.execute(QUERIES['getRemovedIdentities'])]
        self.assertEqual(remaining, ['a|y'])
        self.db.execute(QUERIES['clearAllRemoved'])
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM removed_books').fetchone()[0], 0)

    def test_marking_a_removed_book_twice_replaces_the_row(self):
        sql = "INSERT OR REPLACE INTO removed_books VALUES (?, ?, ?, ?)"
        self.db.execute(sql, ('content://a/tree/x/document/y', 'a|y', 'Book.epub', 100))
        self.db.execute(sql, ('content://a/tree/x/document/y', 'a|y', 'Book.epub', 300))
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM removed_books').fetchone()[0], 1)


class RemovedBooksWiringTest(unittest.TestCase):
    def test_scans_skip_removed_identities(self):
        self.assertIn('getRemovedIdentities()', SCANNER)
        self.assertIn('if (identity in removedIdentities) continue', SCANNER)

    def test_removing_a_book_writes_a_tombstone(self):
        self.assertIn('dao.removeBooks(', MAIN)
        self.assertIn('RemovedBook(', MAIN)
        self.assertIn('bookIdentity(', MAIN)
        self.assertIn('suspend fun removeBooks(', DB)
        self.assertIn('markRemoved(entries)', DB)

    def test_selection_actions_offer_removal(self):
        self.assertIn('"Remove from library"', MAIN)
        self.assertIn('bulkRemoveConfirm', MAIN)
        self.assertIn('Removed books', MAIN)

    def test_duplicate_cleanup_also_remembers_removals(self):
        self.assertIn('dao.removeBooks(', DB)
        self.assertIn('bookIdentity(it.uriString)', DB)

    def test_adding_the_file_again_clears_the_tombstone(self):
        self.assertIn('clearRemoved(entry.uriString)', IMPORT)

    def test_settings_offer_a_restore_screen(self):
        self.assertIn('Text("Removed books")', SETTINGS)
        self.assertIn('onRemovedBooks', SETTINGS)
        self.assertIn('clearRemoved(row.uriString)', SCREEN)
        self.assertIn('clearAllRemoved()', SCREEN)


if __name__ == '__main__':
    unittest.main()
