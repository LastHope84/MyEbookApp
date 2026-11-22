"""Source guardrails only; Android preferences, UI and inference need device tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/example/myebookapp'
PREFS = (SRC / 'AppTheme.kt').read_text()
UI = (SRC / 'AppSettingsSheet.kt').read_text()
PACK_UI = (SRC / 'TranslationPackSettings.kt').read_text()
BASE = (ROOT / 'app/src/nofoxlet/java/com/example/myebookapp/TranslationRuntime.kt').read_text()
EXPERIMENTAL = (ROOT / 'app/src/foxlet/java/com/example/myebookapp/TranslationRuntime.kt').read_text()
ENGINE = (ROOT / 'app/src/foxlet/java/com/example/myebookapp/OfflineTranslationEngine.kt').read_text()
GRADLE = (ROOT / 'app/build.gradle.kts').read_text()

class TranslationSettingContractTest(unittest.TestCase):
    def test_baseline_is_fail_closed_and_isolated(self):
        self.assertIn('fun isAvailable(): Boolean = false', BASE)
        self.assertIn('src/nofoxlet/java', GRADLE)
        self.assertIn('if (foxletAar != null)', GRADLE)
        self.assertIn('else 24', GRADLE)
        self.assertIn('"armeabi-v7a"', GRADLE)
        self.assertIn('applicationIdSuffix = ".foxlet"', GRADLE)
    def test_preference_defaults_off_and_toggle_has_no_pack_effects(self):
        getter = PREFS.split('fun isOfflineTranslationEnabled(context: Context): Boolean =', 1)[1].split('fun saveOfflineTranslationEnabled', 1)[0]
        self.assertIn('TranslationAvailability.isAvailable &&', getter)
        self.assertIn('getBoolean(KEY_OFFLINE_TRANSLATION, false)', getter)
        save = PREFS.split('fun saveOfflineTranslationEnabled(context: Context, enabled: Boolean)', 1)[1].split('fun getKindleEmail', 1)[0]
        self.assertIn('if (enabled && !TranslationAvailability.isAvailable) return', save)
        for token in ('download', 'delete', 'remove(', 'clear(', 'File(', 'pack'):
            self.assertNotIn(token, save)
    def test_only_explicit_download_can_use_network(self):
        self.assertIn('PreparePolicy.LocalOnly', ENGINE.split('suspend fun translate(source:', 1)[1])
        self.assertIn('PreparePolicy.LocalOrBundled', ENGINE.split('suspend fun download(', 1)[1].split('suspend fun translate(', 1)[0])
        self.assertIn('AlertDialog(', PACK_UI)
        self.assertIn('TranslationRuntime.downloadPack', PACK_UI)
        self.assertIn('prompt = "download" to pack', PACK_UI)
        self.assertIn('ThemePrefs.isOfflineTranslationEnabled(context)', EXPERIMENTAL)
    def test_removal_confirms_sharing_and_checks_partial_failure(self):
        self.assertIn('TranslationRuntime.sharedRoutes', PACK_UI)
        self.assertIn('These routes may also stop working', PACK_UI)
        self.assertIn('DeleteStatus.PartiallyDeleted', EXPERIMENTAL)
        self.assertIn('DeleteStatus.Failed', EXPERIMENTAL)
        self.assertIn('check(unload.allReleased)', EXPERIMENTAL)
    def test_pack_status_is_not_claimed_verified(self):
        self.assertIn('installation metadata', PACK_UI)
        self.assertIn('not yet verified', PACK_UI)
        self.assertIn('Models download only when requested', UI)

if __name__ == '__main__':
    unittest.main()
