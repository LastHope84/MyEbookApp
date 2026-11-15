package com.example.myebookapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.math.roundToInt

data class BookRecord(
    val uriString: String,
    val fileName: String,
    val title: String,
    val author: String,
    val publishingDate: String,
    val publisher: String,
    val language: String,
    val coverPath: String?,
    val lastLocatorJson: String?,
    val progressPercent: Double,
    val lastOpenedAt: Long
)

object BookHistoryStore {

    private const val PREFS_NAME = "my_ebook_history_prefs"
    private const val KEY_BOOKS = "books_json"
    private const val KEY_THEME = "reader_theme"
    private const val KEY_FONT_SIZE = "reader_font_size"

    @Synchronized
    fun getAll(context: Context): List<BookRecord> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_BOOKS, null) ?: return emptyList()
        val list = mutableListOf<BookRecord>()
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    BookRecord(
                        uriString = obj.getString("uriString"),
                        fileName = obj.optString("fileName", "Unknown"),
                        title = obj.optString("title", "Unknown"),
                        author = obj.optString("author", "Unknown"),
                        publishingDate = obj.optString("publishingDate", "Unknown"),
                        publisher = obj.optString("publisher", "Unknown"),
                        language = obj.optString("language", "Unknown"),
                        coverPath = obj.optString("coverPath", "").ifBlank { null },
                        lastLocatorJson = obj.optString("lastLocatorJson", "").ifBlank { null },
                        progressPercent = obj.optDouble("progressPercent", 0.0),
                        lastOpenedAt = obj.optLong("lastOpenedAt", 0L)
                    )
                )
            }
        }
        return list.sortedByDescending { it.lastOpenedAt }
    }

    @Synchronized
    fun upsertFromMetadata(context: Context, uriString: String, meta: EpubMetadata): BookRecord {
        val current = getAll(context).toMutableList()
        val existingIndex = current.indexOfFirst { it.uriString == uriString }
        val existing = if (existingIndex >= 0) current[existingIndex] else null

        val coverPath = saveCoverThumbnail(context, uriString, meta.coverBitmap)
            ?: existing?.coverPath

        val updated = BookRecord(
            uriString = uriString,
            fileName = meta.fileName,
            title = meta.title,
            author = meta.author,
            publishingDate = meta.publishingDate,
            publisher = meta.publisher,
            language = meta.language,
            coverPath = coverPath,
            lastLocatorJson = existing?.lastLocatorJson,
            progressPercent = existing?.progressPercent ?: 0.0,
            lastOpenedAt = System.currentTimeMillis()
        )

        if (existingIndex >= 0) {
            current[existingIndex] = updated
        } else {
            current.add(0, updated)
        }
        saveAll(context, current)
        return updated
    }

    @Synchronized
    fun touchOpened(context: Context, uriString: String) {
        val current = getAll(context).toMutableList()
        val index = current.indexOfFirst { it.uriString == uriString }
        if (index >= 0) {
            current[index] = current[index].copy(lastOpenedAt = System.currentTimeMillis())
            saveAll(context, current)
        }
    }

    @Synchronized
    fun saveLocator(
        context: Context,
        uriString: String,
        locatorJson: String,
        progress: Double
    ) {
        val current = getAll(context).toMutableList()
        val index = current.indexOfFirst { it.uriString == uriString }
        if (index >= 0) {
            current[index] = current[index].copy(
                lastLocatorJson = locatorJson,
                progressPercent = progress.coerceIn(0.0, 1.0),
                lastOpenedAt = System.currentTimeMillis()
            )
            saveAll(context, current)
        }
    }

    @Synchronized
    fun getLocatorJson(context: Context, uriString: String): String? {
        return getAll(context).firstOrNull { it.uriString == uriString }?.lastLocatorJson
    }

    @Synchronized
    fun remove(context: Context, uriString: String) {
        val current = getAll(context).toMutableList()
        val removed = current.firstOrNull { it.uriString == uriString }
        removed?.coverPath?.let { path -> runCatching { File(path).delete() } }
        current.removeAll { it.uriString == uriString }
        saveAll(context, current)
    }

    fun loadCoverBitmap(coverPath: String?): Bitmap? {
        if (coverPath.isNullOrBlank()) return null
        val file = File(coverPath)
        if (!file.exists()) return null
        return runCatching { BitmapFactory.decodeFile(coverPath) }.getOrNull()
    }

    fun saveReaderPrefs(context: Context, themeName: String, fontSize: Double) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, themeName)
            .putFloat(KEY_FONT_SIZE, fontSize.toFloat())
            .apply()
    }

    fun getReaderTheme(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME, "LIGHT") ?: "LIGHT"
    }

    fun getReaderFontSize(context: Context): Double {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_FONT_SIZE, 1.0f)
            .toDouble()
    }

    private fun saveAll(context: Context, list: List<BookRecord>) {
        val sorted = list.sortedByDescending { it.lastOpenedAt }
        val array = JSONArray()
        for (item in sorted) {
            val obj = JSONObject().apply {
                put("uriString", item.uriString)
                put("fileName", item.fileName)
                put("title", item.title)
                put("author", item.author)
                put("publishingDate", item.publishingDate)
                put("publisher", item.publisher)
                put("language", item.language)
                put("coverPath", item.coverPath ?: "")
                put("lastLocatorJson", item.lastLocatorJson ?: "")
                put("progressPercent", item.progressPercent)
                put("lastOpenedAt", item.lastOpenedAt)
            }
            array.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BOOKS, array.toString())
            .apply()
    }

    private fun saveCoverThumbnail(context: Context, uriString: String, bitmap: Bitmap?): String? {
        if (bitmap == null) return null
        return runCatching {
            val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
            val file = File(coversDir, "${sha256(uriString)}.jpg")

            val maxHeight = 420f
            val scale = if (bitmap.height > maxHeight) maxHeight / bitmap.height else 1f
            val target = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                    (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                    true
                )
            } else {
                bitmap
            }

            file.outputStream().use { out ->
                target.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            file.absolutePath
        }.getOrNull()
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(24)
    }
}
