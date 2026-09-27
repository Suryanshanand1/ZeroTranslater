package com.zerotranslater.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Looks up English word meanings from a local WordNet dataset or an online API.
 *
 * ## Data sources (in priority order)
 * 1. Local gzipped JSON shards in `assets/meanings/` (English-only, ~3-4 MB)
 * 2. dictionaryapi.dev (free, no key required) if [onlineFallbackEnabled] is true
 *
 * Neither source leaks device identifiers. The online request sends only the
 * word in the URL path.
 *
 * ## Threading
 * All lookups run on [Dispatchers.IO]. Local reads are synchronous from an
 * asset stream; network calls use HttpURLConnection with a 5-second timeout.
 */
class MeaningRepository(private val context: Context) {

    /**
     * Whether online lookup is allowed when the local shard has nothing.
     * Default is true because most devices have at least intermittent connectivity.
     */
    var onlineFallbackEnabled = true

    private var cache: Map<String, List<Sense>> = emptyMap()
    private var cacheLoaded = false

    /**
     * Returns meanings for [word], or null when nothing was found anywhere.
     *
     * @param word must be a single, lowercased English word — callers should use
     *   [SingleWordDetector] before calling.
     */
    suspend fun lookup(word: String): WordMeaning? = withContext(Dispatchers.IO) {
        val senses = lookupLocal(word) ?: lookupOnline(word)
        return@withContext if (senses != null && senses.isNotEmpty()) WordMeaning(word, senses) else null
    }

    private fun lookupLocal(word: String): List<Sense>? {
        if (!cacheLoaded) loadCache()
        return cache[word]?.takeIf { it.isNotEmpty() }
    }

    private fun loadCache() {
        synchronized(this) {
            if (cacheLoaded) return
            try {
                val builder = mutableMapOf<String, MutableList<Sense>>()
                for (letter in 'a'..'z') {
                    try {
                        context.assets.open("meanings/shards/${letter}.json.gz").use { gzStream ->
                            val json = GZIPInputStream(gzStream).bufferedReader().use { it.readText() }
                            if (json.isBlank()) return@use
                            val obj = JSONObject(json)
                            obj.keys().forEach { w ->
                                val arr = obj.getJSONArray(w)
                                if (arr.length() > 0) {
                                    val list = mutableListOf<Sense>()
                                    for (i in 0 until minOf(arr.length(), 8)) {
                                        val entry = arr.getJSONObject(i)
                                        val defn = entry.optString("definition", "").trim()
                                        val pos = entry.optString("pos", "n")
                                        if (defn.isNotBlank()) {
                                            list.add(Sense(pos, defn))
                                        }
                                    }
                                    if (list.isNotEmpty()) builder[w] = list
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // Shard missing or corrupt — skip silently.
                    }
                }
                cache = builder.mapValues { it.value.toList() }.toMap()
            } catch (_: Exception) {
                cache = emptyMap()
            }
            cacheLoaded = true
        }
    }

    private suspend fun lookupOnline(word: String): List<Sense>? {
        if (!onlineFallbackEnabled) return null
        return try {
            val url = URL("https://api.dictionaryapi.dev/api/v2/entries/en/$word")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = ONLINE_TIMEOUT_MS
            conn.readTimeout = ONLINE_TIMEOUT_MS
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) return null
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val text = reader.readText()
            val array = JSONArray(text)
            if (array.length() == 0) return null
            val senses = mutableListOf<Sense>()
            for (i in 0 until array.length()) {
                val entry = array.getJSONObject(i)
                val meanings = entry.optJSONArray("meanings") ?: continue
                for (m in 0 until meanings.length()) {
                    val meaning = meanings.getJSONObject(m)
                    val pos = meaning.optString("partOfSpeech", "")
                    val defs = meaning.optJSONArray("definitions") ?: continue
                    for (d in 0 until minOf(defs.length(), 3)) {
                        val def = defs.getJSONObject(d).optString("definition", "").trim()
                        if (def.isNotBlank() && senses.size < 8) {
                            senses.add(Sense(pos.ifEmpty { "n" }, def))
                        }
                    }
                }
            }
            if (senses.isEmpty()) null else senses
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val ONLINE_TIMEOUT_MS = 5_000
    }
}
