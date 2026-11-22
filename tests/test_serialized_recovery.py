"""Source contracts and deterministic models; these do not execute Kotlin or Android."""
import threading
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCAN = (ROOT / 'app/src/main/java/com/example/myebookapp/LibraryScanner.kt').read_text()
GATE = (ROOT / 'app/src/main/java/com/example/myebookapp/ScannerSafetyGate.kt').read_text()

class RecoveryContracts(unittest.TestCase):
    def test_gate_is_shared_by_real_restore_controls_and_worker(self):
        self.assertIn('private val safety = ScannerSafetyGate()', SCAN)
        restore = SCAN.split('fun restore(context:', 1)[1].split('fun scanFolders', 1)[0]
        self.assertLess(restore.index('safety.beginRestore()'), restore.index('scope.launch'))
        self.assertIn('safety.restoreCurrent(token)', restore)
        self.assertIn('safety.retryRestore()', restore)
        self.assertIn('finally {\n                synchronized(lock) { safety.endRestore() }', restore)
        for name, end in [('scanFolders', 'resumeMetadataOnly'), ('resumeScan', 'stopScan'), ('startWorker', 'claimService')]:
            self.assertIn('safety.canAdmit()', SCAN.split('fun '+name, 1)[1].split('fun '+end, 1)[0])
        for name, end in [('pauseScan', 'resumeScan'), ('stopScan', 'startForeground'), ('interrupt(context', 'checkpoint')]:
            self.assertIn('safety.changed()', SCAN.split('fun '+name, 1)[1].split('fun '+end, 1)[0])

    def test_failed_commit_does_not_rollback_or_allow_writes(self):
        save = SCAN.split('private fun save(', 1)[1].split('fun getSavedFolders', 1)[0]
        self.assertIn('if (!committed) { poisonCheckpoint()', save)
        self.assertIn('safety.canPersist()', save)
        self.assertIn('activeJob?.cancel()', save)
        self.assertIn('safety.failedCommit()', save)
        self.assertIn('private var uncertain = false', GATE)
        self.assertIn('private var configurationVerified = false', GATE)
        self.assertIn('!uncertain && !restoreActive && configurationVerified', GATE)
        self.assertIn('safety.markVerified(token)', SCAN)
        self.assertIn('safety.unverify()', SCAN)
        self.assertNotIn('uncertain = false\n', GATE.split('fun failedCommit()', 1)[1])
        self.assertIn('!safety.isUncertain() && sessions.isDisarmed()', SCAN.split('private fun reportStartFailure', 1)[1])

    def test_stop_discards_roots_but_keeps_metadata_in_room(self):
        stop = SCAN.split('fun stopScan',1)[1].split('private fun startForeground',1)[0]
        self.assertIn('save(app, STOPPED, emptyList(), "stop")', stop)
        self.assertIn('ScanStatus(phaseLabel = "Scan stopped")', stop)
        self.assertIn('Could not confirm scan stopped', stop)
        self.assertIn('Stop may not have been saved', stop)
        self.assertNotIn('dao.', stop)
        self.assertIn('fun resumeMetadataOnly(context: Context) = scanFolders(context, emptyList())', SCAN)
        resume = SCAN.split('fun resumeScan',1)[1].split('fun stopScan',1)[0]
        self.assertNotIn('stoppedWithWork', resume)
        restore = SCAN.split('fun restore(context:',1)[1].split('fun scanFolders',1)[0]
        self.assertNotIn('stoppedWithWork', restore)
        self.assertIn('roots(app).isNotEmpty()', restore)
        self.assertIn('save(app, STOPPED, emptyList(), "stop")', restore)
        self.assertLess(restore.index('reconcileFolderConfig(app)'), restore.index('roots(app).isNotEmpty()'))
        self.assertNotIn('startForeground(', restore)
        self.assertIn('!sessions.isCurrent(request)', SCAN.split('private fun startForeground', 1)[1])

    def test_old_worker_cannot_unverify_new_generation_and_cancellation_is_rethrown(self):
        worker = SCAN.split('fun startWorker',1)[1].split('fun claimService',1)[0]
        reconcile = worker.split('val validated = try',1)[1].split('synchronized(lock) {\n                        ensureActive()',1)[0]
        self.assertIn('catch (e: CancellationException) {\n                        throw e', reconcile)
        self.assertIn('if (generation == token) safety.unverify()', reconcile)

    def test_dangerous_entry_points_remain_disabled(self):
        self.assertNotIn('ScannerConfigRecovery.replay(', SCAN)
        for start, text in [('suspend fun addFolder', 'Adding folders is disabled'),
                            ('suspend fun removeSubfolder', 'Folder removal disabled'),
                            ('suspend fun detachWholeScanRoot', 'Experimental hiding disabled'),
                            ('suspend fun excludeFolderFromScans', 'Folder exclusion disabled'),
                            ('suspend fun restoreScanExclusion', 'Folder exclusion restore disabled')]:
            self.assertIn(text, SCAN.split(start, 1)[1].split('\n    }', 1)[0])

class RestoreInterleavingModel(unittest.TestCase):
    def test_stop_or_pause_wins_and_stale_restore_retries_behind_gate(self):
        for control in ('stop', 'pause'):
            lock = threading.Lock(); started = threading.Event(); release = threading.Event(); retrying = threading.Event(); finish = threading.Event()
            state = {'epoch': 0, 'mode':'running', 'queue':['old'], 'admission':False, 'stale_applied':False, 'verified':False}
            def restore():
                token = state['epoch']; started.set(); self.assertTrue(release.wait(2))
                with lock:
                    if token == state['epoch']: state['stale_applied'] = True
                    token = state['epoch']
                retrying.set(); self.assertTrue(finish.wait(2))
                with lock:
                    if token == state['epoch']:
                        state['verified'] = True; state['admission'] = True
            t = threading.Thread(target=restore); t.start(); self.assertTrue(started.wait(2))
            with lock:
                state['epoch'] += 1; state['mode'] = 'stopped' if control == 'stop' else 'paused'
                if control == 'stop': state['queue'] = []
            release.set(); self.assertTrue(retrying.wait(2))
            self.assertFalse(state['admission']); self.assertFalse(state['stale_applied'])
            finish.set(); t.join(2); self.assertFalse(t.is_alive())
            self.assertTrue(state['verified']); self.assertTrue(state['admission'])
            self.assertEqual(state['queue'], [] if control == 'stop' else ['old'])
            self.assertEqual(state['mode'], 'stopped' if control == 'stop' else 'paused')

    def test_stale_worker_failure_cannot_invalidate_new_generation(self):
        state = {'generation': 1, 'verified': True}
        old = state['generation']
        state['generation'] += 1
        # Cancellation propagates without invalidation; other failures require token ownership.
        if state['generation'] == old: state['verified'] = False
        self.assertTrue(state['verified'])
        current = state['generation']
        if state['generation'] == current: state['verified'] = False
        self.assertFalse(state['verified'])

    def test_false_commit_cache_is_not_used_for_a_second_write(self):
        state = {'cache':'running', 'disk':'running', 'poisoned':False, 'writes':0}
        def write(value, succeeds):
            if state['poisoned']: return False
            state['writes'] += 1; state['cache'] = value
            if succeeds: state['disk'] = value
            else: state['poisoned'] = True
            return succeeds
        self.assertFalse(write('stopped', False))
        self.assertEqual((state['cache'], state['disk']), ('stopped', 'running'))
        self.assertFalse(write('running', True)); self.assertFalse(write('stopped', True))
        self.assertEqual(state['writes'], 1)

if __name__ == '__main__': unittest.main()
