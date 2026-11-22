package com.example.myebookapp

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Room transactions only; does not simulate a real provider walk. */
@RunWith(AndroidJUnit4::class)
class MembershipVisibilityDatabaseTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: BookDao
    private val root = "content://provider/tree/root"
    private val other = "content://provider/tree/other"
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build(); dao = db.bookDao() }
    @After fun close() = db.close()
    private fun item(r: String, id: String): ScannedItem {
        val doc = android.net.Uri.parse(r)
        val book = BookEntity(uriString = android.provider.DocumentsContract.buildDocumentUriUsingTree(doc, id).toString(), fileName = "$id.epub", format = "EPUB", fileSize = 1, lastModified = 0, title = id, author = "", publishingDate = "", publisher = "", language = "", coverPath = null, lastLocatorJson = null, progressPercent = 0.0, lastOpenedAt = 1, metadataExtracted = true)
        return ScannedItem(ScanSighting(r, android.provider.DocumentsContract.getTreeDocumentId(doc), id, false), book, listOf(android.provider.DocumentsContract.getTreeDocumentId(doc)))
    }
    @Test fun unprovenOwnershipNeverHidesSharedOrExclusiveOrLegacy() = runBlocking {
        val a = item(root, "exclusive"); val shared = item(root, "shared")
        val legacy = item(root, "legacy")
        val legacyId = dao.insertIgnore(legacy.book!!)
        dao.insertScannedBatch(listOf(a, shared, legacy), emptySet(), "provider")
        dao.insertScannedBatch(listOf(item(other, "shared")), emptySet(), "provider")
        val exclusiveId = requireNotNull(dao.getByUri(a.book!!.uriString)).id
        dao.setFavorite(exclusiveId, true)
        val shelf = dao.createCustomShelf(CustomShelf(name = "Keep"))
        dao.addBookToCustomShelf(CustomShelfBook(shelf, exclusiveId))
        dao.initializeAndCheckConfig(listOf(root, other))
        val gen = dao.beginInventorySweep(listOf(root, other))
        dao.insertScannedBatch(listOf(a, shared, legacy), emptySet(), "provider", gen)
        dao.completeInventoryRoot(root, gen)
        dao.insertScannedBatch(listOf(item(other, "shared")), emptySet(), "provider", gen)
        dao.completeInventoryRoot(other, gen)
        try { dao.detachWithInventory(RemovedScanRoot(root, "provider", "root"), gen, listOf(root, other)); fail("unproven hide accepted") } catch (_: IllegalStateException) { }
        assertEquals(3, dao.observeTotalCount(true).first())
        assertEquals(3, dao.countAllBooks())
        assertEquals(1, dao.shelfCountForRemoval(exclusiveId))
        assertTrue(requireNotNull(dao.getByIdOnce(exclusiveId)).isFavorite)
        assertNotNull(dao.getByIdOnce(legacyId))
        assertTrue(dao.removedScanRoots().isEmpty())
    }
    @Test fun staleOrIncompleteGenerationNeverCommits() = runBlocking {
        dao.initializeAndCheckConfig(listOf(root))
        val gen = dao.beginInventorySweep(listOf(root))
        try { dao.detachWithInventory(RemovedScanRoot(root,"provider","root"), gen, listOf(root)); fail("incomplete accepted") } catch (_: IllegalStateException) { }
        dao.completeInventoryRoot(root, gen)
        dao.invalidateScanInventory()
        try { dao.detachWithInventory(RemovedScanRoot(root,"provider","root"), gen, listOf(root)); fail("stale accepted") } catch (_: IllegalStateException) { }
        assertTrue(dao.removedScanRoots().isEmpty())
    }
}
