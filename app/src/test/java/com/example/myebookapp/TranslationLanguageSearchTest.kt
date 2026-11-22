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

    @Test fun onlyRoutesWithAllPacksInstalledAreShown() {
        val missing = TranslationPack("fr", "en", "1", 1, emptyList())
        val present = missing.copy(installedVersions = listOf("1 (NotChecked)"))
        val second = TranslationPack("en", "it", "1", 1, listOf("1 (NotChecked)"))
        assertFalse(translationRouteHasInstalledPacks(TranslationRoute("fr", "it", emptyList())))
        assertFalse(translationRouteHasInstalledPacks(TranslationRoute("fr", "it", listOf(missing, second))))
        assertTrue(translationRouteHasInstalledPacks(TranslationRoute("fr", "it", listOf(present, second))))
    }

    @Test fun listExplainsUncheckedMetadataWithoutChangingOtherStatuses() {
        assertEquals("1 (not checked in this list)", translationInstallationLabel("1 (NotChecked)"))
        assertEquals("1 (Corrupt)", translationInstallationLabel("1 (Corrupt)"))
    }
}
