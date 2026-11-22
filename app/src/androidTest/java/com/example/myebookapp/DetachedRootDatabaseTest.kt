package com.example.myebookapp

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Room transaction and opaque-ID policy tests; not a real provider or service race test. */
@RunWith(AndroidJUnit4::class)
class DetachedRootDatabaseTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: BookDao
    private val parent = "content://provider/tree/parent"
    private val detached = "content://provider/tree/removed"
    private val removed = RemovedScanRoot(detached, "provider", "removed")

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        dao = db.bookDao()
    }
    @After fun close() = db.close()

    private fun item(root: String, path: List<String>, id: String): ScannedItem {
        val book = BookEntity(uriString = "$root/document/$id", fileName = "$id.epub", format = "EPUB",
            fileSize = 100, lastModified = 0, title = id, author = "Unknown", publishingDate = "Unknown",
            publisher = "Unknown", language = "Unknown", coverPath = null, lastLocatorJson = null,
            progressPercent = 0.0, lastOpenedAt = 0, metadataExtracted = false)
        return ScannedItem(ScanSighting(root, path.last(), id, false), book, path)
    }

    @Test fun detachmentKeepsRowsAndBlocksLaterParentAndDirectBatchesUntilRestored() = runBlocking {
        val before = item(detached, listOf("removed"), "one")
        assertEquals(1, dao.insertScannedBatch(listOf(before), emptySet(), "provider").inserted)
        val row = requireNotNull(dao.getByUri(before.book!!.uriString))
        dao.setFavorite(row.id, true)
        val shelf = dao.createCustomShelf(CustomShelf(name = "Keep"))
        dao.addBookToCustomShelf(CustomShelfBook(shelf, row.id))
        dao.detachScanRoot(removed)
        assertTrue(requireNotNull(dao.getByIdOnce(row.id)).isFavorite)
        assertEquals(1, dao.shelfCountForRemoval(row.id))
        assertEquals(0, dao.insertScannedBatch(listOf(item(detached, listOf("removed"), "two")), emptySet(), "provider").inserted)
        assertEquals(1, dao.insertScannedBatch(listOf(item(parent, listOf("parent", "removed"), "three")), emptySet(), "provider").inserted)
        assertEquals(1, dao.insertScannedBatch(listOf(item(parent, listOf("parent", "other"), "four")), emptySet(), "provider").inserted)
        assertEquals(1, dao.clearRemovedScanRoot(detached))
        assertEquals(1, dao.insertScannedBatch(listOf(item(parent, listOf("parent", "removed"), "five")), emptySet(), "provider").inserted)
    }

    @Test fun identityIsAuthorityScopedAndIndependentEquivalentGrantIsRetained() {
        assertTrue(removedRootBlocks(parent, "provider", listOf(removed)).isEmpty())
        assertEquals(setOf("removed"), removedRootBlocks(detached, "provider", listOf(removed)))
        assertTrue(removedRootBlocks("content://elsewhere/tree/parent", "elsewhere", listOf(removed)).isEmpty())
        assertTrue(removedRootBlocks("content://provider/tree/removed/extra", "provider", listOf(removed)).isEmpty())
    }
}
