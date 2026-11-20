package com.example.myebookapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

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
}
