package com.example.myebookapp

import java.text.Normalizer

/**
 * Item positions for the alphabet rail, built from one row per initial letter.
 *
 * Positions are paged-list indexes, so they add up book counts rather than
 * counting groups. SQLite sorts with COLLATE NOCASE, which folds ASCII only:
 * "É" sorts after "Z". A letter therefore targets its ASCII group first and
 * only falls back to accented groups when no ASCII group exists.
 */
data class AlphabetIndex(
    val total: Int,
    private val startsAsc: Map<Char, Int>,
    private val startsDesc: Map<Char, Int>,
    val present: Set<Char>,
    val counts: Map<Char, Int>
) {
    fun target(letter: Char, reversed: Boolean): Int {
        if (total <= 0) return 0
        val index = (if (reversed) startsDesc[letter] else startsAsc[letter]) ?: 0
        return index.coerceIn(0, total - 1)
    }

    companion object {
        val LETTERS: List<Char> = listOf('#') + ('A'..'Z')

        fun build(groups: List<InitialCount>): AlphabetIndex {
            val sorted = groups.filter { it.bookCount > 0 }.sortedBy { sortKey(it.initial) }
            val entries = ArrayList<RailEntry>(sorted.size)
            var total = 0
            for (group in sorted) {
                entries += RailEntry(railLetter(group.initial), sortKey(group.initial), total, group.bookCount)
                total += group.bookCount
            }

            val asc = HashMap<Char, Int>(LETTERS.size)
            val desc = HashMap<Char, Int>(LETTERS.size)
            val counts = HashMap<Char, Int>()
            val present = HashSet<Char>()

            for (letter in LETTERS) {
                val mine = entries.filter { it.letter == letter }
                if (mine.isEmpty()) {
                    if (letter == '#') {
                        asc['#'] = 0
                        desc['#'] = 0
                    } else {
                        val key = letter.lowercaseChar().code
                        asc[letter] = entries.filter { it.key < key }.sumOf { it.count }
                        desc[letter] = total - entries.filter { it.key <= key }.sumOf { it.count }
                    }
                    continue
                }
                counts[letter] = mine.sumOf { it.count }
                present += letter
                val ascii = mine.filter { it.key in 'a'.code..'z'.code }
                val first = ascii.firstOrNull() ?: mine.first()
                val last = ascii.lastOrNull() ?: mine.last()
                asc[letter] = first.start
                desc[letter] = total - (last.start + last.count)
            }
            return AlphabetIndex(total, asc, desc, present, counts)
        }

        /** Maps a first character to its rail letter; anything outside A-Z becomes '#'. */
        fun railLetter(initial: String): Char {
            if (initial.isEmpty()) return '#'
            val cp = initial.codePointAt(0)
            if (cp in 'A'.code..'Z'.code) return cp.toChar()
            if (cp in 'a'.code..'z'.code) return (cp - 32).toChar()
            val decomposed = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFD)
            if (decomposed.isEmpty()) return '#'
            return when (val c = decomposed[0].code) {
                in 'A'.code..'Z'.code -> c.toChar()
                in 'a'.code..'z'.code -> (c - 32).toChar()
                else -> '#'
            }
        }

        // Mirrors SQLite's NOCASE ordering for the first character.
        private fun sortKey(initial: String): Int {
            if (initial.isEmpty()) return -1
            val cp = initial.codePointAt(0)
            return if (cp in 'A'.code..'Z'.code) cp + 32 else cp
        }
    }
}

private data class RailEntry(val letter: Char, val key: Int, val start: Int, val count: Int)
