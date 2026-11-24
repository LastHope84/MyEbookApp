package com.example.myebookapp

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.security.MessageDigest

/** Read the current provider version so a replaced file never reuses an old copy. */
internal fun queryDocumentVersion(
    context: Context,
    uri: Uri,
    fallbackSize: Long,
    fallbackModified: Long
): Pair<Long, Long> {
    if (uri.scheme == "file") {
        val file = uri.path?.let { File(it) }
        if (file != null && file.isFile) return file.length() to file.lastModified()
    }
    return runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null, null, null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val size = cursor.getLong(0).takeIf { it > 0 } ?: fallbackSize
            val modified = cursor.getLong(1).takeIf { it > 0 } ?: fallbackModified
            size to modified
        }
    }.getOrNull() ?: (fallbackSize to fallbackModified)
}

/**
 * SAF documents have no usable file path, but some parsers need random access.
 * Keep one cached copy per file version, reuse it on reopen, and prune old ones.
 */
internal fun cachedDocumentCopy(
    context: Context,
    uriString: String,
    fileSize: Long,
    lastModified: Long,
    dirName: String,
    extension: String
): File {
    val uri = Uri.parse(uriString)
    if (uri.scheme == "file") {
        val direct = uri.path?.let { File(it) }
        if (direct != null && direct.isFile) return direct
    }
    val dir = File(context.cacheDir, dirName).apply { mkdirs() }
    val key = sha256("$uriString|$fileSize|$lastModified").take(24)
    val target = File(dir, "$key.$extension")
    if (target.isFile && (fileSize <= 0 || target.length() == fileSize)) {
        target.setLastModified(System.currentTimeMillis())
        return target
    }
    val temp = File(dir, "$key.tmp")
    val input = context.contentResolver.openInputStream(uri)
        ?: throw IllegalStateException("Permission expired or the file moved — add the folder again.")
    try {
        input.use { source -> temp.outputStream().buffered().use { out -> source.copyTo(out) } }
        if (fileSize > 0 && temp.length() != fileSize) {
            throw IllegalStateException("The file changed while it was being opened.")
        }
        if (target.exists() && !target.delete()) {
            throw IllegalStateException("Could not replace the cached copy.")
        }
        if (!temp.renameTo(target)) throw IllegalStateException("Could not prepare the file.")
        prune(dir, target, extension)
        return target
    } catch (e: Exception) {
        temp.delete()
        throw e
    }
}

private fun prune(dir: File, keep: File, extension: String) {
    val now = System.currentTimeMillis()
    val cached = dir.listFiles()?.filter {
        it.isFile && it.absolutePath != keep.absolutePath && it.name.endsWith(".$extension")
    } ?: return
    cached.sortedByDescending { it.lastModified() }.forEachIndexed { index, file ->
        if (index >= 2 || now - file.lastModified() > 30L * 24 * 60 * 60 * 1000) file.delete()
    }
    dir.listFiles()?.filter {
        it.isFile && it.name.endsWith(".tmp") && now - it.lastModified() > 24L * 60 * 60 * 1000
    }?.forEach { it.delete() }
}

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
