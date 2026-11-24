package com.example.myebookapp

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupInfo(
    val createdAt: Long,
    val appVersion: String,
    val includeBooks: Boolean,
    val bookCount: Int,
    val shelfCount: Int
)

data class BackupSummary(
    val books: Int,
    val shelves: Int,
    val covers: Int,
    val bookFiles: Int
)

data class RestoreSummary(
    val books: Int,
    val shelves: Int,
    val bookFiles: Int
)

/**
 * A backup is a plain .zip:
 *  - manifest.json: format, schema version, date, app version, whether books are included
 *  - library.json: books, shelves, shelf membership and conversion links
 *  - prefs.json: app settings
 *  - covers/ and covers_export/: app-generated cover images
 *  - books/imports/ and books/converted_epubs/: optional app-owned book files
 *
 * Restore replaces the library database in one transaction. Source ebook files
 * in the user's folders are never read, written or deleted.
 */
object BackupRestore {
    private const val FORMAT = "myebookapp-backup"
    private const val SCHEMA = 1
    private const val LAST_AUTO_SCAN_KEY = "last_auto_scan"

    private val PREF_NAMES = listOf(
        "my_ebook_theme_prefs",
        "my_ebook_reader_prefs_v2",
        "tts_prefs",
        "library_visibility",
        "my_ebook_folders",
        "my_ebook_history_prefs"
    )

    suspend fun export(
        context: Context,
        db: AppDatabase,
        uri: Uri,
        includeBooks: Boolean
    ): BackupSummary = withContext(Dispatchers.IO) {
        val dao = db.bookDao()
        val books = dao.getAllBooksOnce()
        val shelves = dao.getAllCustomShelvesOnce()
        val shelfBooks = dao.getAllShelfBooksOnce()
        val links = dao.getAllConversionLinksOnce()

        val library = JSONObject().apply {
            put("books", JSONArray().apply { books.forEach { put(it.toJson()) } })
            put("shelves", JSONArray().apply { shelves.forEach { put(it.toJson()) } })
            put("shelfBooks", JSONArray().apply { shelfBooks.forEach { put(it.toJson()) } })
            put("conversionLinks", JSONArray().apply { links.forEach { put(it.toJson()) } })
        }
        val manifest = JSONObject().apply {
            put("format", FORMAT)
            put("schemaVersion", SCHEMA)
            put("createdAt", System.currentTimeMillis())
            put("appVersion", appVersion(context))
            put("includeBooks", includeBooks)
            put("bookCount", books.size)
            put("shelfCount", shelves.size)
        }

        var covers = 0
        var bookFiles = 0
        val output = context.contentResolver.openOutputStream(uri, "wt")
            ?: error("Could not open the backup file for writing.")
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putText("manifest.json", manifest.toString())
            zip.putText("library.json", library.toString())
            zip.putText("prefs.json", readPrefs(context).toString())
            covers += zip.putDir(File(context.filesDir, "covers"), "covers")
            covers += zip.putDir(File(context.filesDir, "covers_export"), "covers_export")
            if (includeBooks) {
                bookFiles += zip.putDir(File(context.filesDir, "imports"), "books/imports")
                bookFiles += zip.putDir(File(context.filesDir, "converted_epubs"), "books/converted_epubs")
            }
        }
        BackupSummary(books.size, shelves.size, covers, bookFiles)
    }

    suspend fun inspect(context: Context, uri: Uri): BackupInfo = withContext(Dispatchers.IO) {
        val manifest = JSONObject(String(readEntry(context, uri, "manifest.json")
            ?: error("This file is not a MyEbookApp backup."), Charsets.UTF_8))
        if (manifest.optString("format") != FORMAT) error("This file is not a MyEbookApp backup.")
        if (manifest.optInt("schemaVersion", 0) > SCHEMA) {
            error("This backup was made by a newer version of the app.")
        }
        val library = JSONObject(String(readEntry(context, uri, "library.json")
            ?: error("The backup is missing its library data."), Charsets.UTF_8))
        BackupInfo(
            createdAt = manifest.optLong("createdAt", 0L),
            appVersion = manifest.optString("appVersion", ""),
            includeBooks = manifest.optBoolean("includeBooks", false),
            bookCount = library.optJSONArray("books")?.length() ?: 0,
            shelfCount = library.optJSONArray("shelves")?.length() ?: 0
        )
    }

    suspend fun restore(context: Context, db: AppDatabase, uri: Uri): RestoreSummary =
        withContext(Dispatchers.IO) {
            val manifest = JSONObject(String(readEntry(context, uri, "manifest.json")
                ?: error("This file is not a MyEbookApp backup."), Charsets.UTF_8))
            if (manifest.optString("format") != FORMAT) error("This file is not a MyEbookApp backup.")
            if (manifest.optInt("schemaVersion", 0) > SCHEMA) {
                error("This backup was made by a newer version of the app.")
            }
            val library = JSONObject(String(readEntry(context, uri, "library.json")
                ?: error("The backup is missing its library data."), Charsets.UTF_8))

            val books = library.optJSONArray("books").toObjects().mapNotNull { it.toBook() }
                .distinctBy { it.id }
            val bookIds = books.mapTo(HashSet()) { it.id }
            val shelves = library.optJSONArray("shelves").toObjects().mapNotNull { it.toShelf() }
                .distinctBy { it.id }
                .distinctBy { it.name.lowercase() }
            val shelfIds = shelves.mapTo(HashSet()) { it.id }
            val shelfBooks = library.optJSONArray("shelfBooks").toObjects().mapNotNull { it.toShelfBook() }
                .filter { it.shelfId in shelfIds && it.bookId in bookIds }
                .distinctBy { it.shelfId to it.bookId }
            val links = library.optJSONArray("conversionLinks").toObjects().mapNotNull { it.toConversionLink() }
                .filter { it.sourceBookId in bookIds && it.outputBookId in bookIds && it.sourceBookId != it.outputBookId }
                .distinctBy { it.sourceBookId }

            // Files first: if extraction fails, the old library stays intact.
            val bookFiles = extractFiles(context, uri, manifest.optBoolean("includeBooks", false))

            val dao = db.bookDao()
            db.withTransaction {
                dao.deleteAllConversionLinks()
                dao.deleteAllShelfBooks()
                dao.deleteAllCustomShelves()
                dao.deleteAllBooks()
                if (shelves.isNotEmpty()) dao.restoreCustomShelves(shelves)
                if (books.isNotEmpty()) dao.restoreBooks(books)
                if (links.isNotEmpty()) dao.restoreConversionLinks(links)
                if (shelfBooks.isNotEmpty()) dao.restoreShelfBooks(shelfBooks)
            }

            runCatching {
                readEntry(context, uri, "prefs.json")?.let { applyPrefs(context, JSONObject(String(it, Charsets.UTF_8))) }
            }

            RestoreSummary(books.size, shelves.size, bookFiles)
        }

    // ---- books and shelves ----

    private fun BookEntity.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("uriString", uriString)
        put("fileName", fileName)
        put("format", format)
        put("fileSize", fileSize)
        put("lastModified", lastModified)
        put("title", title)
        put("author", author)
        put("publishingDate", publishingDate)
        put("publisher", publisher)
        put("language", language)
        put("coverPath", coverPath ?: JSONObject.NULL)
        put("lastLocatorJson", lastLocatorJson ?: JSONObject.NULL)
        put("progressPercent", progressPercent)
        put("lastOpenedAt", lastOpenedAt)
        put("metadataExtracted", metadataExtracted)
        put("series", series ?: JSONObject.NULL)
        put("seriesIndex", seriesIndex ?: JSONObject.NULL)
        put("tags", tags ?: JSONObject.NULL)
        put("metadataEdited", metadataEdited)
        put("exportCoverPath", exportCoverPath ?: JSONObject.NULL)
        put("isFavorite", isFavorite)
        put("readingStatus", readingStatus)
        put("scanRootUri", scanRootUri ?: JSONObject.NULL)
        put("scanParentId", scanParentId ?: JSONObject.NULL)
    }

    private fun JSONObject.toBook(): BookEntity? {
        val id = optLong("id", -1L)
        val uri = optString("uriString", "")
        val fileName = optString("fileName", "")
        val format = optString("format", "").uppercase()
        if (id <= 0 || uri.isBlank() || fileName.isBlank() || format.isBlank()) return null
        val status = optString("readingStatus", "NEW")
            .takeIf { it in setOf("NEW", "TBR", "READING", "FINISHED") } ?: "NEW"
        val seriesIndex = optDouble("seriesIndex", Double.NaN)
        return BookEntity(
            id = id,
            uriString = uri,
            fileName = fileName,
            format = format,
            fileSize = optLong("fileSize", 0L),
            lastModified = optLong("lastModified", 0L),
            title = optString("title", fileName.substringBeforeLast('.')),
            author = optString("author", "Unknown"),
            publishingDate = optString("publishingDate", ""),
            publisher = optString("publisher", ""),
            language = optString("language", ""),
            coverPath = optStringOrNull("coverPath"),
            lastLocatorJson = optStringOrNull("lastLocatorJson"),
            progressPercent = optDouble("progressPercent", 0.0).coerceIn(0.0, 100.0),
            lastOpenedAt = optLong("lastOpenedAt", 0L),
            metadataExtracted = optBoolean("metadataExtracted", true),
            series = optStringOrNull("series"),
            seriesIndex = if (seriesIndex.isNaN()) null else seriesIndex,
            tags = optStringOrNull("tags"),
            metadataEdited = optBoolean("metadataEdited", false),
            exportCoverPath = optStringOrNull("exportCoverPath"),
            isFavorite = optBoolean("isFavorite", false),
            readingStatus = status,
            scanRootUri = optStringOrNull("scanRootUri"),
            scanParentId = optStringOrNull("scanParentId")
        )
    }

    private fun CustomShelf.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
    }

    private fun JSONObject.toShelf(): CustomShelf? {
        val id = optLong("id", -1L)
        val name = optString("name", "").trim()
        return if (id > 0 && name.isNotEmpty()) CustomShelf(id, name) else null
    }

    private fun CustomShelfBook.toJson(): JSONObject = JSONObject().apply {
        put("shelfId", shelfId)
        put("bookId", bookId)
    }

    private fun JSONObject.toShelfBook(): CustomShelfBook? {
        val shelfId = optLong("shelfId", -1L)
        val bookId = optLong("bookId", -1L)
        return if (shelfId > 0 && bookId > 0) CustomShelfBook(shelfId, bookId) else null
    }

    private fun ConversionLink.toJson(): JSONObject = JSONObject().apply {
        put("sourceBookId", sourceBookId)
        put("outputBookId", outputBookId)
    }

    private fun JSONObject.toConversionLink(): ConversionLink? {
        val source = optLong("sourceBookId", -1L)
        val output = optLong("outputBookId", -1L)
        return if (source > 0 && output > 0) ConversionLink(source, output) else null
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    // ---- preferences ----

    private fun readPrefs(context: Context): JSONObject {
        val root = JSONObject()
        for (name in PREF_NAMES) {
            val all = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
            if (all.isEmpty()) continue
            val values = JSONObject()
            for ((key, value) in all) {
                if (name == "my_ebook_theme_prefs" && key == LAST_AUTO_SCAN_KEY) continue
                values.put(key, encodePref(value) ?: continue)
            }
            if (values.length() > 0) root.put(name, values)
        }
        return root
    }

    private fun encodePref(value: Any?): JSONObject? = when (value) {
        is Boolean -> JSONObject().put("t", "b").put("v", value)
        is Int -> JSONObject().put("t", "i").put("v", value)
        is Long -> JSONObject().put("t", "l").put("v", value)
        is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
        is String -> JSONObject().put("t", "s").put("v", value)
        is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray().apply {
            value.filterIsInstance<String>().forEach { put(it) }
        })
        else -> null
    }

    private fun applyPrefs(context: Context, root: JSONObject) {
        for (name in PREF_NAMES) {
            val values = root.optJSONObject(name) ?: continue
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val editor = prefs.edit().clear()
            val keys = values.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val item = values.optJSONObject(key) ?: continue
                when (item.optString("t")) {
                    "b" -> editor.putBoolean(key, item.optBoolean("v"))
                    "i" -> editor.putInt(key, item.optInt("v"))
                    "l" -> editor.putLong(key, item.optLong("v"))
                    "f" -> editor.putFloat(key, item.optDouble("v").toFloat())
                    "s" -> editor.putString(key, item.optString("v"))
                    "ss" -> editor.putStringSet(key, item.optJSONArray("v").toStrings())
                }
            }
            editor.commit()
        }
    }

    // ---- zip helpers ----

    private fun ZipOutputStream.putText(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun ZipOutputStream.putDir(dir: File, prefix: String): Int {
        if (!dir.isDirectory) return 0
        var count = 0
        dir.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = file.relativeTo(dir).invariantSeparatorsPath
            if (relative.isBlank() || relative.startsWith("..")) return@forEach
            putNextEntry(ZipEntry("$prefix/$relative"))
            file.inputStream().buffered().use { it.copyTo(this) }
            closeEntry()
            count++
        }
        return count
    }

    private fun readEntry(context: Context, uri: Uri, name: String): ByteArray? {
        val input = context.contentResolver.openInputStream(uri) ?: error("Could not open the backup file.")
        input.buffered().use { buffered ->
            ZipInputStream(buffered).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name == name) return zip.readBytes()
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return null
    }

    private fun extractFiles(context: Context, uri: Uri, allowBooks: Boolean): Int {
        var books = 0
        val input = context.contentResolver.openInputStream(uri) ?: error("Could not open the backup file.")
        input.buffered().use { buffered ->
            ZipInputStream(buffered).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = entry.name
                        val allowed = allowBooks || !name.startsWith("books/")
                        val target = if (allowed) targetFor(context, name) else null
                        if (target != null) {
                            target.parentFile?.mkdirs()
                            val temp = File(target.parentFile, target.name + ".restore_tmp")
                            temp.outputStream().buffered().use { out -> zip.copyTo(out) }
                            if (!temp.renameTo(target)) {
                                temp.copyTo(target, overwrite = true)
                                temp.delete()
                            }
                            if (name.startsWith("books/")) books++
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return books
    }

    private fun targetFor(context: Context, name: String): File? {
        if (name.startsWith("/") || name.split('/').any { it == ".." }) return null
        val roots = listOf(
            "covers/" to File(context.filesDir, "covers"),
            "covers_export/" to File(context.filesDir, "covers_export"),
            "books/imports/" to File(context.filesDir, "imports"),
            "books/converted_epubs/" to File(context.filesDir, "converted_epubs")
        )
        for ((prefix, dir) in roots) {
            if (name.startsWith(prefix)) {
                val relative = name.removePrefix(prefix)
                if (relative.isNotBlank() && !relative.startsWith("/")) return File(dir, relative)
            }
        }
        return null
    }

    private fun JSONArray?.toObjects(): List<JSONObject> {
        if (this == null) return emptyList()
        val out = mutableListOf<JSONObject>()
        for (i in 0 until length()) optJSONObject(i)?.let { out.add(it) }
        return out
    }

    private fun JSONArray?.toStrings(): Set<String> {
        if (this == null) return emptySet()
        val out = mutableSetOf<String>()
        for (i in 0 until length()) out.add(optString(i))
        return out
    }

    private fun appVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: ""
}
