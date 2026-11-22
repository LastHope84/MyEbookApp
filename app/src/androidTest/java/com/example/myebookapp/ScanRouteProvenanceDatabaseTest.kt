package com.example.myebookapp

import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual DAO/Room tests; these do not simulate a DocumentsProvider or prove route completeness. */
@RunWith(AndroidJUnit4::class)
class ScanRouteProvenanceDatabaseTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: BookDao
    private val root = "content://provider/tree/root"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        dao = db.bookDao()
    }
    @After fun close() = db.close()

    private fun uri(root: String, document: String) =
        DocumentsContract.buildDocumentUriUsingTree(Uri.parse(root), document).toString()
    private fun book(root: String, document: String, name: String = "$document.epub", size: Long = 42) = BookEntity(
        uriString = uri(root, document), fileName = name, format = "EPUB", fileSize = size,
        lastModified = 0, title = name, author = "", publishingDate = "", publisher = "", language = "",
        coverPath = null, lastLocatorJson = null, progressPercent = 0.0, lastOpenedAt = 0, metadataExtracted = false
    )
    private fun item(root: String, document: String, path: List<String>, entry: BookEntity = book(root, document)) =
        ScannedItem(ScanSighting(root, path.last(), document, false), entry, path)

    @Test fun insertsAndExactLegacyMatchRecordOnlyObservedPaths() = runBlocking {
        val document = "opaque:/id,雪"
        val pathA = listOf("root", "a:one")
        val pathB = listOf("root", "b/two")
        val first = item(root, document, pathA)
        assertEquals(1, dao.insertScannedBatch(listOf(first), emptySet(), "provider").inserted)
        val inserted = requireNotNull(dao.getByUri(first.book!!.uriString))
        assertEquals(ScanBookOrigin(inserted.id, root, pathA.last(), document), dao.getScanBookOrigin(inserted.id))
        val known = dao.getAllNameSizeKeys().toSet()
        assertEquals(0, dao.insertScannedBatch(listOf(item(root, document, pathB), first), known, "provider").inserted)
        val routes = dao.scanBookRoutes(inserted.id)
        assertEquals(2, routes.size)
        assertEquals(setOf(JSONArray(pathA).toString(), JSONArray(pathB).toString()), routes.map { it.directoryPath }.toSet())
        assertTrue(routes.all { it.authority == "provider" && it.documentId == document && it.rootUri == root })
        assertTrue(dao.unresolvedScanRoutes().isEmpty())

        // A manual/legacy row receives a positive exact sighting but is not made a scanner origin.
        val legacy = book(root, "legacy", "legacy.epub", 99)
        val id = dao.insertIgnore(legacy)
        assertTrue(dao.scanBookRoutes(id).isEmpty()) // unknown, not a claim of no independent owner
        assertEquals(0, dao.insertScannedBatch(listOf(item(root, "legacy", listOf("root"))), setOf("legacy.epub|99"), "provider").inserted)
        assertEquals(1, dao.scanBookRoutes(id).size)
        assertNull(dao.getScanBookOrigin(id))
    }

    @Test fun sameIdentityRecordsRouteAcrossGrants() = runBlocking {
        val first = item(root, "same-doc", listOf("root"))
        dao.insertScannedBatch(listOf(first), emptySet(), "provider")
        val bookId = requireNotNull(dao.getByUri(first.book!!.uriString)).id
        val secondRoot = "content://provider/tree/other"
        val alias = item(secondRoot, "same-doc", listOf("other"), book(secondRoot, "same-doc"))
        assertEquals(0, dao.insertScannedBatch(listOf(alias), dao.getAllNameSizeKeys().toSet(), "provider").inserted)
        assertNull(dao.getByUri(alias.book!!.uriString))
        assertEquals(2, dao.scanBookRoutes(bookId).size)
        assertTrue(dao.scanBookRoutes(bookId).any { it.rootUri == secondRoot && it.documentId == "same-doc" && it.directoryPath == JSONArray(listOf("other")).toString() })
        assertTrue(dao.unresolvedScanRoutes().isEmpty())
    }

    @Test fun invalidIdentityAndExcludedPathsNeverCreateBookEvidence() = runBlocking {
        val mismatch = item(root, "observed", listOf("root"), book(root, "different"))
        val result = dao.insertScannedBatch(listOf(mismatch), emptySet(), "provider")
        assertEquals(1, result.inserted)
        assertTrue(result.acceptedKeys.isEmpty())
        val id = requireNotNull(dao.getByUri(mismatch.book!!.uriString)).id
        assertNull(dao.getScanBookOrigin(id))
        assertTrue(dao.scanBookRoutes(id).isEmpty())
        dao.commitRootExclusion(ScanRootExclusion(root, "blocked"))
        val blocked = item(root, "blocked-doc", listOf("root", "blocked"))
        dao.insertScannedBatch(listOf(blocked), emptySet(), "provider")
        assertNull(dao.getByUri(blocked.book!!.uriString))
        assertTrue(dao.getScanSightings(root, "blocked").isEmpty())
        assertTrue(dao.unresolvedScanRoutes().isEmpty())
    }

    @Test fun routeWriteFailureRollsBackBookAndSightings() = runBlocking {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_route BEFORE INSERT ON scan_book_routes BEGIN SELECT RAISE(ABORT, 'forced'); END")
        try {
            dao.insertScannedBatch(listOf(item(root, "rollback", listOf("root"))), emptySet(), "provider")
            fail("Expected the route write to abort")
        } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(0, dao.countAllBooks())
        assertTrue(dao.getScanSightings(root, "root").isEmpty())
        assertTrue(dao.unresolvedScanRoutes().isEmpty())
    }
}
