"""Source-level guardrails for the guide; not a substitute for Android UI tests."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/example/myebookapp'
GUIDE = (SRC / 'UserGuideScreen.kt').read_text()
MAIN = (SRC / 'MainActivity.kt').read_text()


class GuideContractTest(unittest.TestCase):
    def test_topic_ids_are_unique_and_complete(self):
        ids = re.findall(r'GuideTopic\("([a-z_]+)",', GUIDE)
        self.assertEqual(ids, ['start', 'shelves', 'reading', 'reader_settings',
                               'metadata', 'convert', 'share', 'settings', 'safety'])
        self.assertEqual(len(ids), len(set(ids)))

    def test_drawer_and_two_level_back_navigation(self):
        self.assertIn('Text("User Guide")', MAIN)
        self.assertIn('UserGuideScreen(onClose = { showGuide = false })', MAIN)
        self.assertIn('BackHandler { goBack() }', GUIDE)
        self.assertIn('if (topicId != null) topicId = null else onClose()', GUIDE)
        self.assertIn('rememberSaveable', GUIDE)
        self.assertIn('modifier = Modifier.semantics { heading() }', GUIDE)

    def test_safety_and_manual_shelf_claims_have_source_guards(self):
        self.assertIn('does not automatically move it', GUIDE)
        self.assertIn('status != null && status !in setOf("NEW", "TBR", "READING", "FINISHED")', MAIN)
        self.assertIn('Original file unchanged.', MAIN)
        self.assertIn('Original kept.', MAIN)
        self.assertIn('not connected in this version', GUIDE)
        self.assertIn('{ThemePrefs.saveKindleEmail(ctx,it)}, {}, {}, {}, {showSet=false}', MAIN)
        self.assertIn('not written into the attachment', GUIDE)

    def test_guide_does_not_change_ebook_data(self):
        for token in ('bookDao(', 'contentResolver', 'deleteById(', 'openOutputStream('):
            self.assertNotIn(token, GUIDE)


if __name__ == '__main__':
    unittest.main()
