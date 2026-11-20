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
        val shortTitle = cleanTitle.substringBefore(":").substringBefore(" - ").trim()
        val cleanAuthor = cleanAuthorForSearch(rawAuthor)

        if (cleanTitle.isBlank() && cleanAuthor.isBlank()) return emptyList()

        val fullTerm = listOf(cleanTitle, cleanAuthor).filter { it.isNotBlank() }.joinToString(" ")
        val shortTerm = listOf(shortTitle, cleanAuthor).filter { it.isNotBlank() }.joinToString(" ")

        val results = mutableListOf<OnlineBookCandidate>()

        // 1. Apple Books Italy (huge Italian ebook & cover catalog)
        runCatching {
            val itBooks = searchAppleBooks(fullTerm, country = "IT")
            if (itBooks.isNotEmpty()) {
                results.addAll(itBooks)
            } else if (shortTerm != fullTerm && shortTerm.isNotBlank()) {
                results.addAll(searchAppleBooks(shortTerm, country = "IT"))
            }
        }

        // 2. Google Books (relaxed query, with short-title retry)
        runCatching {
            val gb = searchGoogleBooks(fullTerm)
            if (gb.isNotEmpty()) {
                results.addAll(gb)
            } else if (shortTerm != fullTerm && shortTerm.isNotBlank()) {
                results.addAll(searchGoogleBooks(shortTerm))
            }
        }

        // 3. OPAC SBN (Italy's National Library Catalog - great for Italian publishers/dates)
        runCatching {
            val sbnTerm = if (shortTerm.isNotBlank()) shortTerm else fullTerm
            results.addAll(searchOpacSbn(sbnTerm))
        }

        // 4. Apple Books US fallback if still sparse (for English / international books)
        if (results.size < 3) {
            runCatching {
                results.addAll(searchAppleBooks(shortTerm.ifBlank { fullTerm }, country = "US"))
            }
        }

        // 5. Open Library relaxed fallback
        if (results.size < 4) {
            runCatching {
                results.addAll(searchOpenLibrary(shortTerm.ifBlank { fullTerm }))
            }
        }

        return results.take(12)
    }

    fun downloadCoverBitmap(urlString: String, maxDimension: Int = 1400): Bitmap? {
        return runCatching {
            val bytes = fetchBytes(urlString, timeoutMs = 12_000) ?: return null
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

    private fun searchAppleBooks(term: String, country: String): List<OnlineBookCandidate> {
        val encoded = URLEncoder.encode(term, "UTF-8")
        val url = "https://itunes.apple.com/search?term=$encoded&media=ebook&country=$country&limit=6"
        val body = fetchText(url) ?: return emptyList()
        val items = JSONObject(body).optJSONArray("results") ?: return emptyList()

        val list = mutableListOf<OnlineBookCandidate>()
        for (i in 0 until items.length()) {
            val obj = items.optJSONObject(i) ?: continue
            val t = obj.optString("trackName", "").trim()
            if (t.isBlank()) continue

            val author = obj.optString("artistName", "").trim()
            val rawDate = obj.optString("releaseDate", "").trim()
            val date = if (rawDate.length >= 10) rawDate.take(10) else rawDate
            val rawArt = obj.optString("artworkUrl100", "").trim()
            val hiResCover = rawArt
                .takeIf { it.isNotBlank() }
                ?.replace("100x100bb", "600x600bb")
                ?.replace("http://", "https://")

            list.add(
                OnlineBookCandidate(
                    source = if (country == "IT") "Apple Books IT" else "Apple Books",
                    title = t,
                    author = author,
                    publishingDate = date,
                    publisher = "",
                    language = if (country == "IT") "it" else "",
                    coverUrl = hiResCover
                )
            )
        }
        return list
    }

    private fun searchGoogleBooks(term: String): List<OnlineBookCandidate> {
        val query = URLEncoder.encode(term, "UTF-8")
        val url = "https://www.googleapis.com/books/v1/volumes?q=$query&maxResults=6"

        val body = fetchText(url) ?: return emptyList()
        val items = JSONObject(body).optJSONArray("items") ?: return emptyList()
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

    private fun searchOpacSbn(term: String): List<OnlineBookCandidate> {
        val query = URLEncoder.encode(term, "UTF-8")
        val url = "https://opac.sbn.it/opacmobilegw/search.json?any=$query&type=0&start=0&rows=4"
        val body = fetchText(url, timeoutMs = 5_000) ?: return emptyList()
        val records = JSONObject(body).optJSONArray("records") ?: return emptyList()
        val list = mutableListOf<OnlineBookCandidate>()

        for (i in 0 until records.length()) {
            val rec = records.optJSONObject(i) ?: continue
            val rawTitle = rec.optString("titolo", "").trim()
            val cleanTitle = rawTitle.substringBefore(" / ").trim()
            if (cleanTitle.isBlank()) continue

            val author = rec.optString("autorePrincipale", "").trim()
            val pubRaw = rec.optString("pubblicazione", "").trim()
            val year = Regex("(19|20)\\d{2}").find(pubRaw)?.value ?: ""
            val publisher = if (pubRaw.contains(":")) {
                pubRaw.substringAfter(":").substringBefore(",").trim()
            } else {
                ""
            }

            list.add(
                OnlineBookCandidate(
                    source = "OPAC SBN (IT)",
                    title = cleanTitle,
                    author = author,
                    publishingDate = year,
                    publisher = publisher,
                    language = "it",
                    coverUrl = null
                )
            )
        }
        return list
    }

    private fun searchOpenLibrary(term: String): List<OnlineBookCandidate> {
        val query = URLEncoder.encode(term, "UTF-8")
        val url = "https://openlibrary.org/search.json?q=$query&limit=5"

        val body = fetchText(url) ?: return emptyList()
        val docs = JSONObject(body).optJSONArray("docs") ?: return emptyList()
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

    private fun fetchText(urlString: String, timeoutMs: Int = 8_000): String? {
        val bytes = fetchBytes(urlString, timeoutMs = timeoutMs) ?: return null
        return String(bytes, Charsets.UTF_8)
    }

    private fun fetchBytes(
        urlString: String,
        timeoutMs: Int = 8_000,
        redirectsLeft: Int = 4
    ): ByteArray? {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MyEbookApp/1.0 (Android)")
        }
        try {
            val code = conn.responseCode
            if (code in 300..399 && redirectsLeft > 0) {
                val loc = conn.getHeaderField("Location") ?: return null
                val nextUrl = if (loc.startsWith("http://")) loc.replaceFirst("http://", "https://") else loc
                return fetchBytes(nextUrl, timeoutMs, redirectsLeft - 1)
            }
            if (code !in 200..299) return null
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun cleanTitleForSearch(raw: String): String {
        return raw
            .replace(Regex("^\"[^\"]*\\d+[^\"]*\"\\s*"), "")
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
