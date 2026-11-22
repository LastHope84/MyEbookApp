"""SQLite/scheduling model and source contracts; not Kotlin or Android execution."""
import json
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DB = (ROOT / 'app/src/main/java/com/example/myebookapp/BookDatabase.kt').read_text()
LAB = (ROOT / 'app/src/main/java/com/example/myebookapp/ScannerConfigIntentLab.kt').read_text()
SCANNER = (ROOT / 'app/src/main/java/com/example/myebookapp/LibraryScanner.kt').read_text()


def database():
    db = sqlite3.connect(':memory:')
    db.executescript('''CREATE TABLE scan_config_roots(rootUri TEXT PRIMARY KEY NOT NULL);
        CREATE TABLE scan_config_journal(singleton INTEGER PRIMARY KEY, operation TEXT NOT NULL,
          rootUri TEXT NOT NULL, beforeJson TEXT NOT NULL, afterJson TEXT NOT NULL);
        CREATE TABLE inventory(generation INTEGER NOT NULL);
        INSERT INTO inventory VALUES(7);
        INSERT INTO scan_config_roots VALUES('old');''')
    return db


def stage(db):
    with db:
        db.execute('INSERT INTO scan_config_journal VALUES(1,?,?,?,?)',
                   ('ADD_SCAN_V2', 'new', '["old"]', '["new","old"]'))
        db.execute('UPDATE inventory SET generation=generation+1')
        db.execute('INSERT INTO scan_config_roots VALUES(?)', ('new',))


def replay_model(saved, mode, queue, journal=True):
    if not journal:
        return saved, mode, queue, False
    before, after, root = {'old'}, {'old', 'new'}, 'new'
    if saved not in (before, after) or len(set(queue)) != len(queue) or any(q not in after for q in queue):
        raise ValueError('divergent state')
    if mode not in ('running', 'paused', 'interrupted', 'stopped'):
        raise ValueError('bad mode')
    if mode == 'stopped' and queue and queue != [root]:
        raise ValueError('unexpected stopped queue')
    return after, 'paused' if mode == 'paused' else 'interrupted', queue if root in queue else queue + [root], False


class IntentLabTest(unittest.TestCase):
    def test_transaction_rollback_for_all_staged_write_boundaries(self):
        for failed_table in ('scan_config_journal', 'inventory', 'scan_config_roots'):
            db = database()
            operation = 'UPDATE' if failed_table == 'inventory' else 'INSERT'
            db.execute(f"CREATE TRIGGER fail BEFORE {operation} ON {failed_table} BEGIN SELECT RAISE(ABORT, 'fail'); END")
            with self.assertRaises(sqlite3.IntegrityError):
                stage(db)
            self.assertEqual(db.execute('SELECT * FROM scan_config_journal').fetchall(), [])
            self.assertEqual(db.execute('SELECT * FROM scan_config_roots').fetchall(), [('old',)])
            self.assertEqual(db.execute('SELECT generation FROM inventory').fetchone(), (7,))
            db.close()

    def test_crash_boundaries_and_idempotency_preserve_paused_work(self):
        for folders, queue in [({'old'}, ['old']), ({'old','new'}, ['old']), ({'old','new'}, ['old','new'])]:
            out = replay_model(folders, 'paused', queue)
            self.assertEqual(out, ({'old','new'}, 'paused', ['old','new'], False))
            self.assertEqual(replay_model(*out), out)

    def test_stopped_becomes_interrupted_and_divergence_fails_closed(self):
        self.assertEqual(replay_model({'old'}, 'stopped', []), ({'old','new'}, 'interrupted', ['new'], False))
        for saved, queue in [({'other'}, []), ({'old'}, ['alien']), ({'old'}, ['old','old'])]:
            with self.assertRaises(ValueError): replay_model(saved, 'paused', queue)

    def test_v2_is_staged_but_not_wired_or_enabled(self):
        stage_source = DB.split('suspend fun stageRootAddWithScanIntent',1)[1].split('suspend fun finishRootAddWithScanIntent',1)[0]
        self.assertIn('operation = "ADD_SCAN_V2"', stage_source)
        self.assertIn('invalidateScanInventory()', stage_source)
        self.assertIn('insertConfigRoots(', stage_source)
        self.assertLess(LAB.index('folders.edit()'), LAB.index('scan.edit()'))
        self.assertLess(LAB.index('scan.edit()'), LAB.index('dao.finishRootAddWithScanIntent'))
        self.assertNotIn('ScannerConfigIntentLab', SCANNER)
        self.assertIn('Adding folders is disabled', SCANNER)

    def test_replay_without_startup_gate_can_undo_stop(self):
        # Counterexample: after death but before replay, an ungated stop clears the queue;
        # replay then resurrects work. This is why the prototype must not be enabled.
        self.assertEqual(replay_model({'old','new'}, 'stopped', [])[2], ['new'])


if __name__ == '__main__': unittest.main()
