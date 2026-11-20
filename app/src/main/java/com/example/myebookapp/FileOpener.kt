package com.example.myebookapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import java.io.File

object FileOpener {

    fun formatReadablePath(uriString: String): String {
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file") {
            val path = uri.path ?: return uriString
            val idx = path.indexOf("/converted_epubs/")
            return if (idx >= 0) "App Storage" + path.substring(idx) else path
        }
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        if (!docId.isNullOrBlank()) {
            return when {
                docId.startsWith("primary:") -> "Internal Storage/" + docId.removePrefix("primary:")
                docId.contains(":") -> {
                    val vol = docId.substringBefore(":")
                    val rest = docId.substringAfter(":")
                    "Storage ($vol)/$rest"
                }
                else -> docId
            }
        }
        return Uri.decode(uriString)
    }

    fun openContainingFolder(context: Context, uriString: String): String? {
        val uri = Uri.parse(uriString)
        val readable = formatReadablePath(uriString)

        if (uri.scheme == "content") {
            val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            val parentDocId = docId?.let { id ->
                when {
                    id.contains("/") -> id.substringBeforeLast("/")
                    id.contains(":") -> id.substringBefore(":") + ":"
                    else -> null
                }
            }

            val authority = uri.authority ?: "com.android.externalstorage.documents"
            val targetUris = buildList {
                if (!parentDocId.isNullOrBlank()) {
                    runCatching { add(DocumentsContract.buildDocumentUriUsingTree(uri, parentDocId)) }
                    runCatching { add(DocumentsContract.buildDocumentUri(authority, parentDocId)) }
                }
                add(uri)
            }

            val mimeTypes = listOf(
                DocumentsContract.Document.MIME_TYPE_DIR,
                "resource/folder"
            )

            for (target in targetUris) {
                for (mime in mimeTypes) {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(target, mime)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (runCatching { context.startActivity(intent) }.isSuccess) {
                        return null
                    }
                }
            }
        }

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Book Path", readable))
        return if (uri.scheme == "file") {
            "Converted EPUB is in private App Storage — path copied to clipboard."
        } else {
            "No file manager accepted this folder link — path copied to clipboard."
        }
    }

    fun prepareShareIntent(context: Context, books: List<BookEntity>): Intent {
        require(books.isNotEmpty()) { "No books selected to share." }

        val authority = "${context.packageName}.fileprovider"
        val sharedDir = File(context.cacheDir, "shared_books").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }

        val uris = ArrayList<Uri>(books.size)
        val usedNames = mutableSetOf<String>()

        for (book in books) {
            val srcUri = Uri.parse(book.uriString)
            val directUri = if (srcUri.scheme == "file" && srcUri.path != null) {
                val f = File(srcUri.path!!)
                if (f.exists()) runCatching { FileProvider.getUriForFile(context, authority, f) }.getOrNull()
                else null
            } else null

            val shareUri = directUri ?: run {
                val cleanName = book.fileName
                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    .ifBlank { "book.${book.format.lowercase()}" }

                var finalName = cleanName
                var idx = 2
                while (!usedNames.add(finalName.lowercase())) {
                    val base = cleanName.substringBeforeLast('.')
                    val ext = cleanName.substringAfterLast('.', "")
                    finalName = if (ext.isNotEmpty()) "${base}_$idx.$ext" else "${base}_$idx"
                    idx++
                }

                val dest = File(sharedDir, finalName)
                val input = if (srcUri.scheme == "file" && srcUri.path != null) {
                    File(srcUri.path!!).inputStream()
                } else {
                    context.contentResolver.openInputStream(srcUri)
                } ?: error("Could not read ${book.fileName}")

                input.use { ins ->
                    dest.outputStream().buffered().use { out -> ins.copyTo(out) }
                }
                FileProvider.getUriForFile(context, authority, dest)
            }

            uris.add(shareUri)
        }

        val allEpub = books.all { it.format == "EPUB" }
        val mimeType = if (allEpub) "application/epub+zip" else "*/*"

        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uris.first())
                putExtra(Intent.EXTRA_SUBJECT, books.first().title)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mimeType
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }

        val clipData = ClipData.newUri(context.contentResolver, "Ebook", uris.first())
        for (i in 1 until uris.size) {
            clipData.addItem(ClipData.Item(uris[i]))
        }
        intent.clipData = clipData
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val chooserTitle = if (uris.size == 1) {
            "Share ${books.first().title}"
        } else {
            "Share ${uris.size} ebooks"
        }
        return Intent.createChooser(intent, chooserTitle)
    }
}
