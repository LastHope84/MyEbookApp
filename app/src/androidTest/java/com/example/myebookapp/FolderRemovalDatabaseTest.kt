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

/** Real SQLite/Room coverage; no provider files are touched. */
@RunWith(AndroidJUnit4::class)
class FolderRemovalDatabaseTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: BookDao
    private val root = "content://provider/tree/root"
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        dao = db.bookDao()
    }
    @After fun close() = db.close()
    private fun book(n: String) = BookEntity(uriString = "content://provider/tree/root/document/$n", fileName = "$n.epub", format = "EPUB", fileSize = 100,
        lastModified = 0, title = n, author = "Unknown", publishingDate = "Unknown", publisher = "Unknown", language = "Unknown", coverPath = null,
        lastLocatorJson = null, progressPercent = 0.0, lastOpenedAt = 0, metadataExtracted = false)
    private suspend fun scanned(n: String): RemovalCandidate {
        val item = ScannedItem(ScanSighting(root, "folder", n, false), book(n), listOf("root", "folder"))
        assertEquals(1, dao.insertScannedBatch(listOf(item), emptySet(), "provider").inserted)
        val row = requireNotNull(dao.getByUri(book(n).uriString))
        return RemovalCandidate(row, requireNotNull(dao.getScanBookOrigin(row.id)))
    }
    @Test fun deletesOnlyUnchangedPristineOriginAndBlocksRescan() = runBlocking {
        val candidate = scanned("one")
        assertEquals(1, dao.commitProvenFolderRemoval(ScanRootExclusion(root, "folder"), listOf(candidate)))
        assertNull(dao.getByIdOnce(candidate.book.id))
        assertNull(dao.getScanBookOrigin(candidate.book.id))
        val retry = ScannedItem(ScanSighting(root, "folder", "one", false), book("one"), listOf("root", "folder"))
        assertEquals(0, dao.insertScannedBatch(listOf(retry), emptySet(), "provider").inserted)
    }
    @Test fun concurrentChangeAndShelfMembershipAreRetained() = runBlocking {
        val changed = scanned("changed")
        val shelved = scanned("shelved")
        dao.setFavorite(changed.book.id, true)
        val shelfId = dao.createCustomShelf(CustomShelf(name = "Keep"))
        dao.addBookToCustomShelf(CustomShelfBook(shelfId, shelved.book.id))
        assertEquals(0, dao.commitProvenFolderRemoval(ScanRootExclusion(root, "folder"), listOf(changed, shelved)))
        assertTrue(requireNotNull(dao.getByIdOnce(changed.book.id)).isFavorite)
        assertNotNull(dao.getByIdOnce(shelved.book.id))
    }
    @Test fun legacyOrModifiedRecordsCannotBeDeletedByInventedCandidate() = runBlocking {
        val id = dao.insertIgnore(book("legacy"))
        val legacy = requireNotNull(dao.getByIdOnce(id))
        val forged = RemovalCandidate(legacy, ScanBookOrigin(id, root, "folder", "legacy"))
        assertEquals(0, dao.commitProvenFolderRemoval(ScanRootExclusion(root, "folder"), listOf(forged)))
        assertNotNull(dao.getByIdOnce(id))
    }
}
