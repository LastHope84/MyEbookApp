"""Safety regression checks for the in-progress provenance integration.

SQLite exercises the actual historical M56 DDL in isolation; other checks inspect
Kotlin source, not a running Android app, Room, or a live document provider. A
sighting is historical evidence, not a complete or current ownership snapshot.
"""
from pathlib import Path
import re
import sqlite3
import unittest

BASE = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/example/myebookapp'


def source(name):
    return (BASE / name).read_text(encoding='utf-8')


def section(text, start, end):
    return text.split(start, 1)[1].split(end, 1)[0]


class ProvenanceIntegrationSafety(unittest.TestCase):
    def setUp(self):
        self.db_source = source('BookDatabase.kt')
        migration = section(self.db_source, 'internal val M56', 'internal val M67')
        statements = re.findall(r'db\.execSQL\("([^"\n]+)"\)', migration)
        self.db = sqlite3.connect(':memory:')
        self.addCleanup(self.db.close)
        self.db.execute('PRAGMA foreign_keys=ON')
        self.db.execute('CREATE TABLE books(id INTEGER PRIMARY KEY, uriString TEXT UNIQUE, fileName TEXT, fileSize INTEGER)')
        self.db.executemany('INSERT INTO books VALUES(?,?,?,?)', [
            (1, 'content://alpha/document/legacy', 'same.epub', 100),
            (2, 'content://beta/document/new', 'same.epub', 100),
        ])
        for sql in statements:
            self.db.execute(sql)
        self.db.commit()

    def test_legacy_rows_are_not_backfilled_by_migration_or_name_size(self):
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM books').fetchone()[0], 2)
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM scan_book_origins').fetchone()[0], 0)
        self.db.execute('INSERT INTO scan_book_origins VALUES (2,?,?,?)',
                        ('content://beta/tree/root', 'parent', 'new'))
        # Exact book association, never the fact that file names and sizes match.
        self.assertIsNone(self.db.execute('SELECT bookId FROM scan_book_origins WHERE bookId=1').fetchone())
        rows = self.db.execute('SELECT b.id,o.documentId FROM books b LEFT JOIN scan_book_origins o ON o.bookId=b.id ORDER BY b.id').fetchall()
        self.assertEqual(rows, [(1, None), (2, 'new')])

    def test_sightings_preserve_exact_grant_parent_and_document_identity(self):
        rows = [
            ('content://alpha/tree/root', 'a', 'opaque/file', 0),
            ('content://alpha/tree/root', 'b', 'opaque/file', 0),
            ('content://alpha/tree/other', 'a', 'opaque/file', 0),
            ('content://beta/tree/root', 'a', 'opaque/file', 0),
            ('content://alpha/tree/root', 'a', 'opaque/file-longer', 0),
        ]
        self.db.executemany('INSERT OR IGNORE INTO scan_sightings VALUES (?,?,?,?)', rows + rows)
        result = self.db.execute('SELECT rootUri,parentDocumentId,documentId,isDirectory FROM scan_sightings').fetchall()
        self.assertCountEqual(result, rows)
        # Distinct grants preserve authority as part of their exact URI; similar IDs
        # are not prefixes. This table stores edges, not a completeness guarantee.
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM scan_sightings WHERE rootUri=? AND parentDocumentId=? AND documentId=?', rows[0][:3]).fetchone()[0], 1)

    def test_identity_matches_do_not_create_new_book_origins(self):
        batch = section(self.db_source, '@Transaction suspend fun insertScannedBatch', '@Insert(onConflict')
        self.assertNotIn('key in knownKeys', batch)
        self.assertIn('bookIdsForScanIdentities(authority, chunk)', batch)
        self.assertIn('candidateIds.size > 1', batch)
        self.assertIn('insertUnresolvedScanRoute(ScanUnresolvedRoute', batch)
        self.assertIn('insertScanBookRoute(route.copy(bookId = exact.id))', batch)
        self.assertIn('insertScanBookRoute(route.copy(bookId = candidateIds.single()))', batch)
        self.assertLess(batch.index('if (id == -1L) continue'), batch.index('origins.add(ScanBookOrigin'))
        self.assertIn('insertScanSightings(allowed.map { it.sighting })', batch)

    def test_new_book_origin_requires_verified_route_but_insert_count_does_not(self):
        # Source contract only: Android Room instrumentation exercises real behavior.
        batch = section(self.db_source, '@Transaction suspend fun insertScannedBatch', '    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(')
        self.assertIn('val route = verifiedScanRoute(item, authority)', batch)
        self.assertIn('''            if (route != null) {
                insertScanBookRoute(route.copy(bookId = id))
                recordScanOwned(ScanOwnedBook(id))
                recordMembership(ScanMembership(id, root, inventoryGeneration ?: 0L))
                identityIdsByDocument.getOrPut(route.documentId) { mutableSetOf() }.add(id)
                val sighting = item.sighting
                origins.add(ScanBookOrigin(id, sighting.rootUri, sighting.parentDocumentId, sighting.documentId))
            }''', batch)
        self.assertIn('inserted++', batch)
        self.assertIn('return ScannedBatchResult(inserted, emptySet())', batch)
        self.assertLess(batch.index('if (id == -1L) continue'), batch.index('inserted++'))
        self.assertLess(batch.index('inserted++'), batch.index('if (route != null) {\n                insertScanBookRoute(route.copy(bookId = id))'))

    def test_ui_uses_read_only_adapter_and_default_disabled(self):
        host = source('FolderManagementHost.kt')
        ui = source('FolderManagementUi.kt')
        activity = source('MainActivity.kt')
        self.assertIn('ReadOnlyFolderManagementAdapter(ctx)', activity)
        self.assertIn('override val canRemove = false', host)
        self.assertIn('override suspend fun remove(target: FolderManagementRemoval): FolderManagementOutcome = error(', host)
        self.assertIn('val canRemove: Boolean = false', ui)
        self.assertIn('if (adapter.canRemove) mutate { adapter.remove(target) }', host)

    def test_removal_fails_closed_and_does_not_delete_provider_files(self):
        core = source('FolderRemovalCore.kt')
        scanner = source('LibraryScanner.kt')
        host = source('FolderManagementHost.kt')
        remove = section(core, 'suspend fun remove(', 'suspend fun exclude(')
        self.assertIn('error("Folder removal unavailable:', remove)
        self.assertNotIn('commitRootExclusion(', remove)
        self.assertNotIn('commitFolderExclusion(', remove)
        self.assertNotIn('deleteById', remove)
        # The scanner may delete only its own unreferenced generated cover files;
        # don't confuse those with original SAF ebooks.
        for code in (core, scanner, host):
            for forbidden in ('deleteDocument(', 'contentResolver.delete(', 'openOutputStream('):
                self.assertNotIn(forbidden, code)

    def test_route_and_document_types_carry_provider_and_observed_paths(self):
        route = source('ScanRouteOwnership.kt')
        self.assertIn('data class RouteDocument(val authority: String, val documentId: String)', route)
        self.assertIn('data class ScanRoute(val rootId: String, val directories: List<RouteDocument>, val file: RouteDocument)', route)
        self.assertIn('book.hasUnknownLegacyOwner -> RouteRetention.KEEP_UNKNOWN', route)
        self.assertIn('require(book.routes.toSet() == expected)', route)
        self.assertIn('NO_REMAINING_SCANNED_ROUTE // A diagnostic only, NOT permission', route)


if __name__ == '__main__':
    unittest.main()
