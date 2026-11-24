package com.example.myebookapp

import android.content.Context
import android.net.Uri
import java.io.File

/** Files the app owns can be deleted; files in the user's folders are never touched. */
object AppFiles {
    fun isAppOwned(context: Context, uriString: String): Boolean {
        val uri = Uri.parse(uriString)
        if (uri.scheme != "file") return false
        val path = uri.path ?: return false
        return ownedRoots(context).any { root ->
            path.startsWith(root.absolutePath + File.separator)
        }
    }

    fun delete(context: Context, book: BookEntity): Boolean {
        if (!isAppOwned(context, book.uriString)) return false
        val path = Uri.parse(book.uriString).path ?: return false
        val file = File(path)
        if (file.exists() && !file.delete()) return false
        book.coverPath?.let { runCatching { File(it).delete() } }
        book.exportCoverPath?.let { runCatching { File(it).delete() } }
        return true
    }

    private fun ownedRoots(context: Context) = listOf(
        File(context.filesDir, "imports"),
        File(context.filesDir, "converted_epubs")
    )
}
