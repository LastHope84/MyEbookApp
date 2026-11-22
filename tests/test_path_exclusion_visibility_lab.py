"""Host SQLite migration/query execution and path model; not Kotlin, Room or SAF tests."""
import json, re, sqlite3, unittest
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
DB = (ROOT / 'app/src/main/java/com/example/myebookapp/BookDatabase.kt').read_text()
SCAN = (ROOT / 'app/src/main/java/com/example/myebookapp/LibraryScanner.kt').read_text()
DECISION = (ROOT / 'app/src/main/java/com/example/myebookapp/BookMembershipDecision.kt').read_text()
MIGRATION = DB.split('internal val M1112', 1)[1].split('internal val M1011', 1)[0]
Q = dict((name, sql) for sql, name in re.findall(r'@Query\("([^"\n]*)"\) (?:suspend )?fun (\w+)\(', DB))

def blocked(root, path, rows):
    return any(r == root and path[:len(p)] == p for r, p in rows)

class PathExclusionLab(unittest.TestCase):
    def test_actual_migration_and_queries_preserve_legacy(self):
        db=sqlite3.connect(':memory:')
        self.addCleanup(db.close)
        db.execute('CREATE TABLE books(id INTEGER PRIMARY KEY, progress REAL)')
        db.execute('CREATE TABLE custom_shelf_books(shelfId INTEGER,bookId INTEGER)')
        db.execute('INSERT INTO books VALUES(1, 0.65)')
        db.execute('INSERT INTO custom_shelf_books VALUES(3,1)')
        sql=re.findall(r'db\.execSQL\("([^"\n]+)"\)', MIGRATION)
        self.assertEqual(len(sql),1)
        db.execute(sql[0])
        path=json.dumps(['opaque/root','folder'], separators=(',',':'))
        db.execute('INSERT INTO scan_path_exclusions VALUES (?,?)',('grant-a',path))
        db.execute('INSERT INTO scan_path_exclusions VALUES (?,?)',('grant-b',path))
        self.assertEqual(db.execute(Q['pathExclusions'], {'rootUri':'grant-a'}).fetchall(), [('grant-a',path)])
        db.execute(Q['clearPathExclusion'], {'rootUri':'grant-a','directoryPath':path})
        self.assertEqual(db.execute('SELECT rootUri FROM scan_path_exclusions').fetchall(), [('grant-b',)])
        self.assertEqual(db.execute('SELECT * FROM books').fetchall(), [(1,0.65)])
        self.assertEqual(db.execute('SELECT * FROM custom_shelf_books').fetchall(), [(3,1)])
    def test_path_components_grant_and_multiparent(self):
        rows=[('a',['root','parent','x'])]
        self.assertTrue(blocked('a',['root','parent','x','child'],rows))
        self.assertFalse(blocked('a',['root','other','x','child'],rows))
        self.assertFalse(blocked('b',['root','parent','x','child'],rows))
        self.assertFalse(blocked('a',['root','parent','x2'],rows))
        self.assertTrue(blocked('a',['root','x'],[('a',['root'])]))
    def test_code_contracts_fail_closed(self):
        for text in ('@Transaction suspend fun commitObservedPathExclusion','configuredScanRoots().toSet() == expectedRoots.toSet()', '(inventoryState()?.generation ?: 0L) == generation', 'getScanSightings(rootUri, path[i - 1])', 'scanPathBlocked(root, itemPath, pathBlocks)', 'check(false) { "Experimental hiding disabled'):
            self.assertIn(text,DB)
        self.assertIn('scanPathBlocked(treeUri.toString(), path + documentId, pathBlocks)',SCAN)
        self.assertIn('fun decidePathQualified',DECISION)
        self.assertIn('directoryPath == null',DECISION)
        self.assertIn('!evidence.ownershipProvenanceComplete',DECISION)
        self.assertIn('if (affected.size < routes.size) MembershipDecision.RETAIN_SHARED',DECISION)
        self.assertNotRegex(DB,r'suspend fun hideExclusiveScanMembers\([^\n]*\): Int')

if __name__=='__main__': unittest.main()
