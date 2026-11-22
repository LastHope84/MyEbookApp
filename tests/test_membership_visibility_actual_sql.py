"""Execute the actual DAO SQL on host SQLite; not a Room/SAF/device test."""
import re, sqlite3, unittest
from pathlib import Path

S = (Path(__file__).resolve().parents[1] / 'app/src/main/java/com/example/myebookapp/BookDatabase.kt').read_text()
Q = dict((name, sql) for sql, name in re.findall(r'@Query\("([^"\n]*)"\) (?:suspend )?fun (\w+)\(', S))
M = S.split('internal val M1011', 1)[1].split('internal val M910', 1)[0]

class MembershipSql(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.execute('PRAGMA foreign_keys=ON')
        self.db.executescript('''
          CREATE TABLE books(id INTEGER PRIMARY KEY, uriString TEXT, fileName TEXT, format TEXT, fileSize INTEGER, lastModified INTEGER, title TEXT, author TEXT, publishingDate TEXT, publisher TEXT, language TEXT, coverPath TEXT, lastLocatorJson TEXT, progressPercent REAL, lastOpenedAt INTEGER, metadataExtracted INTEGER, series TEXT, seriesIndex REAL, tags TEXT, isFavorite INTEGER, readingStatus TEXT);
          CREATE TABLE scan_book_routes(bookId INTEGER,rootUri TEXT,authority TEXT,documentId TEXT,directoryPath TEXT);
          CREATE TABLE scan_unresolved_routes(rootUri TEXT,authority TEXT,documentId TEXT,directoryPath TEXT);
          CREATE TABLE conversion_links(sourceBookId INTEGER, outputBookId INTEGER);
          CREATE TABLE custom_shelves(id INTEGER PRIMARY KEY, name TEXT);
          CREATE TABLE custom_shelf_books(shelfId INTEGER,bookId INTEGER);
          CREATE VIRTUAL TABLE books_fts USING fts4(author,publisher,series,tags,title);
        ''')
        self.db.execute('CREATE INDEX index_scan_book_routes_authority_documentId ON scan_book_routes(documentId)')
        for sql in re.findall(r'db\.execSQL\("([^"\n]+)"\)', M): self.db.execute(sql)
        for n in range(1,6):
            self.db.execute("INSERT INTO books(id,uriString,fileName,format,fileSize,title,author,publisher,lastOpenedAt,metadataExtracted,isFavorite,readingStatus) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", (n,str(n),str(n),'EPUB',50,'needle','author','pub',n,0,1,'READING'))
            self.db.execute("INSERT INTO books_fts(rowid,title,author,publisher) VALUES (?, 'needle','author','pub')", (n,))
        self.db.execute("INSERT INTO custom_shelves VALUES(1,'shelf')")
        self.db.executemany('INSERT INTO custom_shelf_books VALUES(1,?)', [(n,) for n in range(1,6)])
        self.db.execute("INSERT INTO scan_inventory_roots VALUES('removed',1,1),('active',1,1)")
        self.db.executemany('INSERT INTO scan_owned_books VALUES(?)', [(1,),(2,),(3,)])
        self.db.execute('INSERT INTO independent_books VALUES(3)')
        self.db.execute("INSERT INTO scan_memberships VALUES(1,'removed',1),(2,'removed',1),(2,'active',1),(3,'removed',1),(4,'removed',1)")
    def tearDown(self): self.db.close()
    def test_exclusive_known_only_and_reversible(self):
        self.db.execute(Q['hideExclusiveScanMembers'], {'root':'removed','generation':1})
        self.assertEqual([(1,'removed')], self.db.execute('SELECT * FROM hidden_scan_books').fetchall())
        self.assertEqual(5, self.db.execute('SELECT count(*) FROM books').fetchone()[0])
        args={'showConvertedOriginals':1,'q':'needle*','status':'READING','shelfId':1}
        for name in ('getAllIds','getFavoriteIds','getStatusIds','getCustomShelfBookIds','getRecentIds','getRecentlyAddedIds','searchAllIds','searchCustomShelfIds','searchStatusIds','searchRecentIds','searchRecentlyAddedIds'):
            with self.subTest(name=name): self.assertNotIn(1,[x[0] for x in self.db.execute(Q[name],args)])
        self.assertEqual(4,self.db.execute(Q['observeTotalCount'],args).fetchone()[0])
        self.assertEqual(4,self.db.execute(Q['observeCustomShelvesWithCounts'],args).fetchone()[2])
        self.db.execute('DELETE FROM hidden_scan_books')
        self.assertIn(1,[x[0] for x in self.db.execute(Q['getAllIds'],args)])
        self.assertEqual(5,self.db.execute(Q['observeTotalCount'],args).fetchone()[0])
    def test_stale_target_membership_is_not_hidden(self):
        self.db.execute("UPDATE scan_memberships SET seenGeneration=0 WHERE bookId=1")
        self.db.execute(Q['hideExclusiveScanMembers'], {'root':'removed','generation':1})
        self.assertEqual([], self.db.execute('SELECT * FROM hidden_scan_books').fetchall())
    def test_unresolved_identity_stays_visible(self):
        self.db.execute("INSERT INTO scan_book_routes VALUES(1,'removed','p','x','[]')")
        self.db.execute("INSERT INTO scan_unresolved_routes VALUES('removed','p','x','[]')")
        self.db.execute(Q['hideExclusiveScanMembers'], {'root':'removed','generation':1})
        self.assertEqual([],self.db.execute('SELECT * FROM hidden_scan_books').fetchall())
    def test_hidden_conversion_output_reveals_original(self):
        self.db.execute('INSERT INTO conversion_links VALUES(2,1)')
        self.db.execute(Q['hideExclusiveScanMembers'], {'root':'removed','generation':1})
        rows=self.db.execute(Q['getAllIds'],{'showConvertedOriginals':0}).fetchall()
        self.assertIn((2,),rows)
    def test_migration_index_and_no_backfill(self):
        cols=[x[2] for x in self.db.execute('PRAGMA index_info(index_scan_book_routes_authority_documentId)')]
        self.assertEqual(['authority','documentId'],cols)
        self.assertEqual(0,self.db.execute('SELECT COUNT(*) FROM hidden_scan_books').fetchone()[0])
        self.assertIn('inventoryState()?.generation == generation', S)
        self.assertIn('roots.all { it.complete && it.generation == generation }', S)

if __name__=='__main__': unittest.main()
