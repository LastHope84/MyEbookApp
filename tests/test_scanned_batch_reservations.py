"""Host SQLite model and source contracts, not a replacement for Android Room tests."""
from pathlib import Path
import sqlite3
import unittest

SOURCE = (Path(__file__).resolve().parents[1] / 'app/src/main/java/com/example/myebookapp/BookDatabase.kt').read_text()
METHOD = SOURCE.split('@Transaction suspend fun insertScannedBatch(', 1)[1].split('    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(', 1)[0]

class ScannedBatchIdentity(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.addCleanup(self.db.close)
        self.db.executescript('''
          CREATE TABLE books(id INTEGER PRIMARY KEY, uri TEXT UNIQUE NOT NULL, name TEXT NOT NULL, size INTEGER NOT NULL);
          CREATE TABLE routes(book_id INTEGER NOT NULL, authority TEXT NOT NULL, doc TEXT NOT NULL, root TEXT NOT NULL);
          CREATE TABLE sightings(root TEXT NOT NULL, doc TEXT NOT NULL);
          CREATE TABLE unresolved(root TEXT NOT NULL, doc TEXT NOT NULL);
        ''')

    def commit(self, items, blocked=frozenset()):
        inserted = 0
        with self.db:
            for uri, name, size, authority, doc, root, path in items:
                if blocked.intersection(path): continue
                self.db.execute('INSERT INTO sightings VALUES(?,?)', (root, doc))
                row = self.db.execute('SELECT id FROM books WHERE uri=?', (uri,)).fetchone()
                ids = {x[0] for x in self.db.execute('SELECT DISTINCT book_id FROM routes WHERE authority=? AND doc=?', (authority, doc))}
                if row: ids.add(row[0])
                if len(ids) > 1:
                    self.db.execute('INSERT INTO unresolved VALUES(?,?)', (root, doc))
                    continue
                if ids:
                    self.db.execute('INSERT INTO routes VALUES(?,?,?,?)', (ids.pop(), authority, doc, root))
                    continue
                cur = self.db.execute('INSERT INTO books(uri,name,size) VALUES(?,?,?)', (uri, name, size))
                self.db.execute('INSERT INTO routes VALUES(?,?,?,?)', (cur.lastrowid, authority, doc, root))
                inserted += 1
        return inserted

    def test_source_exclusions_before_sightings_and_unique_identity_before_insert(self):
        self.assertIn('item.directoryPath.none { it in blocked }', METHOD)
        self.assertLess(METHOD.index('val allowed = items.filter'), METHOD.index('insertScanSightings(allowed.map'))
        self.assertIn('bookIdsForScanIdentities(authority, chunk)', METHOD)
        self.assertLess(METHOD.index('candidateIds.size > 1'), METHOD.index('val id = insertBookRaw(book)'))
        self.assertIn('insertUnresolvedScanRoute(', METHOD)
        self.assertNotIn('key in knownKeys', METHOD)
        self.assertLess(METHOD.index('if (id == -1L) continue'), METHOD.index('origins.add('))

    def test_different_documents_same_name_and_size_and_exclusions(self):
        items = [('blocked','same.epub',12,'p','blocked','a',['a','blocked']),
                 ('u1','same.epub',12,'p','one','a',['a']),
                 ('u2','same.epub',12,'p','two','a',['a']),
                 ('u3','same.epub',12,'other','one','a',['a'])]
        self.assertEqual(self.commit(items, {'blocked'}), 3)
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM sightings').fetchone()[0], 3)
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM books').fetchone()[0], 3)

    def test_identity_across_roots_and_ambiguity(self):
        self.assertEqual(self.commit([('u1','same',1,'p','doc','a',['a'])]), 1)
        self.assertEqual(self.commit([('u2','changed',2,'p','doc','b',['b'])]), 0)
        self.assertEqual(self.db.execute('SELECT uri,name,size FROM books').fetchall(), [('u1','same',1)])
        self.db.execute("INSERT INTO books VALUES(2,'other','same',1)")
        self.db.execute("INSERT INTO routes VALUES(2,'p','doc','c')")
        self.db.commit()
        self.assertEqual(self.commit([('u3','same',1,'p','doc','d',['d'])]), 0)
        self.assertEqual(self.db.execute('SELECT * FROM unresolved').fetchall(), [('d','doc')])
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM books').fetchone()[0], 2)

if __name__ == '__main__': unittest.main()
