package com.example.myebookapp

import org.junit.Assert.*
import org.junit.Test

class TranslationLanguageSearchTest {
    @Test fun namesAndUnknownCodes() {
        assertEquals("English (en)", translationLanguageLabel("en"))
        assertEquals("Italian (it)", translationLanguageLabel("it"))
        assertEquals("zzz", translationLanguageLabel("zzz"))
    }

    @Test fun searchMatchesNamesCodesAndMultipleTermsIgnoringCase() {
        assertTrue(translationPairMatches("en", "it", ""))
        assertTrue(translationPairMatches("en", "it", " ITALIAN "))
        assertTrue(translationPairMatches("en", "it", "en it"))
        assertTrue(translationPairMatches("it", "en", "English Italian"))
        assertFalse(translationPairMatches("en", "it", "french"))
    }

    @Test fun searchCanFindIntermediateLanguagesInRoutes() {
        assertTrue(translationLanguagesMatch(listOf("fr", "it", "en"), "French English Italian"))
        assertFalse(translationLanguagesMatch(listOf("fr", "it", "en"), "German"))
    }
}
