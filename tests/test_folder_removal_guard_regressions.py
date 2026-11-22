"""Host-side source contracts; not a substitute for Android/provider integration tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/example/myebookapp'
CORE = (ROOT / 'FolderRemovalCore.kt').read_text()
SCAN = (ROOT / 'LibraryScanner.kt').read_text()

class FolderRemovalGuardContracts(unittest.TestCase):
    def test_scanner_origin_is_not_treated_as_exclusive_ownership(self):
        self.assertIn('NOT evidence of exclusive book ownership', CORE)
        self.assertIn('FolderRemovalResult(0, dao.countAllBooks(), 0', CORE)
        self.assertNotIn('deleteById', CORE)
        self.assertNotIn('deleteDocument(', CORE)

    def test_selection_must_be_in_the_specific_grant(self):
        self.assertIn('require(selectedTree.toString() in rootUris)', CORE)
        self.assertIn('collect(context, selectedTree, DocumentsContract.getTreeDocumentId(selectedTree))', CORE)
        guard = 'check(SafDocumentKey(authority, directoryId) in grantRoot.directories)'
        self.assertIn(guard, CORE)
        self.assertLess(CORE.index(guard), CORE.index('dao.commitRootExclusion('))

    def test_scanner_uses_cycle_guard_and_keeps_exclusion(self):
        self.assertIn('val seen = mutableSetOf<String>()', SCAN)
        self.assertIn('if (!seen.add(parentId)) continue', SCAN)
        self.assertIn('documentId !in blocked && documentId !in seen && documentId !in path', SCAN)
        self.assertIn('if (path.any { it in blocked }) continue', SCAN)

    def test_exclusion_does_not_cancel_the_live_scan_session(self):
        section = SCAN.split('suspend fun excludeFolderFromScans', 1)[1].split('private suspend fun walkTree', 1)[0]
        for forbidden in ('activeJob?.cancel()', 'reportStartFailure(', 'processingMutex', 'stopScan('):
            self.assertNotIn(forbidden, section)
        self.assertIn('FolderRemovalCore.exclude(app, treeUri, directoryId, rootUris)', section)

if __name__ == '__main__': unittest.main()
