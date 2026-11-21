package com.example.myebookapp

/** Conservative, layout-independent predicates for automatic shelf transitions. */
internal object ShelfEndDetector {
    fun uniqueLastHref(hrefs: List<String>): String? {
        val paths = hrefs.map { it.substringBefore('#') }
        return paths.lastOrNull()?.takeIf { it.isNotBlank() && paths.count { path -> path == it } == 1 }
    }

    fun isLastResource(lastHref: String?, locatorHref: String): Boolean =
        lastHref != null && locatorHref.substringBefore('#') == lastHref

    // Readium 3.1.2 supplies a zero-based physical page index in this callback.
    fun isLastPage(pageIndex: Int, totalPages: Int, rtl: Boolean): Boolean =
        totalPages > 0 && pageIndex in 0 until totalPages &&
            if (rtl) pageIndex == 0 else pageIndex == totalPages - 1
}
