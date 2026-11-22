"""Recovery source contracts and transaction/scheduling model; not Kotlin execution."""
import sqlite3
import unittest
from pathlib import Path
R = Path(__file__).resolve().parents[1]
S = (R/'app/src/main/java/com/example/myebookapp/LibraryScanner.kt').read_text()
C = (R/'app/src/main/java/com/example/myebookapp/ScannerConfigRecovery.kt').read_text()
D = (R/'app/src/main/java/com/example/myebookapp/BookDatabase.kt').read_text()
SERVICE = (R/'app/src/main/java/com/example/myebookapp/ScanService.kt').read_text()

class RecoveryV3(unittest.TestCase):
    def test_pending_journal_fails_closed_at_startup_and_worker(self):
        self.assertNotIn('ScannerConfigRecovery.replay(', S)
        self.assertRegex(S, r'processingMutex\.withLock \{\s+reconcileFolderConfig\(app\)')
        self.assertIn('reconcileFolderConfig(context.applicationContext)', S)
        self.assertIn('check(dao.configJournal() == null)', S)
        self.assertIn('Adding folders is disabled', S)
        self.assertNotIn('BOOT_COMPLETED', (R/'app/src/main/AndroidManifest.xml').read_text())
    def test_worker_checks_mode_queue_and_generation_after_reconciliation(self):
        worker = S[S.index('fun startWorker('):S.index('/** Service lifecycle callbacks')]
        reconcile = worker.index('reconcileFolderConfig(context.applicationContext)')
        run = worker.index('runScan(context, token)')
        guard = worker[reconcile:run]
        self.assertIn('ensureActive()', guard)
        self.assertIn('generation != token', guard)
        self.assertIn('savedMode != RUNNING', guard)
        self.assertIn('return@withLock', guard)
        self.assertIn('workerAdmissionValid(savedMode, generation, token', guard)
        self.assertIn('revision == validated.revision && queue == validated.roots', S)
        self.assertIn('mode == RUNNING && currentGeneration == token', S)
    def test_atomic_stage_rolls_back_at_each_write(self):
        for table, operation in [('inventory','UPDATE'), ('journal','INSERT'), ('roots','INSERT')]:
            db = sqlite3.connect(':memory:')
            db.executescript('CREATE TABLE inventory(g INTEGER); INSERT INTO inventory VALUES(7); CREATE TABLE journal(op TEXT); CREATE TABLE roots(root TEXT); INSERT INTO roots VALUES("old");')
            db.execute(f"CREATE TRIGGER fail BEFORE {operation} ON {table} BEGIN SELECT RAISE(ABORT,'fail'); END")
            with self.assertRaises(sqlite3.IntegrityError):
                with db:
                    db.execute('UPDATE inventory SET g=8'); db.execute('INSERT INTO journal VALUES("V3")'); db.execute('INSERT INTO roots VALUES("new")')
            self.assertEqual(db.execute('SELECT * FROM inventory').fetchall(), [(7,)])
            self.assertEqual(db.execute('SELECT * FROM journal').fetchall(), [])
            self.assertEqual(db.execute('SELECT * FROM roots').fetchall(), [('old',)])
            db.close()
    def test_stop_and_pause_cannot_be_replaced_by_stale_snapshot(self):
        self.assertIn('control == "stop" && mode == "stopped" && queue.isEmpty()', C)
        self.assertIn('control == "pause" && mode == "paused"', C)
        self.assertIn('revision == oldRevision && mode == oldMode && queue == oldQueue', C)
        self.assertIn('marker == id', C)
        self.assertIn('dao.inventoryState()?.generation == inventoryGeneration', C)
        self.assertIn('finishRootAddV3(j, newFolders, inventoryGeneration)', C)
        self.assertIn('save(app, STOPPED, emptyList(), "stop")', S)
        self.assertIn('save(app, PAUSED, roots(app), "pause")', S)
    def test_stale_service_callback_rejected(self):
        self.assertIn('serviceOwner !== owner', S)
        self.assertIn('!LibraryScanner.isServiceOwner(this)', SERVICE)
        self.assertIn('startId != latestStartId', SERVICE)
    def test_v2_is_not_replayed(self):
        self.assertIn('check(j.operation == "ADD_SCAN_V3")', C)
        self.assertNotIn('ScannerConfigRecovery.replay(', S)
        self.assertIn('@Transaction suspend fun stageRootAddV3', D)
        self.assertIn('@Transaction suspend fun finishRootAddV3', D)

if __name__ == '__main__': unittest.main()
