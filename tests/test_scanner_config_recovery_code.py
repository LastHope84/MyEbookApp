"""Host source/SQLite checks and a scheduling model, not Kotlin or Android execution."""
import asyncio
import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DB = (ROOT / 'app/src/main/java/com/example/myebookapp/BookDatabase.kt').read_text()
SCANNER = (ROOT / 'app/src/main/java/com/example/myebookapp/LibraryScanner.kt').read_text()

class ScannerConfigRecoveryCode(unittest.TestCase):
    def test_migration_preserves_legacy_data_and_creates_empty_journal(self):
        sql = re.search(r'internal val M1213.*?db.execSQL\("([^"]+)"\)', DB, re.S).group(1)
        conn = sqlite3.connect(':memory:')
        conn.execute('CREATE TABLE books(id INTEGER PRIMARY KEY, title TEXT)')
        conn.execute("INSERT INTO books VALUES(1, 'keep')")
        conn.execute('CREATE TABLE scan_config_roots(rootUri TEXT PRIMARY KEY)')
        conn.execute("INSERT INTO scan_config_roots VALUES('legacy')")
        conn.execute(sql)
        self.assertEqual(conn.execute('SELECT * FROM books').fetchall(), [(1, 'keep')])
        self.assertEqual(conn.execute('SELECT * FROM scan_config_roots').fetchall(), [('legacy',)])
        self.assertEqual(conn.execute('SELECT * FROM scan_config_journal').fetchall(), [])
        conn.close()

    def test_pending_add_and_new_add_fail_before_writes(self):
        recover = SCANNER.split('suspend fun reconcileFolderConfig', 1)[1].split('suspend fun addFolder', 1)[0]
        self.assertLess(recover.index('check(dao.configJournal() == null)'), recover.index('dao.initializeAndCheckConfig('))
        self.assertNotIn('putStringSet(', recover)
        self.assertNotIn('finishJournaledRootAdd(', recover)
        add = SCANNER.split('suspend fun addFolder', 1)[1].split('/** Called only from visible UI', 1)[0]
        self.assertIn('error("Adding folders is disabled', add)
        for forbidden in ('beginJournaledRootAdd(', 'save(', 'scanFolders(', 'withScannerQuiescent('):
            self.assertNotIn(forbidden, add)
        runscan = SCANNER.split('private suspend fun runScan', 1)[1].split('suspend fun removeSubfolder', 1)[0]
        self.assertLess(runscan.index('next in synchronized(lock) { getSavedFolders(context) }'), runscan.index('walkTree('))

    def test_queue_and_pause_boundary_is_unchanged(self):
        scan = SCANNER.split('fun scanFolders(', 1)[1].split('fun resumeMetadataOnly', 1)[0]
        self.assertIn('if (old == PAUSED) PAUSED else RUNNING', scan)
        self.assertLess(scan.index('save(app, next, queue)'), scan.index('if (startRevision != null) startForeground(app, startRevision)'))
        ui = (ROOT / 'app/src/main/java/com/example/myebookapp/MainActivity.kt').read_text()
        self.assertNotIn('folderP.launch(', ui)
        self.assertNotIn('takePersistableUriPermission(u,', ui)
        self.assertIn('Add Folder (temporarily disabled)', ui)

    def test_sqlite_abort_after_journal_insert_rolls_back_all_writes(self):
        conn = sqlite3.connect(':memory:')
        conn.executescript('''CREATE TABLE roots(root TEXT PRIMARY KEY);
            CREATE TABLE journal(operation TEXT NOT NULL);
            CREATE TABLE inventory(generation INTEGER NOT NULL);
            INSERT INTO roots VALUES ('old');
            INSERT INTO inventory VALUES (1);
            CREATE TRIGGER fail_root BEFORE INSERT ON roots WHEN NEW.root = 'added'
            BEGIN SELECT RAISE(ABORT, 'injected'); END;''')
        try:
            with conn:
                conn.execute("INSERT INTO journal VALUES ('ADD')")
                conn.execute('UPDATE inventory SET generation = generation + 1')
                conn.execute("INSERT INTO roots VALUES ('added')")
        except sqlite3.IntegrityError:
            pass
        else:
            self.fail('trigger must abort')
        self.assertEqual(conn.execute('SELECT * FROM journal').fetchall(), [])
        self.assertEqual(conn.execute('SELECT * FROM inventory').fetchall(), [(1,)])
        self.assertEqual(conn.execute('SELECT * FROM roots').fetchall(), [('old',)])
        conn.close()

    def test_scan_and_operations_fail_closed(self):
        worker = SCANNER.split('fun startWorker', 1)[1].split('fun interrupt', 1)[0]
        self.assertLess(worker.index('reconcileFolderConfig('), worker.index('runScan('))
        gate = SCANNER.split('private suspend fun <T> withScannerQuiescent', 1)[1].split('suspend fun excludeFolderFromScans', 1)[0]
        self.assertIn('reconcileFolderConfig(app); work()', gate)
        restore = SCANNER.split('suspend fun restoreDetachedScanRoot', 1)[1].split('private suspend fun <T> withScannerQuiescent', 1)[0]
        self.assertIn('Root restoration disabled', restore)
        self.assertNotIn('clearRemovedScanRoot', restore)

class ScanAdmissionScheduleModel(unittest.IsolatedAsyncioTestCase):
    async def test_unconfigured_enqueue_during_walk_is_not_traversed(self):
        # Scheduling model only: the host does not execute the Kotlin scanner.
        queue, saved, traversed = ['old'], {'old'}, []
        walking, release = asyncio.Event(), asyncio.Event()

        async def worker():
            while queue:
                root = queue[0]
                if root not in saved:
                    raise ValueError('unconfigured')
                traversed.append(root)
                if root == 'old':
                    walking.set()
                    await release.wait()
                queue.pop(0)

        task = asyncio.create_task(worker())
        await asyncio.wait_for(walking.wait(), 1)
        queue.append('foreign')  # concurrent enqueue while the first root is walking
        release.set()
        with self.assertRaisesRegex(ValueError, 'unconfigured'):
            await asyncio.wait_for(task, 1)
        self.assertEqual(traversed, ['old'])
        self.assertEqual(queue, ['foreign'])


if __name__ == '__main__':
    unittest.main()
