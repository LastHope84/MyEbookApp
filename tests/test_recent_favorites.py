import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DAO = (ROOT / 'app/src/main/java/com/example/myebookapp/BookDatabase.kt').read_text()
MAIN = (ROOT / 'app/src/main/java/com/example/myebookapp/MainActivity.kt').read_text()

def query_for(name):
    match = re.search(r'@Query\("([^"]+)"\)\s*(?:suspend )?fun ' + name + r'\(', DAO)
    if not match:
        raise AssertionError('Missing query: ' + name)
    return match.group(1)

class RecentAndFavorites(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.execute('CREATE TABLE books (id INTEGER PRIMARY KEY, title TEXT, isFavorite INTEGER, readingStatus TEXT)')
        self.db.execute('CREATE VIRTUAL TABLE books_fts USING fts4(title)')
        for i in range(1, 71):
            title = 'needle' if i in (1, 30, 69) else 'other'
            self.db.execute('INSERT INTO books VALUES (?, ?, ?, ?)', (i, title, i % 2, 'READING' if i % 3 == 0 else 'TBR'))
            self.db.execute('INSERT INTO books_fts(rowid,title) VALUES (?,?)', (i,title))
        self.db.commit()

    def test_recent_and_search_apply_limit_before_search(self):
        rows = self.db.execute(query_for('recentlyAddedPaged')).fetchall()
        self.assertEqual([row[0] for row in rows], list(range(70,20,-1)))
        self.assertEqual([x[0] for x in self.db.execute(query_for('getRecentlyAddedIds'))], list(range(70,20,-1)))
        self.assertEqual([x[0] for x in self.db.execute(query_for('searchRecentlyAddedPaged'), {'q':'needle'})], [69,30])
        self.assertEqual([x[0] for x in self.db.execute(query_for('searchRecentlyAddedIds'), {'q':'needle'})], [69,30])

    def test_favorite_toggle_is_individual_and_rolls_back(self):
        sql = query_for('toggleFavoritesChunk').replace(':ids', '?,?')
        self.db.execute(sql, (1, 2))
        self.assertEqual(self.db.execute('SELECT isFavorite FROM books WHERE id IN (1,2) ORDER BY id').fetchall(), [(0,), (1,)])
        self.db.rollback()
        self.assertEqual(self.db.execute('SELECT isFavorite FROM books WHERE id IN (1,2) ORDER BY id').fetchall(), [(1,), (0,)])
        self.assertIn('@Transaction suspend fun toggleFavorites', DAO)
        self.assertIn('unique.chunked(BULK_ID_CHUNK_SIZE)', DAO)
        self.assertIn('check(count == unique.size)', DAO)

    def test_counts_and_ui_wiring(self):
        self.assertEqual(self.db.execute(query_for('observeFavoriteCount')).fetchone()[0], 35)
        self.assertEqual(self.db.execute(query_for('observeStatusCount'), {'status':'READING'}).fetchone()[0], 23)
        for token in ('badge={Text("$count")}', 'badge={Text("${shelf.bookCount}")}', 'dao.toggleFavorites(ids)', 'dao.searchRecentlyAddedIds(f)', 'dao.searchRecentlyAddedPaged(f)'):
            self.assertIn(token, MAIN)

if __name__ == '__main__':
    unittest.main()
