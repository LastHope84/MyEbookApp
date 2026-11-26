package com.example.myebookapp

import org.junit.Assert.assertEquals
import org.junit.Test

class BookProgressTest {
    private fun book(status: String, progress: Double) = BookEntity(
        id = 1,
        uriString = "content://book/1",
        fileName = "book.epub",
        format = "EPUB",
        fileSize = 1,
        lastModified = 1,
        title = "Title",
        author = "Author",
        publishingDate = "",
        publisher = "",
        language = "en",
        coverPath = null,
        lastLocatorJson = null,
        progressPercent = progress,
        lastOpenedAt = 0,
        metadataExtracted = true,
        readingStatus = status
    )

    @Test
    fun finishedBooksShowFullProgress() {
        assertEquals(100, displayedProgressPercent(book("FINISHED", 0.42)))
        assertEquals(1f, displayedProgressFraction(book("FINISHED", 0.0)))
    }

    @Test
    fun otherStatusesUseStoredProgress() {
        assertEquals(42, displayedProgressPercent(book("READING", 0.42)))
        assertEquals(0, displayedProgressPercent(book("NEW", 0.0)))
        assertEquals(99, displayedProgressPercent(book("TBR", 0.994)))
    }

    @Test
    fun storedProgressIsClamped() {
        assertEquals(100, displayedProgressPercent(book("READING", 1.4)))
        assertEquals(0, displayedProgressPercent(book("READING", -0.2)))
    }
}
