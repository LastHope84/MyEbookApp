package com.example.myebookapp

/** Cleans a reader selection before it is stored as a quote. */
object QuoteText {
    const val MAX_LENGTH = 4000
    private val whitespace = Regex("\\s+")

    fun normalize(raw: String): String? {
        val clean = raw.replace(whitespace, " ").trim()
        if (clean.isEmpty()) return null
        return if (clean.length <= MAX_LENGTH) clean else clean.take(MAX_LENGTH).trimEnd() + "…"
    }
}
