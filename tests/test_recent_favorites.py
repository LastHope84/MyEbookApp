import re
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DAO = (ROOT / 'app/src/main/java/com/example/myebookapp/BookDatabase.kt').read_text()
MAIN = (ROOT / 'app/src/main/java/com/example/myebookapp/MainActivity.kt').read_text()
MIGRATION_45 = DAO.split('internal val M45', 1)[1].split('internal val M56', 1)[0]
STATEMENTS_45 = re.findall(r'db\.execSQL\("([^"]+)"\)', MIGRATION_45)

def query_for(name):
    match = re.search(r'@Query\("([^"]+)"\)\s*(?:suspend )?fun ' + name + r'\(', DAO)
    if not match:
        raise AssertionError('Missing query: ' + name)
    return match.group(1)

class RecentAndFavorites(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.execute('CREATE TABLE books (id INTEGER PRIMARY KEY, title TEXT, isFavorite INTEGER, readingStatus TEXT)')
        self.assertEqual(len(STATEMENTS_45), 2)
        for sql in STATEMENTS_45:
            self.db.execute(sql)
        self.db.execute('CREATE VIRTUAL TABLE books_fts USING fts4(title)')
        for i in range(1, 71):
            title = 'needle' if i in (1, 30, 69) else 'other'
            self.db.execute('INSERT INTO books VALUES (?, ?, ?, ?)', (i, title, i % 2, 'READING' if i % 3 == 0 else 'TBR'))
            self.db.execute('INSERT INTO books_fts(rowid,title) VALUES (?,?)', (i,title))
        self.db.commit()

    def test_recent_and_search_apply_limit_before_search(self):
        rows = self.db.execute(query_for('recentlyAddedPaged'), {'showConvertedOriginals': 0}).fetchall()
        self.assertEqual([row[0] for row in rows], list(range(70,20,-1)))
        self.assertEqual([x[0] for x in self.db.execute(query_for('getRecentlyAddedIds'), {'showConvertedOriginals': 0})], list(range(70,20,-1)))
        self.assertEqual([x[0] for x in self.db.execute(query_for('searchRecentlyAddedPaged'), {'q':'needle', 'showConvertedOriginals': 0})], [69,30])
        self.assertEqual([x[0] for x in self.db.execute(query_for('searchRecentlyAddedIds'), {'q':'needle', 'showConvertedOriginals': 0})], [69,30])

    def test_favorite_update_is_individual_and_rolls_back(self):
        sql = query_for('setFavoriteForIdsChunk').replace(':favorite', '?').replace(':ids', '?,?')
        self.db.execute(sql, (1, 1, 2))
        self.assertEqual(self.db.execute('SELECT isFavorite FROM books WHERE id IN (1,2) ORDER BY id').fetchall(), [(1,), (1,)])
        self.db.rollback()
        self.assertEqual(self.db.execute('SELECT isFavorite FROM books WHERE id IN (1,2) ORDER BY id').fetchall(), [(1,), (0,)])
        self.assertIn('@Transaction suspend fun setFavoriteForIds', DAO)
        self.assertIn('unique.chunked(BULK_ID_CHUNK_SIZE)', DAO)
        self.assertIn('check(count == unique.size)', DAO)

    def test_counts_and_ui_wiring(self):
        self.assertEqual(self.db.execute(query_for('observeFavoriteCount'), {'showConvertedOriginals': 0}).fetchone()[0], 35)
        self.assertEqual(self.db.execute(query_for('observeStatusCount'), {'status':'READING', 'showConvertedOriginals': 0}).fetchone()[0], 23)
        for token in ('badge={Text("$count")}', 'badge={Text("${shelf.bookCount}")}', 'dao.setFavoriteForIds(ids, add)', 'dao.searchRecentlyAddedIds(f, showConvertedOriginals)', 'dao.searchRecentPaged(f, showConvertedOriginals)'):
            self.assertIn(token, MAIN)

if __name__ == '__main__':
    unittest.main()
