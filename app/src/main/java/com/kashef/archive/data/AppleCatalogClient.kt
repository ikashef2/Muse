package com.kashef.archive.data

import com.kashef.archive.domain.LatinMetadataNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.absoluteValue

/**
 * A second, keyless catalog source. It is deliberately suggestion-only:
 * fingerprint matches remain stronger than catalog text matches.
 */
class AppleCatalogClient {
    private val mutex = Mutex()
    private var lastRequestAt = 0L

    suspend fun search(track: TrackEntity): List<MetadataCandidate> = mutex.withLock {
        val filename = track.displayName.substringBeforeLast('.').replace('_', ' ').replace('-', ' ')
        val terms = listOf(
            listOf(track.originalArtist, track.originalTitle).filter(String::isNotBlank).joinToString(" "),
            listOf(track.artist, track.title).filter(String::isNotBlank).joinToString(" "),
            filename,
        ).filter(String::isNotBlank).distinct().take(2)

        terms.flatMap { term ->
            val waitMs = 400L - (System.currentTimeMillis() - lastRequestAt)
            if (waitMs > 0) delay(waitMs)
            val encoded = URLEncoder.encode(term, StandardCharsets.UTF_8.name())
            val json = requestJson(
                "https://itunes.apple.com/search?term=$encoded&media=music&entity=song&country=US&limit=12"
            )
            lastRequestAt = System.currentTimeMillis()
            parse(json, track)
        }
            .distinctBy(MetadataCandidate::recordingId)
            .filter { it.confidence >= 42 }
            .sortedByDescending(MetadataCandidate::confidence)
            .take(8)
    }

    private suspend fun requestJson(url: String): String = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Muse/0.4.1 (https://github.com/ikashef2/Muse)")
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Apple catalog returned HTTP ${connection.responseCode}")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    internal fun parse(json: String, local: TrackEntity): List<MetadataCandidate> {
        val results = JSONObject(json).optJSONArray("results") ?: JSONArray()
        return (0 until results.length()).mapNotNull { index ->
            val item = results.optJSONObject(index) ?: return@mapNotNull null
            val rawTitle = item.optString("trackName").trim()
            val rawArtist = item.optString("artistName").trim()
            if (rawTitle.isBlank() || rawArtist.isBlank()) return@mapNotNull null
            val duration = item.optLong("trackTimeMillis").takeIf { it > 0 }
            val difference = duration?.let { (it - local.durationMs).absoluteValue }
            val confidence = catalogConfidence(local, rawTitle, rawArtist, difference)
            MetadataCandidate(
                recordingId = "apple:${item.optLong("trackId")}",
                title = LatinMetadataNormalizer.canonicalize(rawTitle).latin,
                artist = LatinMetadataNormalizer.canonicalize(rawArtist).latin,
                albumArtist = LatinMetadataNormalizer.canonicalize(
                    item.optString("collectionArtistName").ifBlank { rawArtist }
                ).latin,
                album = LatinMetadataNormalizer.canonicalize(item.optString("collectionName")).latin,
                genre = item.optString("primaryGenreName"),
                year = item.optString("releaseDate").take(4).toIntOrNull(),
                confidence = confidence,
                durationDifferenceMs = difference,
                source = "Apple catalog search",
            )
        }
    }

    private fun catalogConfidence(
        local: TrackEntity,
        remoteTitle: String,
        remoteArtist: String,
        durationDifferenceMs: Long?,
    ): Int {
        val localTitles = listOf(local.originalTitle, local.title, local.displayName.substringBeforeLast('.'))
        val localArtists = listOf(local.originalArtist, local.artist)
        val title = localTitles.maxOfOrNull { similarity(it, remoteTitle) } ?: 0
        val artist = localArtists.maxOfOrNull { similarity(it, remoteArtist) } ?: 0
        val duration = durationDifferenceMs?.let { (25 - (it / 1_000L).coerceAtMost(25L)).toInt() } ?: 5
        return (title * 55 / 100 + artist * 20 / 100 + duration).coerceIn(0, 92)
    }

    private fun similarity(left: String, right: String): Int {
        val a = tokens(left)
        val b = tokens(right)
        if (a.isEmpty() || b.isEmpty()) return 0
        if (a == b) return 100
        val overlap = a.intersect(b).size
        return (overlap * 200 / (a.size + b.size)).coerceIn(0, 100)
    }

    private fun tokens(value: String): Set<String> = value
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .split(Regex("\\s+"))
        .filter { it.length > 1 }
        .toSet()
}
