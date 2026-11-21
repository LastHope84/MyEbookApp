package com.example.myebookapp

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class DictionaryEntry(
    val word: String,
    val source: String,
    val definitions: List<String>
)

object WordDictionary {

    fun lookup(rawSelection: String, preferredLang: String): DictionaryEntry? {
        val cleaned = rawSelection
            .trim()
            .replace(Regex("[\\r\\n]+"), " ")
            .trim { !it.isLetter() && it != '\'' && it != '-' }
            .lowercase()

        if (cleaned.isBlank()) return null
        val singleWord = cleaned.substringBefore(" ")
        val preferItalian = preferredLang.lowercase().startsWith("it")

        if (preferItalian) {
            lookupWiktionaryIt(cleaned)?.let { return it }
            if (singleWord != cleaned) lookupWiktionaryIt(singleWord)?.let { return it }
            lookupFreeDictionaryEn(singleWord)?.let { return it }
            lookupWiktionaryEnRest(singleWord)?.let { return it }
        } else {
            lookupFreeDictionaryEn(singleWord)?.let { return it }
            lookupWiktionaryEnRest(singleWord)?.let { return it }
            lookupWiktionaryIt(singleWord)?.let { return it }
        }
        return null
    }

    private fun lookupWiktionaryIt(word: String): DictionaryEntry? {
        return runCatching {
            val encoded = URLEncoder.encode(word, "UTF-8")
            val url = "https://it.wiktionary.org/w/api.php?action=query&prop=extracts&explaintext=1&titles=$encoded&format=json&redirects=1"
            val body = fetchText(url) ?: return null
            val pages = JSONObject(body).optJSONObject("query")?.optJSONObject("pages") ?: return null
            val keys = pages.keys()
            while (keys.hasNext()) {
                val pageId = keys.next()
                if (pageId == "-1") continue
                val extract = pages.optJSONObject(pageId)?.optString("extract", "")?.trim() ?: ""
                if (extract.isBlank()) continue

                val defs = parseWiktionaryPlainText(extract)
                if (defs.isNotEmpty()) {
                    return DictionaryEntry(word, "Wikizionario (IT)", defs.take(8))
                }
            }
            null
        }.getOrNull()
    }

    private fun parseWiktionaryPlainText(extract: String): List<String> {
        val lines = extract.lines().map { it.trim() }
        val result = mutableListOf<String>()
        var inItalianSection = true
        var skipSection = false
        val stopHeaders = listOf(
            "traduzion", "etimolog", "sillabaz", "pronunci", "sinonim",
            "antonim", "alterat", "derivat", "proverbi", "note", "bibliograf", "collegamenti"
        )

        for (line in lines) {
            if (line.isBlank()) continue
            if (line.startsWith("== ") && line.endsWith(" ==") && !line.startsWith("===")) {
                val langHeader = line.removeSurrounding("== ", " ==").lowercase()
                inItalianSection = langHeader.contains("italian")
                skipSection = false
                continue
            }
            if (!inItalianSection) continue

            if (line.startsWith("===")) {
                val header = line.trim('=', ' ')
                skipSection = stopHeaders.any { header.lowercase().contains(it) }
                if (!skipSection && header.isNotBlank() && result.size < 8) {
                    result.add("[$header]")
                }
                continue
            }

            if (!skipSection && line.length > 4) {
                result.add(line)
                if (result.size >= 8) break
            }
        }
        return result.filterNot { it.startsWith("[") && it == result.last() }
    }

    private fun lookupFreeDictionaryEn(word: String): DictionaryEntry? {
        return runCatching {
            val encoded = URLEncoder.encode(word, "UTF-8")
            val body = fetchText("https://api.dictionaryapi.dev/api/v2/entries/en/$encoded") ?: return null
            val arr = JSONArray(body)
            val defs = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val meanings = arr.optJSONObject(i)?.optJSONArray("meanings") ?: continue
                for (m in 0 until meanings.length()) {
                    val meaning = meanings.optJSONObject(m) ?: continue
                    val pos = meaning.optString("partOfSpeech", "").trim()
                    val defArr = meaning.optJSONArray("definitions") ?: continue
                    for (d in 0 until defArr.length()) {
                        val text = defArr.optJSONObject(d)?.optString("definition", "")?.trim() ?: ""
                        if (text.isNotBlank()) {
                            defs.add(if (pos.isNotBlank()) "($pos) $text" else text)
                            if (defs.size >= 7) break
                        }
                    }
                    if (defs.size >= 7) break
                }
                if (defs.size >= 7) break
            }
            if (defs.isEmpty()) null else DictionaryEntry(word, "Dictionary (EN)", defs)
        }.getOrNull()
    }

    private fun lookupWiktionaryEnRest(word: String): DictionaryEntry? {
        return runCatching {
            val encoded = URLEncoder.encode(word, "UTF-8")
            val body = fetchText("https://en.wiktionary.org/api/rest_v1/page/definition/$encoded") ?: return null
            val obj = JSONObject(body)
            val langKeys = (listOf("en", "it") + obj.keys().asSequence().toList()).distinct()
            val defs = mutableListOf<String>()

            for (langKey in langKeys) {
                val arr = obj.optJSONArray(langKey) ?: continue
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val pos = item.optString("partOfSpeech", "").trim()
                    val defArr = item.optJSONArray("definitions") ?: continue
                    for (d in 0 until defArr.length()) {
                        val html = defArr.optJSONObject(d)?.optString("definition", "") ?: ""
                        val plain = stripHtml(html)
                        if (plain.isNotBlank()) {
                            defs.add(if (pos.isNotBlank()) "($pos) $plain" else plain)
                            if (defs.size >= 7) break
                        }
                    }
                    if (defs.size >= 7) break
                }
                if (defs.isNotEmpty()) return DictionaryEntry(word, "Wiktionary ($langKey)", defs)
            }
            null
        }.getOrNull()
    }

    private fun stripHtml(html: String): String {
        return html
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun fetchText(urlString: String): String? {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 7_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MyEbookApp/1.0 (Android)")
        }
        return try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
