package com.example.myebookapp

import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room tests for identity decisions, not proof of complete provider routes. */
@RunWith(AndroidJUnit4::class)
class ScannerIdentityDatabaseTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: BookDao
    private val rootA = "content://provider/tree/a"
    private val rootB = "content://provider/tree/b"
    private fun entry(root: String, doc: String, name: String = "same.epub") = BookEntity(
        uriString = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(root), doc).toString(),
        fileName = name, format = "EPUB", fileSize = 42, lastModified = 0, title = name,
        author = "", publishingDate = "", publisher = "", language = "", coverPath = null,
        lastLocatorJson = null, progressPercent = 0.0, lastOpenedAt = 0, metadataExtracted = false)
    private fun item(root: String, parent: String, doc: String, book: BookEntity = entry(root, doc)) =
        ScannedItem(ScanSighting(root, parent, doc, false), book, listOf(parent))
    private suspend fun scan(root: String, parent: String, doc: String, authority: String = "provider") =
        dao.insertScannedBatch(listOf(item(root, parent, doc)), setOf("same.epub|42"), authority)

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        dao = db.bookDao()
    }
    @After fun close() = db.close()

    @Test fun sameIdentityAcrossGrantsPreservesOriginalUriAndState() = runBlocking {
        val first = entry(rootA, "opaque:/雪").copy(title = "Edited", isFavorite = true,
            readingStatus = "READING", progressPercent = 37.0, lastLocatorJson = "locator")
        assertEquals(1, dao.insertScannedBatch(listOf(item(rootA, "a", "opaque:/雪", first)), emptySet(), "provider").inserted)
        val existing = requireNotNull(dao.getByUri(first.uriString))
        val shelf = dao.createCustomShelf(CustomShelf(name = "test"))
        dao.addBookToCustomShelf(CustomShelfBook(shelf, existing.id))
        assertEquals(0, scan(rootB, "b", "opaque:/雪").inserted)
        assertNull(dao.getByUri(entry(rootB, "opaque:/雪").uriString))
        assertEquals(existing, dao.getByIdOnce(existing.id))
        assertEquals(1, dao.shelfCountForRemoval(existing.id))
        assertEquals(setOf(rootA, rootB), dao.scanBookRoutes(existing.id).map { it.rootUri }.toSet())
        assertTrue(dao.unresolvedScanRoutes().isEmpty())
    }

    @Test fun differentIdsWithSameNameAndSizeAreDistinct() = runBlocking {
        assertEquals(1, scan(rootA, "a", "one").inserted)
        assertEquals(1, scan(rootA, "a", "two").inserted)
        assertNotEquals(dao.getByUri(entry(rootA, "one").uriString)?.id, dao.getByUri(entry(rootA, "two").uriString)?.id)
        assertEquals(2, dao.countAllBooks())
    }

    @Test fun sameDocumentIdAtDifferentAuthoritiesIsDistinct() = runBlocking {
        val other = "content://other/tree/a"
        assertEquals(1, scan(rootA, "a", "one").inserted)
        assertEquals(1, scan(other, "a", "one", "other").inserted)
        assertEquals(2, dao.countAllBooks())
    }

    @Test fun ambiguousRoutesNeverMergeOrDeleteEvenWhenExactUriExists() = runBlocking {
        val first = dao.insertIgnore(entry(rootA, "same").copy(isFavorite = true))
        val second = dao.insertIgnore(entry(rootB, "same").copy(progressPercent = 50.0))
        dao.insertScanBookRoute(ScanBookRoute(first, rootA, "provider", "same", "[\"a\"]"))
        dao.insertScanBookRoute(ScanBookRoute(second, rootB, "provider", "same", "[\"b\"]"))
        val third = "content://provider/tree/c"
        assertEquals(0, scan(third, "c", "same").inserted)
        assertNull(dao.getByUri(entry(third, "same").uriString))
        assertEquals(0, scan(rootA, "a", "same").inserted)
        assertEquals(2, dao.countAllBooks())
        assertTrue(requireNotNull(dao.getByIdOnce(first)).isFavorite)
        assertEquals(50.0, requireNotNull(dao.getByIdOnce(second)).progressPercent, 0.0)
        assertEquals(1, dao.scanBookRoutes(first).size)
        assertEquals(1, dao.scanBookRoutes(second).size)
        assertEquals(setOf(third, rootA), dao.unresolvedScanRoutes().map { it.rootUri }.toSet())
    }

    @Test fun exactLegacyUriConflictingWithOneKnownIdentityIsUnresolved() = runBlocking {
        assertEquals(1, scan(rootA, "a", "shared").inserted)
        val existing = requireNotNull(dao.getByUri(entry(rootA, "shared").uriString))
        val legacy = entry(rootB, "shared").copy(isFavorite = true)
        val legacyId = dao.insertIgnore(legacy)
        assertEquals(0, scan(rootB, "b", "shared").inserted)
        assertEquals(2, dao.countAllBooks())
        assertEquals(legacy.copy(id = legacyId), dao.getByIdOnce(legacyId))
        assertEquals(1, dao.scanBookRoutes(existing.id).size)
        assertTrue(dao.scanBookRoutes(legacyId).isEmpty())
        assertEquals(rootB, dao.unresolvedScanRoutes().single().rootUri)
    }

    @Test fun sameIdentityTwiceInBatchTracksBothPaths() = runBlocking {
        val doc = "opaque"
        val first = item(rootA, "a", doc)
        val nested = ScannedItem(ScanSighting(rootA, "child", doc, false), entry(rootA, doc), listOf("a", "child"))
        assertEquals(1, dao.insertScannedBatch(listOf(first, nested), emptySet(), "provider").inserted)
        val id = requireNotNull(dao.getByUri(entry(rootA, doc).uriString)).id
        assertEquals(2, dao.scanBookRoutes(id).size)
        assertEquals(1, dao.countAllBooks())
    }

    @Test fun exactLegacyUriMatchesWithoutInventingOriginAndNoMatchInserts() = runBlocking {
        val legacy = entry(rootA, "legacy").copy(title = "Edited", isFavorite = true)
        val id = dao.insertIgnore(legacy)
        assertEquals(0, scan(rootA, "a", "legacy").inserted)
        assertEquals(legacy.copy(id = id), dao.getByIdOnce(id))
        assertNull(dao.getScanBookOrigin(id))
        assertEquals(1, dao.scanBookRoutes(id).size)
        assertEquals(1, scan(rootB, "b", "new").inserted)
        assertEquals(2, dao.countAllBooks())
    }
}
