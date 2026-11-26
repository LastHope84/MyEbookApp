package com.example.myebookapp

import kotlin.math.roundToInt

/** A book on the Finished shelf is shown as fully read; its stored position is unchanged. */
fun displayedProgressPercent(book: BookEntity): Int =
    if (book.readingStatus == "FINISHED") 100
    else (book.progressPercent * 100).roundToInt().coerceIn(0, 100)

fun displayedProgressFraction(book: BookEntity): Float = displayedProgressPercent(book) / 100f
