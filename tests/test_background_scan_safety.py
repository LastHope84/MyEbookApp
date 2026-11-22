"""Background-scan safety regressions (stdlib unittest; no Android required).

SQLiteModelTests exercise a *hypothetical* durable scan design, not the app's
worker, Room schema, SAF provider or lifecycle. SourceSqlTests execute two
current Room SQL queries on a minimal SQLite fixture and check a few known
source-level guards. Passing these tests does NOT establish that the app has
persistent pause/resume, background execution, or safe restart behaviour.
Android/instrumented process-death and provider tests are still required.
"""
from pathlib import Path
import re
import sqlite3
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/example/myebookapp'


def dao_query(method):
    source = (SRC / 'BookDatabase.kt').read_text()
    match = re.search(r'@Query\("([^"\n]+)"\)\s*(?:\n\s*)?suspend\s+fun\s+'
                      + re.escape(method) + r'\s*\(', source)
    if not match:
        raise AssertionError(f'Cannot locate SQL for {method}; inspect replacement safety path')
    return match.group(1)


def metadata_args(title='Parsed title'):
    return dict(id=1, t=title, a='Parsed author', d='2026', p='Parsed publisher',
                l='en', c='parsed-cover', s='Parsed series', si=2.0, tags='parsed')


class SourceSqlTests(unittest.TestCase):
    """Real SQL extracted from current DAO, executed against a small fixture."""
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.execute('PRAGMA foreign_keys=ON')
        self.db.executescript('''
          CREATE TABLE books(id INTEGER PRIMARY KEY, uriString TEXT UNIQUE,
            title TEXT, author TEXT, publishingDate TEXT, publisher TEXT,
            language TEXT, coverPath TEXT, series TEXT, seriesIndex REAL,
            tags TEXT, metadataExtracted INTEGER, isFavorite INTEGER,
            readingStatus TEXT, progressPercent REAL, lastLocatorJson TEXT);
          CREATE TABLE custom_shelves(id INTEGER PRIMARY KEY, name TEXT);
          CREATE TABLE custom_shelf_books(shelfId INTEGER REFERENCES custom_shelves(id),
            bookId INTEGER REFERENCES books(id) ON DELETE CASCADE,
            PRIMARY KEY(shelfId, bookId));
          INSERT INTO books VALUES(1, 'content://book/1', 'placeholder', 'unknown',
            'unknown', 'unknown', 'unknown', NULL, NULL, NULL, NULL, 0,
            1, 'READING', 0.7, 'locator');
          INSERT INTO custom_shelves VALUES(1, 'Favourites');
          INSERT INTO custom_shelf_books VALUES(1, 1);
        ''')

    def tearDown(self):
        self.db.close()

    def snapshot(self):
        return (self.db.execute('SELECT * FROM books ORDER BY id').fetchall(),
                self.db.execute('SELECT * FROM custom_shelf_books').fetchall())

    def test_manual_edit_wins_over_stale_extraction(self):
        # Extraction may finish after the user has edited the same pending book.
        edit = metadata_args('User title')
        edit.update(a='User author', c='user-cover', s='User series',
                    tags='user tags', changeCover=1)
        self.db.execute(dao_query('saveEditedMetadata'), edit)
        before = self.snapshot()
        self.db.execute(dao_query('updatePendingMetadata'), metadata_args())
        self.assertEqual(self.snapshot(), before,
                         'A late extraction must not overwrite manual edits or shelf state')

    def test_extraction_and_replay_preserve_shelves_and_reading_state(self):
        self.db.execute(dao_query('updatePendingMetadata'), metadata_args())
        self.assertEqual(self.db.execute('SELECT title, metadataExtracted, isFavorite, '
                         'readingStatus, progressPercent, lastLocatorJson FROM books').fetchone(),
                         ('Parsed title', 1, 1, 'READING', 0.7, 'locator'))
        self.assertEqual(self.db.execute('SELECT * FROM custom_shelf_books').fetchall(), [(1, 1)])
        before = self.snapshot()
        self.db.execute(dao_query('updatePendingMetadata'), metadata_args('Stale replay'))
        self.assertEqual(self.snapshot(), before)

    def test_rescan_insert_contract_does_not_replace_book_rows(self):
        # This checks a current source guard, not that all future paths call it.
        source = (SRC / 'BookDatabase.kt').read_text()
        for method in ('insertBookRaw',):
            self.assertRegex(source, r'@Insert\s*\(\s*onConflict\s*=\s*OnConflictStrategy\.IGNORE\s*\)\s*'
                             r'suspend\s+fun\s+' + method + r'\s*\(')
        self.assertIn('@Transaction suspend fun insertIgnore(', source)
        self.assertIn('@Transaction suspend fun insertIgnoreBatch(', source)
        self.assertRegex(source, r'Index\(\s*"uriString"\s*,\s*unique\s*=\s*true\s*\)')
        before = self.snapshot()
        self.db.execute("INSERT OR IGNORE INTO books(id, uriString, title) VALUES(2, 'content://book/1', 'replacement')")
        self.assertEqual(self.snapshot(), before)

    def test_worker_start_must_not_unconditionally_clear_known_pause_flag(self):
        # Catch the known startup reset; this does not prove durable restoration.
        source = (SRC / 'LibraryScanner.kt').read_text()
        reset = re.search(r'private\s+fun\s+startWorkerLoop\b[^\{]*\{\s*'
                          r'pausedFlow\.value\s*=\s*false\b', source)
        self.assertIsNone(reset, 'Worker startup unconditionally clears the pause flag; '
                          'a paused scan must remain paused until explicitly resumed')

    def test_scanner_has_no_obvious_source_document_write_or_delete(self):
        # Deliberately narrow heuristic, not proof: cannot inspect indirect calls.
        source = (SRC / 'LibraryScanner.kt').read_text()
        for forbidden in (r'\.openOutputStream\s*\(', r'\.openFileDescriptor\s*\(',
                          r'DocumentsContract\.deleteDocument\s*\(',
                          r'\.deleteByIds?\s*\(', r'EpubMetadataEditor\s*\.'):
            self.assertNotRegex(source, forbidden)


class SQLiteModelTests(unittest.TestCase):
    """A reference model only: none of its schema or code is the app's scanner."""
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / 'reference.db'
        self.db = self.connect()
        self.db.executescript('''
          CREATE TABLE books(id INTEGER PRIMARY KEY, uri TEXT UNIQUE NOT NULL,
            title TEXT NOT NULL, extracted INTEGER NOT NULL DEFAULT 0,
            favorite INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT 'NEW',
            progress REAL NOT NULL DEFAULT 0, locator TEXT);
          CREATE TABLE shelves(id INTEGER PRIMARY KEY, name TEXT NOT NULL);
          CREATE TABLE membership(shelf INTEGER REFERENCES shelves(id),
            book INTEGER REFERENCES books(id) ON DELETE CASCADE, PRIMARY KEY(shelf, book));
          CREATE TABLE scan_state(id INTEGER PRIMARY KEY CHECK(id=1), paused INTEGER NOT NULL);
          CREATE TABLE queue(uri TEXT PRIMARY KEY, done INTEGER NOT NULL DEFAULT 0);
          INSERT INTO scan_state VALUES(1, 0);
          INSERT INTO books VALUES(1, 'uri:existing', 'User title', 1, 1, 'READING', 0.7, 'location');
          INSERT INTO shelves VALUES(1, 'My shelf');
          INSERT INTO membership VALUES(1, 1);
          INSERT INTO queue(uri) VALUES('uri:existing'), ('uri:new'), ('uri:other');
        ''')
        self.db.commit()

    def connect(self):
        db = sqlite3.connect(self.path)
        db.execute('PRAGMA foreign_keys=ON')
        return db

    def restart(self):
        self.db.close()
        self.db = self.connect()

    def tearDown(self):
        self.db.close()
        self.tmp.cleanup()

    def snapshot_existing(self):
        return (self.db.execute("SELECT * FROM books WHERE uri='uri:existing'").fetchone(),
                self.db.execute('SELECT * FROM membership').fetchall())

    def apply_one(self, uri, fail=False):
        # Discovery result and checkpoint are atomic in this reference design.
        with self.db:
            if self.db.execute('SELECT paused FROM scan_state WHERE id=1').fetchone()[0]:
                return False
            self.db.execute('INSERT OR IGNORE INTO books(uri,title) VALUES(?,?)', (uri, 'Scanned'))
            if fail:
                raise RuntimeError('injected interruption before checkpoint')
            self.db.execute('UPDATE queue SET done=1 WHERE uri=?', (uri,))
        return True

    def test_interrupted_transaction_reopens_without_half_checkpoint_then_replays(self):
        before = self.snapshot_existing()
        with self.assertRaisesRegex(RuntimeError, 'interruption'):
            self.apply_one('uri:new', fail=True)
        self.restart()
        self.assertEqual(self.db.execute("SELECT count(*) FROM books WHERE uri='uri:new'").fetchone()[0], 0)
        self.assertEqual(self.db.execute("SELECT done FROM queue WHERE uri='uri:new'").fetchone()[0], 0)
        self.apply_one('uri:new')
        self.apply_one('uri:new')  # at-least-once delivery
        self.restart()
        self.assertEqual(self.db.execute("SELECT count(*) FROM books WHERE uri='uri:new'").fetchone()[0], 1)
        self.assertEqual(self.db.execute("SELECT done FROM queue WHERE uri='uri:new'").fetchone()[0], 1)
        self.assertEqual(self.snapshot_existing(), before)

    def test_restart_after_commit_and_full_replay_preserves_user_state(self):
        before = self.snapshot_existing()
        self.apply_one('uri:new')
        self.restart()  # crash after commit before worker acknowledges completion
        for (uri,) in self.db.execute('SELECT uri FROM queue').fetchall():
            self.apply_one(uri)
        self.restart()
        self.assertEqual(self.db.execute('SELECT count(*), count(DISTINCT uri) FROM books').fetchone(), (3, 3))
        self.assertEqual(self.db.execute('SELECT count(*) FROM queue WHERE done=0').fetchone()[0], 0)
        self.assertEqual(self.snapshot_existing(), before)

    def test_pause_persists_across_restart_until_explicit_resume(self):
        with self.db:
            self.db.execute('UPDATE scan_state SET paused=1 WHERE id=1')
        self.restart()
        self.assertFalse(self.apply_one('uri:new'))
        self.assertEqual(self.db.execute("SELECT done FROM queue WHERE uri='uri:new'").fetchone()[0], 0)
        self.assertEqual(self.db.execute("SELECT count(*) FROM books WHERE uri='uri:new'").fetchone()[0], 0)
        with self.db:
            self.db.execute('UPDATE scan_state SET paused=0 WHERE id=1')
        self.restart()
        self.assertTrue(self.apply_one('uri:new'))
        self.assertEqual(self.db.execute("SELECT done FROM queue WHERE uri='uri:new'").fetchone()[0], 1)

    def test_replay_preserves_edits_made_after_enumeration(self):
        self.apply_one('uri:new')
        with self.db:
            self.db.execute("UPDATE books SET title='Manual edit', extracted=1, favorite=1, status='FINISHED' WHERE uri='uri:new'")
            self.db.execute("INSERT INTO membership SELECT 1, id FROM books WHERE uri='uri:new'")
        before = self.db.execute("SELECT * FROM books WHERE uri='uri:new'").fetchone()
        self.restart()
        self.apply_one('uri:new')
        self.assertEqual(self.db.execute("SELECT * FROM books WHERE uri='uri:new'").fetchone(), before)
        self.assertEqual(self.db.execute('SELECT count(*) FROM membership').fetchone()[0], 2)


if __name__ == '__main__':
    unittest.main()
