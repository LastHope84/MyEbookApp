package com.example.myebookapp

import org.junit.Assert.*
import org.junit.Test

class ShelfEndDetectorTest {
    @Test fun checksEntireReadingOrderIncludingBackMatter() {
        val last = ShelfEndDetector.uniqueLastHref(listOf("cover.xhtml", "story.xhtml", "thanks.xhtml"))
        assertEquals("thanks.xhtml", last)
        assertFalse(ShelfEndDetector.isLastResource(last, "story.xhtml"))
        assertTrue(ShelfEndDetector.isLastResource(last, "thanks.xhtml#end"))
    }

    @Test fun ambiguousOrEmptyReadingOrderFailsClosed() {
        assertNull(ShelfEndDetector.uniqueLastHref(emptyList()))
        assertNull(ShelfEndDetector.uniqueLastHref(listOf("a.xhtml", "b.xhtml", "a.xhtml#again")))
        assertNull(ShelfEndDetector.uniqueLastHref(listOf("")))
        assertFalse(ShelfEndDetector.isLastResource(null, ""))
    }

    @Test fun checksPhysicalFinalPageInBothDirections() {
        assertTrue(ShelfEndDetector.isLastPage(3, 4, false))
        assertFalse(ShelfEndDetector.isLastPage(2, 4, false))
        assertTrue(ShelfEndDetector.isLastPage(0, 4, true))
        assertFalse(ShelfEndDetector.isLastPage(1, 4, true))
        assertTrue(ShelfEndDetector.isLastPage(0, 1, false))
        assertFalse(ShelfEndDetector.isLastPage(0, 0, false))
        assertFalse(ShelfEndDetector.isLastPage(4, 4, false))
        assertFalse(ShelfEndDetector.isLastPage(-1, 4, true))
    }
}
