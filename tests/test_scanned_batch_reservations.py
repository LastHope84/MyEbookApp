"""Host-side SQLite model + Kotlin source contracts; Android Room tests live in androidTest."""
from pathlib import Path
import sqlite3
import unittest

SOURCE = (Path(__file__).resolve().parents[1] / 'app/src/main/java/com/example/myebookapp/BookDatabase.kt').read_text()
METHOD = SOURCE.split('@Transaction suspend fun insertScannedBatch(', 1)[1].split('    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(', 1)[0]


class ScannedBatchReservations(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.addCleanup(self.db.close)
        self.db.executescript('''
          CREATE TABLE books(id INTEGER PRIMARY KEY, uri TEXT UNIQUE NOT NULL, name TEXT NOT NULL, size INTEGER NOT NULL);
          CREATE TABLE origins(book_id INTEGER PRIMARY KEY, document_id TEXT NOT NULL);
          CREATE TABLE sightings(document_id TEXT PRIMARY KEY);
        ''')

    def commit(self, items, known=frozenset(), blocked=frozenset()):
        # Model only: the production path is exercised by ScannedBatchDatabaseTest.
        accepted = set()
        inserted = []
        with self.db:
            for uri, name, size, doc, path in items:
                if blocked.intersection(path):
                    continue
                self.db.execute('INSERT OR IGNORE INTO sightings VALUES(?)', (doc,))
                key = f'{name.lower()}|{size}' if size > 0 else None
                if key is not None and (key in known or key in accepted):
                    continue
                cursor = self.db.execute('INSERT OR IGNORE INTO books(uri,name,size) VALUES(?,?,?)', (uri,name,size))
                if cursor.rowcount == 0:
                    continue
                if key is not None:
                    accepted.add(key)
                self.db.execute('INSERT INTO origins VALUES(?,?)', (cursor.lastrowid,doc))
                inserted.append(doc)
        return inserted, accepted

    def test_actual_kotlin_reserves_only_after_success(self):
        self.assertIn('@Transaction suspend fun insertScannedBatch', SOURCE)
        self.assertIn('item.directoryPath.none { it in blocked }', METHOD)
        self.assertLess(METHOD.index('val allowed = items.filter'), METHOD.index('insertScanSightings(allowed.map'))
        self.assertLess(METHOD.index('key in knownKeys || key in acceptedKeys'), METHOD.index('val id = insertIgnore(book)'))
        self.assertLess(METHOD.index('val id = insertIgnore(book)'), METHOD.index('if (id == -1L) continue'))
        self.assertLess(METHOD.index('if (id == -1L) continue'), METHOD.index('acceptedKeys.add(key)'))
        self.assertLess(METHOD.index('acceptedKeys.add(key)'), METHOD.index('origins.add('))

    def test_conflicting_uri_then_distinct_same_key_and_next_batch(self):
        self.db.execute("INSERT INTO books(uri,name,size) VALUES('existing','old.epub',10)")
        self.db.commit()
        known = {'old.epub|10'}
        inserted, keys = self.commit([('existing','NEW.epub',20,'a',['root']), ('different','new.epub',20,'b',['root'])], known)
        self.assertEqual((inserted, keys), (['b'], {'new.epub|20'}))
        self.assertEqual(self.db.execute('SELECT uri,name,size FROM books ORDER BY id').fetchall(), [('existing','old.epub',10),('different','new.epub',20)])
        self.assertEqual(self.db.execute('SELECT document_id FROM origins').fetchall(), [('b',)])
        inserted, keys = self.commit([('existing','later.epub',30,'c',['root'])], known)
        self.assertEqual((inserted, keys), ([], set()))
        self.assertEqual(self.commit([('next','later.epub',30,'d',['root'])], known | keys), (['d'], {'later.epub|30'}))

    def test_known_keys_new_keys_zero_sizes_and_exclusion_barrier(self):
        items = [('excluded','FRESH.epub',12,'blocked',['root','blocked']),
                 ('known','KNOWN.epub',11,'known',['root']),
                 ('first','Fresh.epub',12,'first',['root']),
                 ('duplicate','fresh.epub',12,'duplicate',['root']),
                 ('zero1','zero.epub',0,'zero1',['root']),
                 ('zero2','zero.epub',0,'zero2',['root'])]
        inserted, keys = self.commit(items, {'known.epub|11'}, {'blocked'})
        self.assertEqual(inserted, ['first','zero1','zero2'])
        self.assertEqual(keys, {'fresh.epub|12'})
        self.assertIsNone(self.db.execute("SELECT * FROM sightings WHERE document_id='blocked'").fetchone())
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM sightings').fetchone()[0], 5)

if __name__ == '__main__':
    unittest.main()
