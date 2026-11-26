"""The changelog must lead with the version the app is built as."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GRADLE = (ROOT / 'app/build.gradle.kts').read_text()
CHANGELOG = (ROOT / 'CHANGELOG.md').read_text()
README = (ROOT / 'README.md').read_text()
VERSIONS = re.findall(r'^## \[([0-9]+(?:\.[0-9]+)*)\]', CHANGELOG, re.M)


class ChangelogTest(unittest.TestCase):
    def test_top_entry_matches_the_app_version(self):
        version = re.search(r'versionName = "([^"]+)"', GRADLE).group(1)
        self.assertEqual(version, VERSIONS[0])

    def test_versions_are_unique_and_descending(self):
        self.assertEqual(len(VERSIONS), len(set(VERSIONS)))
        keyed = [tuple(int(part) for part in v.split('.')) for v in VERSIONS]
        self.assertEqual(keyed, sorted(keyed, reverse=True))

    def test_readme_links_to_the_changelog(self):
        self.assertIn('[CHANGELOG.md](CHANGELOG.md)', README)

    def test_every_version_has_a_section_body(self):
        sections = re.split(r'^## \[', CHANGELOG, flags=re.M)[1:]
        for section in sections:
            body = section.split('\n', 1)[1].strip()
            self.assertTrue(body, section.split('\n', 1)[0])


if __name__ == '__main__':
    unittest.main()
