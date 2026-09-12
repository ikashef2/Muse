package com.kashef.archive.data

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

class MusicBrainzClient {
    private val requestMutex = Mutex()
    private var lastRequestAt = 0L

    suspend fun search(track: TrackEntity): List<MetadataCandidate> = requestMutex.withLock {
        val waitMs = 1_100L - (System.currentTimeMillis() - lastRequestAt)
        if (waitMs > 0) delay(waitMs)
        try {
            withContext(Dispatchers.IO) { request(track) }
        } finally {
            lastRequestAt = System.currentTimeMillis()
        }
    }

    private fun request(track: TrackEntity): List<MetadataCandidate> {
        val query = buildString {
            append("recording:\"").append(clean(track.title.ifBlank { track.displayName.substringBeforeLast('.') })).append('"')
            if (track.artist.isNotBlank() && !track.artist.contains("unknown", ignoreCase = true)) {
                append(" AND artist:\"").append(clean(track.artist)).append('"')
            }
        }
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val connection = URL("https://musicbrainz.org/ws/2/recording/?query=$encoded&fmt=json&limit=5")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 12_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Muse/0.3.0 (https://github.com/ikashef2/Muse)")
        return try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("MusicBrainz returned HTTP ${connection.responseCode}")
            }
            parse(connection.inputStream.bufferedReader().use { it.readText() }, track.durationMs)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parse(json: String, localDurationMs: Long): List<MetadataCandidate> {
        val recordings = JSONObject(json).optJSONArray("recordings") ?: JSONArray()
        return (0 until recordings.length()).mapNotNull { index ->
            val recording = recordings.optJSONObject(index) ?: return@mapNotNull null
            val title = recording.optString("title").trim()
            if (title.isBlank()) return@mapNotNull null
            val artist = recording.optJSONArray("artist-credit").creditNames()
            val release = recording.optJSONArray("releases")?.optJSONObject(0)
            val album = release?.optString("title").orEmpty().trim()
            val albumArtist = release?.optJSONArray("artist-credit").creditNames().ifBlank { artist }
            val date = release?.optString("date").orEmpty()
            val remoteDuration = recording.optLong("length").takeIf { it > 0 }
            val difference = remoteDuration?.let { (it - localDurationMs).absoluteValue }
            val durationPenalty = difference?.let { (it / 1_000L).coerceAtMost(25L).toInt() } ?: 8
            val serviceScore = recording.optInt("score", 0)
            val confidence = (serviceScore - durationPenalty).coerceIn(0, 100)
            MetadataCandidate(
                recordingId = recording.optString("id"),
                title = title,
                artist = artist,
                albumArtist = albumArtist,
                album = album,
                genre = recording.bestGenre(),
                year = date.take(4).toIntOrNull(),
                confidence = confidence,
                durationDifferenceMs = difference,
            )
        }.sortedByDescending(MetadataCandidate::confidence)
    }

    private fun clean(value: String): String = value.replace(Regex("[\\\"\\r\\n]"), " ").trim()
}

private fun JSONArray?.creditNames(): String {
    if (this == null) return ""
    return (0 until length()).mapNotNull { index ->
        optJSONObject(index)?.let { credit ->
            credit.optString("name").takeIf(String::isNotBlank)?.plus(credit.optString("joinphrase"))
        }
    }
        .joinToString("")
}

private fun JSONObject.bestGenre(): String {
    val genres = optJSONArray("genres") ?: optJSONArray("tags") ?: return ""
    return (0 until genres.length())
        .mapNotNull { genres.optJSONObject(it) }
        .maxByOrNull { it.optInt("count", 0) }
        ?.optString("name")
        .orEmpty()
}
