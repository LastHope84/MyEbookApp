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
            ?: runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        if (!docId.isNullOrBlank()) {
            return when {
                docId.startsWith("primary:") -> "Internal Storage/" + docId.removePrefix("primary:")
                docId.contains(":") -> "Storage (${docId.substringBefore(":")})/${docId.substringAfter(":")}"
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
            for (target in targetUris) {
                for (mime in listOf(DocumentsContract.Document.MIME_TYPE_DIR, "resource/folder")) {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(target, mime)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (runCatching { context.startActivity(intent) }.isSuccess) return null
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
        return buildAttachmentChooser(context, books, kindleEmail = null)
    }

    fun prepareKindleEmailIntent(context: Context, books: List<BookEntity>, kindleEmail: String): Intent {
        require(kindleEmail.isNotBlank()) { "Set your @kindle.com address in Settings first." }
        return buildAttachmentChooser(context, books, kindleEmail = kindleEmail.trim())
    }

    private fun buildAttachmentChooser(
        context: Context,
        books: List<BookEntity>,
        kindleEmail: String?
    ): Intent {
        require(books.isNotEmpty()) { "No books selected." }

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

            // Books edited in the app are shared with their corrections baked in.
            val exported = if (book.format == "EPUB" && book.metadataEdited) {
                val base = book.title.ifBlank { book.fileName.substringBeforeLast('.') }
                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    .ifBlank { "book" }
                var finalName = "$base.epub"
                var idx = 2
                while (!usedNames.add(finalName.lowercase())) {
                    finalName = "${base}_$idx.epub"
                    idx++
                }
                val dest = File(sharedDir, finalName)
                if (EpubExporter.export(context, book, dest)) {
                    runCatching { FileProvider.getUriForFile(context, authority, dest) }.getOrNull()
                } else null
            } else null

            val shareUri = exported ?: directUri ?: run {
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
        val mimeType = if (kindleEmail != null) "message/rfc822" else if (allEpub) "application/epub+zip" else "*/*"

        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uris.first())
                putExtra(Intent.EXTRA_SUBJECT, books.first().title)
                if (kindleEmail != null) putExtra(Intent.EXTRA_EMAIL, arrayOf(kindleEmail))
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mimeType
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                putExtra(Intent.EXTRA_SUBJECT, "${uris.size} ebooks")
                if (kindleEmail != null) putExtra(Intent.EXTRA_EMAIL, arrayOf(kindleEmail))
            }
        }

        val clipData = ClipData.newUri(context.contentResolver, "Ebook", uris.first())
        for (i in 1 until uris.size) clipData.addItem(ClipData.Item(uris[i]))
        intent.clipData = clipData
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val chooserTitle = when {
            kindleEmail != null -> "Send to Kindle ($kindleEmail)"
            uris.size == 1 -> "Share ${books.first().title}"
            else -> "Share ${uris.size} ebooks"
        }
        return Intent.createChooser(intent, chooserTitle)
    }
}
