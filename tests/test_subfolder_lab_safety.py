"""Host-only behavioral model and Kotlin source guards, not Android/Room/provider tests."""
from pathlib import Path
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/example/myebookapp'
DB = (SRC / 'BookDatabase.kt').read_text()
SCANNER = (SRC / 'LibraryScanner.kt').read_text()
CORE = (SRC / 'FolderRemovalCore.kt').read_text()
POLICY = (SRC / 'RemovedScanRootPolicy.kt').read_text()
HOST = (SRC / 'FolderManagementHost.kt').read_text()

class SubfolderLab(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.addCleanup(self.db.close)
        self.db.executescript('''CREATE TABLE exclusions(root TEXT, doc TEXT, PRIMARY KEY(root,doc));
          CREATE TABLE books(id INTEGER PRIMARY KEY, favorite INTEGER, note TEXT);
          INSERT INTO books VALUES(1,1,'retain');
        ''')

    def allowed(self, root, path):
        blocked = {r[0] for r in self.db.execute('SELECT doc FROM exclusions WHERE root=?',(root,))}
        return not any(d in blocked for d in path)

    def test_scoped_opaque_edges_not_prefixes(self):
        self.db.execute('INSERT INTO exclusions VALUES(?,?)', ('A', 'dir:1'))
        self.assertFalse(self.allowed('A',['root', 'dir:1','nested']))
        self.assertTrue(self.allowed('A',['root', 'dir:10']))
        self.assertTrue(self.allowed('B',['root','dir:1','nested']))
        self.assertEqual(self.db.execute('SELECT * FROM books').fetchall(), [(1,1,'retain')])

    def test_root_blocks_its_subtree_and_restore(self):
        self.db.execute('INSERT INTO exclusions VALUES(?,?)', ('A','root'))
        self.assertFalse(self.allowed('A',['root','dir','child']))
        self.assertTrue(self.allowed('B',['root','dir','child']))
        self.db.execute('DELETE FROM exclusions WHERE root=? AND doc=?', ('A','root'))
        self.assertTrue(self.allowed('A',['root','dir','child']))
        self.assertEqual(self.db.execute('SELECT * FROM books').fetchall(), [(1,1,'retain')])

    def test_scanner_rechecks_generation_and_root_exclusions_in_transaction(self):
        batch = DB.split('@Transaction suspend fun insertScannedBatch(',1)[1].split('@Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertBookRaw',1)[0]
        self.assertLess(batch.index('scanGeneration != null'), batch.index('insertScanSightings('))
        self.assertIn('rootExclusions(root)', batch)
        self.assertIn('item.directoryPath.none { it in blocked }',batch)
        self.assertIn('scanGeneration = scanGeneration',SCANNER)
        checked = DB.split('@Transaction suspend fun commitCheckedRootExclusion(',1)[1].split('/** Restoration',1)[0]
        self.assertLess(checked.index('inventoryState()?.generation'),checked.index('invalidateScanInventory()'))
        self.assertIn('configuredScanRoots().toSet() == expectedRoots.toSet()',checked)
        self.assertNotIn('delete',checked.lower())

    def test_no_user_visible_removal_or_unproven_hiding(self):
        self.assertIn('override val canRemove = false',HOST)
        self.assertIn('override val canRestore = false',HOST)
        detach = DB.split('@Transaction suspend fun detachWithInventory(',1)[1]
        self.assertLess(detach.index('check(false)'),detach.index('hideExclusiveScanMembers('))
        self.assertIn('it.rootUri == rootUri && it.authority == authority', POLICY)
        self.assertIn('FolderRemovalEvidence.preview(grantRoot, listOf(grantRoot), emptyList())',CORE)
        self.assertIn('commitCheckedRootExclusion(',CORE)
        self.assertIn('withScannerQuiescent(app)', SCANNER.split('suspend fun excludeFolderFromScans(',1)[1].split('private suspend fun walkTree',1)[0])

if __name__ == '__main__': unittest.main()
