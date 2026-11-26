package com.example.myebookapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuoteTextTest {
    @Test
    fun collapsesWhitespaceAndNewlines() {
        assertEquals("hello world again", QuoteText.normalize("  hello\n\nworld\t again  "))
    }

    @Test
    fun rejectsBlankText() {
        assertNull(QuoteText.normalize(""))
        assertNull(QuoteText.normalize(" \n\t "))
    }

    @Test
    fun truncatesLongText() {
        val long = "a".repeat(QuoteText.MAX_LENGTH + 50)
        val out = requireNotNull(QuoteText.normalize(long))
        assertEquals(QuoteText.MAX_LENGTH + 1, out.length)
        assertTrue(out.endsWith("…"))
    }

    @Test
    fun keepsShortText() {
        assertEquals("Una frase.", QuoteText.normalize("Una frase."))
    }
}
