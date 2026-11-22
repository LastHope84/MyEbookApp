"""Conservative source contracts and a scheduling model; not Android execution."""
import unittest
from pathlib import Path
R = Path(__file__).resolve().parents[1]
S = (R/'app/src/main/java/com/example/myebookapp/LibraryScanner.kt').read_text()
V = (R/'app/src/main/java/com/example/myebookapp/ScanService.kt').read_text()
A = (R/'app/src/main/java/com/example/myebookapp/ScanSessionAuthority.kt').read_text()

class Model:
    def __init__(self): self.serial = 0; self.current = None; self.owner = None
    def request(self):
        self.serial += 1; self.current = self.serial; self.owner = None; return self.serial
    def bind(self, t, o):
        if t is None or t != self.current or self.owner not in (None, o): return False
        self.owner = o; return True
    def callback(self, t, o):
        if t != self.current or self.owner != o or t is None: return False
        self.current = self.owner = None; return True
    def stop(self): self.current = self.owner = None

class ServiceRaces(unittest.TestCase):
    def test_feature_disabled_in_both_user_and_service_gates(self):
        self.assertIn('private const val LIFECYCLE_EXPERIMENT_ENABLED = false', S)
        self.assertGreaterEqual(S.count('if (!LIFECYCLE_EXPERIMENT_ENABLED)'), 2)
        self.assertIn('LIFECYCLE_EXPERIMENT_ENABLED && safety.canAdmit() && sessions.bind', S)
    def test_token_not_persisted_and_null_old_intents_rejected(self):
        self.assertIn('SecureRandom().nextBytes(bytes)', A)
        self.assertNotIn('SharedPreferences', A)
        self.assertIn('token == null || token != current', A)
        self.assertIn('intent?.getStringExtra(EXTRA_SESSION)', V)
        self.assertIn('START_NOT_STICKY', V)
    def test_permission_result_cannot_revive_predeath_action(self):
        ui = (R/'app/src/main/java/com/example/myebookapp/MainActivity.kt').read_text()
        self.assertIn('pendingScanAction by remember {', ui)
        self.assertNotIn('pendingScanAction by rememberSaveable', ui)
        self.assertIn('pendingScanAction = null; pendingScanFolder = null; LibraryScanner.stopScan', ui)
    def test_user_pause_stop_and_uncertain_commit_revoke(self):
        self.assertIn('save(app, STOPPED, emptyList(), "stop")', S)
        self.assertIn('save(app, PAUSED, roots(app), "pause"); sessions.disarm(); generation++', S)
        self.assertIn('safety.failedCommit()\n        sessions.disarm()', S)
        self.assertIn('fun resumeMetadataOnly(context: Context) = scanFolders(context, emptyList())', S)
    def test_stale_callbacks_and_retiring_are_fenced(self):
        self.assertIn('!sessions.retire(request, owner)', S)
        self.assertIn('if (!sessions.owns(request, owner))', S)
        self.assertIn('if (retiring)', V)
        self.assertIn('if (stopSelfResult(startId)) retiring = true', V)
        self.assertIn('startId != latestDeliveredStartId', V)
        self.assertIn('ensureWorker(token, latestStartId)', V)
    def test_restore_and_progress_do_not_override_new_request(self):
        self.assertIn('!sessions.isDisarmed()) return\n            safety.beginRestore()', S)
        self.assertIn('if (generation == token) _status.update', S)
        self.assertIn('sessions.owns(request, owner)) action(_status.value)', S)
    def test_process_recreation(self):
        old = Model(); t = old.request(); self.assertTrue(old.bind(t, 'old'))
        fresh = Model(); self.assertFalse(fresh.bind(None, 'new')); self.assertFalse(fresh.bind(t, 'new'))
    def test_delayed_old_start_destroy_and_completion(self):
        m = Model(); a = m.request(); m.bind(a,'same'); b = m.request(); m.bind(b,'same')
        self.assertFalse(m.bind(a,'same'))
        self.assertFalse(m.callback(a,'same')); self.assertFalse(m.callback(a,'same'))
        self.assertEqual(m.current, b); self.assertEqual(m.owner, 'same')
    def test_pause_stop_and_launch_failure(self):
        for _ in ('pause','stop','failed launch'):
            m = Model(); t = m.request(); m.bind(t,'s'); m.stop()
            self.assertFalse(m.bind(t,'s')); self.assertFalse(m.callback(t,'s'))
            n = m.request(); self.assertTrue(m.bind(n,'s'))
    def test_stale_launch_failure_cannot_stop_new(self):
        m = Model(); a = m.request(); m.bind(a,'old'); b = m.request()
        self.assertFalse(m.callback(a,'old')); self.assertEqual(m.current,b)
        self.assertTrue(m.bind(b,'new'))
