package com.example.myebookapp

import org.junit.Assert.*
import org.junit.Test

class AlphabetIndexTest {
    private fun index(vararg groups: Pair<String, Int>): AlphabetIndex =
        AlphabetIndex.build(groups.map { InitialCount(it.first, it.second) })

    @Test fun emptyLibraryHasNoRail() {
        val i = index()
        assertEquals(0, i.total)
        assertTrue(i.present.isEmpty())
        assertEquals(0, i.target('A', false))
        assertEquals(0, i.target('#', true))
    }

    @Test fun missingLettersJumpToTheirInsertionPoint() {
        val i = index("A" to 2, "C" to 3)
        assertEquals(5, i.total)
        assertEquals(0, i.target('A', false))
        assertEquals(2, i.target('C', false))
        assertEquals(2, i.target('B', false))
        assertEquals(3, i.target('B', true))
        assertEquals(0, i.target('C', true))
        assertEquals(3, i.target('A', true))
    }

    @Test fun positionsAddBookCountsNotGroupCounts() {
        val i = index("A" to 10, "C" to 5)
        assertEquals(10, i.target('C', false))
        assertEquals(10, i.target('B', false))
        assertEquals(14, i.target('Z', false))
    }

    @Test fun caseVariantsShareOneGroup() {
        val i = index("a" to 2, "C" to 3)
        assertEquals(setOf('A', 'C'), i.present)
        assertEquals(2, i.target('C', false))
        assertEquals(2, i.counts['A'])
    }

    @Test fun accentedInitialFoldsWhenNoAsciiGroupExists() {
        val i = index("A" to 2, "É" to 3)
        assertEquals(setOf('A', 'E'), i.present)
        assertEquals(2, i.target('E', false))
        assertEquals(0, i.target('E', true))
        assertEquals(3, i.counts['E'])
    }

    @Test fun asciiGroupWinsOverAccentedGroup() {
        val i = index("A" to 2, "E" to 3, "É" to 4)
        assertEquals(9, i.total)
        assertEquals(2, i.target('E', false))
        assertEquals(4, i.target('E', true))
        assertEquals(7, i.counts['E'])
    }

    @Test fun numbersAndSymbolsTargetTheFirstSpecialGroup() {
        val before = index("-" to 2, "A" to 3)
        assertEquals(setOf('#', 'A'), before.present)
        assertEquals(0, before.target('#', false))
        assertEquals(3, before.target('#', true))

        val after = index("A" to 2, "日" to 3)
        assertEquals(2, after.target('#', false))
        assertEquals(0, after.target('#', true))
    }

    @Test fun blankInitialSortsFirstAsSpecial() {
        val i = index("" to 4, "A" to 2)
        assertEquals(6, i.total)
        assertEquals(0, i.target('#', false))
        assertEquals(2, i.target('#', true))
        assertEquals(4, i.target('A', false))
    }

    @Test fun orderingMatchesSqliteNoCase() {
        val i = index("a" to 1, "Z" to 1, "É" to 1)
        assertEquals(3, i.total)
        assertEquals(0, i.target('A', false))
        assertEquals(1, i.target('Z', false))
        assertEquals(2, i.target('E', false))
    }

    @Test fun railLettersCoverAccentsAndNonLatin() {
        assertEquals('A', AlphabetIndex.railLetter("a"))
        assertEquals('Z', AlphabetIndex.railLetter("Z"))
        assertEquals('E', AlphabetIndex.railLetter("É"))
        assertEquals('E', AlphabetIndex.railLetter("é"))
        assertEquals('I', AlphabetIndex.railLetter("İ"))
        assertEquals('#', AlphabetIndex.railLetter("ß"))
        assertEquals('#', AlphabetIndex.railLetter("Æ"))
        assertEquals('#', AlphabetIndex.railLetter("Ø"))
        assertEquals('#', AlphabetIndex.railLetter("日"))
        assertEquals('#', AlphabetIndex.railLetter("😀"))
        assertEquals('#', AlphabetIndex.railLetter(""))
    }

    @Test fun targetsStayInsideTheList() {
        val i = index("A" to 2, "C" to 3)
        for (letter in AlphabetIndex.LETTERS) {
            assertTrue(i.target(letter, false) in 0..4)
            assertTrue(i.target(letter, true) in 0..4)
        }
    }
}
