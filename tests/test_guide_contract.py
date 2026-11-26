"""Source-level guardrails for the guide; not a substitute for Android UI tests."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/example/myebookapp'
GUIDE = (SRC / 'UserGuideScreen.kt').read_text()
MAIN = (SRC / 'MainActivity.kt').read_text()
READER = (SRC / 'ReaderActivity.kt').read_text()
DAO = (SRC / 'BookDatabase.kt').read_text()
DETAILS = (SRC / 'BookDetailsSheet.kt').read_text()
COMPONENTS = (SRC / 'LibraryComponents.kt').read_text()
DOC = (ROOT / 'docs/USER_GUIDE.md').read_text()


class GuideContractTest(unittest.TestCase):
    def test_topic_ids_are_unique_and_complete(self):
        ids = re.findall(r'GuideTopic\("([a-z_]+)",', GUIDE)
        self.assertEqual(ids, ['start', 'shelves', 'reading', 'comics', 'pdf', 'reader_settings',
                               'metadata', 'convert', 'share', 'settings', 'safety', 'licenses'])
        self.assertEqual(len(ids), len(set(ids)))

    def test_drawer_and_two_level_back_navigation(self):
        self.assertIn('Text("User Guide")', MAIN)
        self.assertIn('UserGuideScreen(initialTopic = guideTopic, onClose = { showGuide = false; guideTopic = null })', MAIN)
        self.assertIn('guideTopic = "licenses"', MAIN)
        self.assertIn('"Credits" to', GUIDE)
        self.assertIn('BackHandler { goBack() }', GUIDE)
        self.assertIn('if (topicId != null) topicId = null else onClose()', GUIDE)
        self.assertIn('rememberSaveable', GUIDE)
        self.assertIn('modifier = Modifier.semantics { heading() }', GUIDE)

    def test_shelf_and_selection_guides_match_source_and_each_other(self):
        for heading in ('Reading status', 'Custom shelves', 'Selection'):
            app_text = re.search(r'"' + re.escape(heading) + r'" to "([^"]+)"', GUIDE).group(1)
            doc_text = re.search(r'### ' + re.escape(heading) + r'\n([^\n]+)', DOC).group(1)
            self.assertEqual(app_text, doc_text)
        for text in (GUIDE, DOC):
            self.assertIn('When an EPUB opens successfully', text)
            self.assertIn('an Unclassified or To Read book moves to Reading', text)
            self.assertIn('a Finished book stays Finished when reopened', text)
            self.assertIn('mark a Reading book Finished', text)
            self.assertIn('including back matter', text)
            self.assertIn('may miss some layouts', text)
            self.assertIn('current shelf matching the active search', text)
            self.assertIn('books not yet loaded on screen', text)
            self.assertIn('Actions lets you change reading status', text)
            self.assertNotIn('no bulk-action buttons', text)
            self.assertNotIn('does not automatically move it', text)
        self.assertIn('markStarted(bookUri.toString())', READER)
        self.assertIn('markFinished(bookUri.toString())', READER)
        self.assertIn("readingStatus IN ('NEW', 'TBR')", DAO)
        self.assertIn("readingStatus = 'READING'\")\n    suspend fun markFinished", DAO)
        self.assertIn('selectAllOnShelf()', MAIN)
        self.assertIn('selectAllPrevious', MAIN)
        self.assertIn('SelectAllIcon(if (selectAllPrevious != null) "Restore previous selection" else "Select all")', MAIN)
        self.assertIn('contentDescription = description', COMPONENTS)
        self.assertIn('Text("Mark as read")', DETAILS)
        self.assertIn('onStatusChange("FINISHED")', DETAILS)
        self.assertIn('Mark as read', GUIDE)
        self.assertIn('Mark as read', DOC)
        for label in ('Change reading status', 'Move to custom shelf',
                      'Share selected files', 'Convert MOBI / AZW3 books'):
            self.assertIn('Text("' + label + '")', MAIN)

    def test_safety_claims_have_source_guards(self):
        self.assertIn('status != null && status !in setOf("NEW", "TBR", "READING", "FINISHED")', MAIN)
        self.assertIn('Original file unchanged.', MAIN)
        self.assertIn('Original kept.', MAIN)
        self.assertNotIn('not connected in this version', GUIDE)
        self.assertIn('convertedFolderP.launch(null)', MAIN)
        self.assertIn('ThemePrefs.saveConvertedOutputUri(ctx, null)', MAIN)
        self.assertIn('removeDuplicateBooks(dao)', MAIN)
        self.assertIn('didAutoOpenLastBook', MAIN)
        self.assertIn('{ showSet = false; showCleanDuplicates = true }', MAIN)
        self.assertIn('the original file is untouched', GUIDE)
        self.assertIn('BackupRestore.export', MAIN)
        self.assertIn('BackupRestore.restore', MAIN)
        self.assertIn('Back up', GUIDE)
        self.assertNotIn('has no visible export', GUIDE)

    def test_guide_does_not_change_ebook_data(self):
        for token in ('bookDao(', 'contentResolver', 'deleteById(', 'openOutputStream('):
            self.assertNotIn(token, GUIDE)

    def test_alphabet_index_is_documented_and_wired(self):
        for text in (GUIDE, DOC):
            self.assertIn('Alphabet index', text)
            self.assertIn('100 or more books', text)
            self.assertIn('hidden during search, on Recent, and while selecting books', text)
        self.assertIn('AlphabetIndexRail(', MAIN)
        self.assertIn('requestScrollToItem', MAIN)
        self.assertIn('jumpThreshold = 60', MAIN)
        self.assertIn('sortMode in setOf("title", "author")', MAIN)

    def test_imported_files_get_a_visible_copy(self):
        import_src = (SRC / 'ExternalImport.kt').read_text()
        self.assertIn('ImportCopies.save(context, dest)', import_src)
        self.assertIn('Downloads/MyEbookApp', GUIDE)
        self.assertIn('Downloads/MyEbookApp', DOC)


if __name__ == '__main__':
    unittest.main()
