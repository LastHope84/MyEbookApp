package com.example.myebookapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class OnlineBookCandidate(
    val source: String,
    val title: String,
    val author: String,
    val publishingDate: String,
    val publisher: String,
    val language: String,
    val coverUrl: String?
)

object OnlineMetadataFetcher {

    fun search(rawTitle: String, rawAuthor: String): List<OnlineBookCandidate> {
        val cleanTitle = cleanTitleForSearch(rawTitle)
        val cleanAuthor = cleanAuthorForSearch(rawAuthor)
        if (cleanTitle.isBlank() && cleanAuthor.isBlank()) return emptyList()

        val results = mutableListOf<OnlineBookCandidate>()
        runCatching { results.addAll(searchGoogleBooks(cleanTitle, cleanAuthor)) }

        if (results.size < 3) {
            runCatching { results.addAll(searchOpenLibrary(cleanTitle, cleanAuthor)) }
        }
        return results.take(8)
    }

    fun downloadCoverBitmap(urlString: String, maxDimension: Int = 1400): Bitmap? {
        return runCatching {
            val bytes = fetchBytes(urlString) ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sampleSize = 1
            while (bounds.outWidth / sampleSize > maxDimension ||
                bounds.outHeight / sampleSize > maxDimension
            ) {
                sampleSize *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }.getOrNull()
    }

    private fun searchGoogleBooks(title: String, author: String): List<OnlineBookCandidate> {
        val parts = mutableListOf<String>()
        if (title.isNotBlank()) parts.add("intitle:$title")
        if (author.isNotBlank()) parts.add("inauthor:$author")
        val query = URLEncoder.encode(parts.joinToString(" "), "UTF-8")
        val url = "https://www.googleapis.com/books/v1/volumes?q=$query&maxResults=6"

        val body = fetchText(url) ?: return emptyList()
        val json = JSONObject(body)
        val items = json.optJSONArray("items") ?: return emptyList()
        val list = mutableListOf<OnlineBookCandidate>()

        for (i in 0 until items.length()) {
            val info = items.optJSONObject(i)?.optJSONObject("volumeInfo") ?: continue
            val t = info.optString("title", "").trim()
            if (t.isBlank()) continue

            val authorsArr = info.optJSONArray("authors")
            val authorsList = mutableListOf<String>()
            if (authorsArr != null) {
                for (a in 0 until authorsArr.length()) {
                    val name = authorsArr.optString(a, "").trim()
                    if (name.isNotBlank()) authorsList.add(name)
                }
            }

            val imageLinks = info.optJSONObject("imageLinks")
            val rawThumb = imageLinks?.optString("large", "")
                ?.ifBlank { imageLinks.optString("medium", "") }
                ?.ifBlank { imageLinks.optString("thumbnail", "") }
                ?.ifBlank { imageLinks.optString("smallThumbnail", "") }
                ?.ifBlank { null }

            val coverUrl = rawThumb
                ?.replace("http://", "https://")
                ?.replace("&edge=curl", "")

            list.add(
                OnlineBookCandidate(
                    source = "Google Books",
                    title = t,
                    author = authorsList.joinToString("; "),
                    publishingDate = info.optString("publishedDate", "").trim(),
                    publisher = info.optString("publisher", "").trim(),
                    language = info.optString("language", "").trim(),
                    coverUrl = coverUrl
                )
            )
        }
        return list
    }

    private fun searchOpenLibrary(title: String, author: String): List<OnlineBookCandidate> {
        val params = mutableListOf<String>()
        if (title.isNotBlank()) params.add("title=${URLEncoder.encode(title, "UTF-8")}")
        if (author.isNotBlank()) params.add("author=${URLEncoder.encode(author, "UTF-8")}")
        params.add("limit=6")
        val url = "https://openlibrary.org/search.json?${params.joinToString("&")}"

        val body = fetchText(url) ?: return emptyList()
        val json = JSONObject(body)
        val docs = json.optJSONArray("docs") ?: return emptyList()
        val list = mutableListOf<OnlineBookCandidate>()

        for (i in 0 until docs.length()) {
            val doc = docs.optJSONObject(i) ?: continue
            val t = doc.optString("title", "").trim()
            if (t.isBlank()) continue

            val authorArr = doc.optJSONArray("author_name")
            val authors = mutableListOf<String>()
            if (authorArr != null) {
                for (a in 0 until authorArr.length()) {
                    val name = authorArr.optString(a, "").trim()
                    if (name.isNotBlank()) authors.add(name)
                }
            }

            val publisher = doc.optJSONArray("publisher")?.optString(0, "")?.trim() ?: ""
            val year = doc.optInt("first_publish_year", 0)
            val date = if (year > 0) year.toString() else ""
            val rawLang = doc.optJSONArray("language")?.optString(0, "")?.trim() ?: ""
            val lang = normalizeLang(rawLang)
            val coverId = doc.optLong("cover_i", 0L)
            val coverUrl = if (coverId > 0L) "https://covers.openlibrary.org/b/id/$coverId-L.jpg" else null

            list.add(
                OnlineBookCandidate(
                    source = "Open Library",
                    title = t,
                    author = authors.joinToString("; "),
                    publishingDate = date,
                    publisher = publisher,
                    language = lang,
                    coverUrl = coverUrl
                )
            )
        }
        return list
    }

    private fun fetchText(urlString: String): String? {
        val bytes = fetchBytes(urlString) ?: return null
        return String(bytes, Charsets.UTF_8)
    }

    private fun fetchBytes(urlString: String, redirectsLeft: Int = 4): ByteArray? {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 12_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MyEbookApp/1.0 (Android)")
        }
        try {
            val code = conn.responseCode
            if (code in 300..399 && redirectsLeft > 0) {
                val loc = conn.getHeaderField("Location") ?: return null
                val nextUrl = if (loc.startsWith("http://")) loc.replaceFirst("http://", "https://") else loc
                return fetchBytes(nextUrl, redirectsLeft - 1)
            }
            if (code !in 200..299) return null
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun cleanTitleForSearch(raw: String): String {
        return raw
            .replace(Regex("\\([^)]*edition[^)]*\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\[[^]]*]"), "")
            .replace("\"", "")
            .trim()
    }

    private fun cleanAuthorForSearch(raw: String): String {
        val first = raw.split(";").firstOrNull()?.trim() ?: return ""
        if (first.equals("Unknown", ignoreCase = true)) return ""
        val commaParts = first.split(",")
        return if (commaParts.size == 2) {
            "${commaParts[1].trim()} ${commaParts[0].trim()}".trim()
        } else {
            first
        }
    }

    private fun normalizeLang(code: String): String {
        return when (code.lowercase()) {
            "ita" -> "it"
            "eng" -> "en"
            "fra", "fre" -> "fr"
            "spa" -> "es"
            "deu", "ger" -> "de"
            "por" -> "pt"
            else -> code
        }
    }
}
