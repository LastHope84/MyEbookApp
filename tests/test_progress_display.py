"""A book on the Finished shelf must be displayed as 100% read."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/example/myebookapp'


class ProgressDisplayTest(unittest.TestCase):
    def test_finished_books_display_full_progress(self):
        helper = (SRC / 'BookProgress.kt').read_text()
        self.assertIn('readingStatus == "FINISHED"', helper)
        self.assertIn('fun displayedProgressPercent(', helper)
        self.assertIn('fun displayedProgressFraction(', helper)

    def test_every_progress_display_uses_the_helper(self):
        components = (SRC / 'LibraryComponents.kt').read_text()
        details = (SRC / 'BookDetailsSheet.kt').read_text()
        self.assertEqual(components.count('displayedProgressPercent(book)'), 2)
        self.assertIn('displayedProgressFraction(book)', components)
        self.assertIn('displayedProgressPercent(b)', details)
        for text in (components, details):
            self.assertNotIn('(book.progressPercent * 100)', text)
            self.assertNotIn('(b.progressPercent * 100)', text)


if __name__ == '__main__':
    unittest.main()
