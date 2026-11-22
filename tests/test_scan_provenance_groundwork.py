"""SQLite/schema and source contracts only; not Room or Android provider/device tests.

The historical data collected here is never sufficient authority for folder removal.
"""
import base64
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import unittest
import zlib

ROOT = Path(__file__).resolve().parents[1]
DB = (ROOT / 'app/src/main/java/com/example/myebookapp/BookDatabase.kt').read_text()
SCANNER = (ROOT / 'app/src/main/java/com/example/myebookapp/LibraryScanner.kt').read_text()
MIGRATION = DB.split('internal val M56', 1)[1].split('internal val M67', 1)[0]
SQL = re.findall(r'db\.execSQL\("([^"\n]+)"\)', MIGRATION)


class ProvenanceGroundwork(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.addCleanup(self.db.close)
        self.db.execute('PRAGMA foreign_keys=ON')
        self.db.execute('CREATE TABLE books(id INTEGER PRIMARY KEY, uriString TEXT UNIQUE, title TEXT)')
        self.db.executemany('INSERT INTO books VALUES(?, ?, ?)', [(1, 'legacy:1', 'Edited title'), (2, 'legacy:2', 'Another')])
        self.db.commit()
        self.assertEqual(len(SQL), 2)
        for sql in SQL:
            self.db.execute(sql)
        self.db.commit()

    def test_migration_preserves_legacy_without_inventing_origin(self):
        self.assertEqual(self.db.execute('SELECT * FROM books ORDER BY id').fetchall(), [(1, 'legacy:1', 'Edited title'), (2, 'legacy:2', 'Another')])
        self.assertEqual(self.db.execute('SELECT * FROM scan_book_origins').fetchall(), [])
        self.assertEqual(self.db.execute('SELECT * FROM scan_sightings').fetchall(), [])
        self.assertIn('addMigrations(M12, M23, M34, M45, M56, M67, M78)', DB)

    def test_origin_cascade_deletes_only_metadata(self):
        self.db.execute('INSERT INTO scan_book_origins VALUES(?, ?, ?, ?)', (1, 'content://provider/tree/root', 'parent', 'doc'))
        self.db.execute('INSERT INTO scan_sightings VALUES(?, ?, ?, ?)', ('content://provider/tree/root', 'parent', 'doc', 0))
        self.db.execute('DELETE FROM books WHERE id=1')
        self.assertEqual(self.db.execute('SELECT * FROM books').fetchall(), [(2, 'legacy:2', 'Another')])
        self.assertEqual(self.db.execute('SELECT * FROM scan_book_origins').fetchall(), [])
        self.assertEqual(len(self.db.execute('SELECT * FROM scan_sightings').fetchall()), 1)
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute('INSERT INTO scan_book_origins VALUES(99, ?, ?, ?)', ('root','parent','doc'))

    def test_transaction_rolls_back_insert_and_sighting_on_origin_failure(self):
        with self.assertRaises(sqlite3.IntegrityError):
            with self.db:
                self.db.execute("INSERT INTO scan_sightings VALUES('root', 'parent', 'doc', 0)")
                self.db.execute("INSERT INTO books VALUES(3, 'new-uri', 'new')")
                self.db.execute("INSERT INTO scan_book_origins VALUES(3, 'root', 'parent', 'doc')")
                self.db.execute("INSERT INTO scan_book_origins VALUES(3, 'root', 'parent', 'doc')")
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM scan_sightings').fetchone()[0], 0)
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM scan_book_origins').fetchone()[0], 0)
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM books').fetchone()[0], 2)

    def test_same_document_multiple_roots_and_parents_are_distinct(self):
        vals = [('root1','parent1','opaque-doc',0), ('root1','parent2','opaque-doc',0), ('root2','parent1','opaque-doc',0)]
        self.db.executemany('INSERT OR IGNORE INTO scan_sightings VALUES(?, ?, ?, ?)', vals + vals)
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM scan_sightings').fetchone()[0], 3)

    def test_scanner_records_skips_without_making_them_origins(self):
        self.assertIn('@Transaction suspend fun insertScannedBatch(items: List<ScannedItem>, knownKeys: Set<String>, authority: String)', DB)
        batch = DB.split('@Transaction suspend fun insertScannedBatch', 1)[1].split('@Insert(onConflict', 1)[0]
        self.assertIn('insertScanSightings(allowed.map { it.sighting })', batch)
        self.assertIn('val book = item.book ?: continue', batch)
        self.assertIn('key in knownKeys || key in acceptedKeys', batch)
        self.assertIn('if (id == -1L) continue', batch)
        self.assertLess(batch.index('if (id == -1L) continue'), batch.index('origins.add(ScanBookOrigin'))
        self.assertLess(batch.index('if (id == -1L) continue'), batch.index('acceptedKeys.add(key)'))
        self.assertIn('batch.add(ScannedItem(sighting, book, path))', SCANNER)
        self.assertIn('dao.insertScannedBatch(batch, keys, authority)', SCANNER)
        self.assertNotIn('deleteDocument(', SCANNER)
        self.assertNotIn('openOutputStream(', SCANNER)

    def test_restored_activity_keeps_scan_and_whole_book_translation_ui(self):
        build = ROOT / '.translation-build-input'
        entries = json.loads((build / 'manifest.json').read_text())
        entry = next(e for e in entries if e['path'].endswith('/MainActivity.kt'))
        raw = zlib.decompress(base64.b64decode(''.join((build / p).read_text() for p in entry['parts']), validate=True))
        self.assertEqual(hashlib.sha256(raw).hexdigest(), entry['sha256'])
        activity = raw.decode()
        for marker in ('LaunchedEffect(Unit) { LibraryScanner.restore(ctx) }', 'pendingScanAction by rememberSaveable', 'requestScan("folder", u.toString())', 'requestScan("rescan")', 'requestScan("resume")', 'LibraryScanner.pauseScan(ctx)', 'LibraryScanner.stopScan(ctx)', 'translatingBook?.let { book ->', 'WholeEpubTranslation.translate'):
            self.assertIn(marker, activity)

if __name__ == '__main__':
    unittest.main()
