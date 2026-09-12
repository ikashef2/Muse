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

class MusicBrainzClient {
    private val requestMutex = Mutex()
    private var lastRequestAt = 0L

    suspend fun search(track: TrackEntity): List<MetadataCandidate> = requestMutex.withLock {
        val rawTitle = track.originalTitle.ifBlank { track.title.ifBlank { track.displayName.substringBeforeLast('.') } }
        val rawArtist = track.originalArtist.ifBlank { track.artist }
        val filename = track.displayName.substringBeforeLast('.').replace('_', ' ').replace('-', ' ')
        val queries = listOf(
            recordingQuery(rawTitle, rawArtist),
            recordingQuery(track.title, track.artist),
            recordingQuery(filename, ""),
        ).filter(String::isNotBlank).distinct().take(3)

        queries.flatMap { query ->
            val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
            val json = requestJson("https://musicbrainz.org/ws/2/recording/?query=$encoded&fmt=json&limit=8")
            parse(json, track.durationMs)
        }
            .distinctBy { it.recordingId }
            .sortedByDescending(MetadataCandidate::confidence)
            .take(8)
    }

    suspend fun lookupByRecordingIds(
        recordingIds: List<String>,
        localDurationMs: Long,
        acoustId: String,
        acoustConfidence: Int,
    ): List<MetadataCandidate> = requestMutex.withLock {
        recordingIds.distinct().take(3).mapNotNull { id ->
            val json = requestJson(
                "https://musicbrainz.org/ws/2/recording/$id?inc=artists+releases+genres&fmt=json"
            )
            parseRecording(JSONObject(json), localDurationMs, acoustConfidence)
                ?.copy(source = "AcoustID fingerprint", acoustId = acoustId)
        }.sortedByDescending(MetadataCandidate::confidence)
    }

    private suspend fun requestJson(url: String): String {
        val waitMs = 1_100L - (System.currentTimeMillis() - lastRequestAt)
        if (waitMs > 0) delay(waitMs)
        return try {
            withContext(Dispatchers.IO) {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 12_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "Muse/0.4.1 (https://github.com/ikashef2/Muse)")
                try {
                    if (connection.responseCode !in 200..299) {
                        throw IllegalStateException("MusicBrainz returned HTTP ${connection.responseCode}")
                    }
                    connection.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    connection.disconnect()
                }
            }
        } finally {
            lastRequestAt = System.currentTimeMillis()
        }
    }

    internal fun parse(json: String, localDurationMs: Long): List<MetadataCandidate> {
        val recordings = JSONObject(json).optJSONArray("recordings") ?: JSONArray()
        return (0 until recordings.length()).mapNotNull { index ->
            recordings.optJSONObject(index)?.let { recording ->
                parseRecording(recording, localDurationMs, recording.optInt("score", 0))
            }
        }.sortedByDescending(MetadataCandidate::confidence)
    }

    private fun parseRecording(
        recording: JSONObject,
        localDurationMs: Long,
        serviceScore: Int,
    ): MetadataCandidate? {
        val rawTitle = recording.optString("title").trim()
        if (rawTitle.isBlank()) return null
        val rawArtist = recording.optJSONArray("artist-credit").creditNames()
        val release = recording.optJSONArray("releases")?.optJSONObject(0)
        val rawAlbum = release?.optString("title").orEmpty().trim()
        val rawAlbumArtist = release?.optJSONArray("artist-credit").creditNames().ifBlank { rawArtist }
        val title = LatinMetadataNormalizer.canonicalize(rawTitle).latin
        val artist = LatinMetadataNormalizer.canonicalize(rawArtist).latin
        val album = LatinMetadataNormalizer.canonicalize(rawAlbum).latin
        val albumArtist = LatinMetadataNormalizer.canonicalize(rawAlbumArtist).latin
        val date = release?.optString("date").orEmpty()
        val remoteDuration = recording.optLong("length").takeIf { it > 0 }
        val difference = remoteDuration?.let { (it - localDurationMs).absoluteValue }
        val durationPenalty = difference?.let { (it / 1_000L).coerceAtMost(25L).toInt() } ?: 8
        return MetadataCandidate(
            recordingId = recording.optString("id"),
            title = title,
            artist = artist,
            albumArtist = albumArtist,
            album = album,
            genre = recording.bestGenre(),
            year = date.take(4).toIntOrNull(),
            confidence = (serviceScore - durationPenalty).coerceIn(0, 100),
            durationDifferenceMs = difference,
        )
    }

    private fun recordingQuery(title: String, artist: String): String {
        if (title.isBlank()) return ""
        return buildString {
            append("recording:\"").append(clean(title)).append('\"')
            if (artist.isNotBlank() && !artist.contains("unknown", ignoreCase = true)) {
                append(" AND artist:\"").append(clean(artist)).append('\"')
            }
        }
    }

    private fun clean(value: String): String = value.replace(Regex("[\\\"\\r\\n]"), " ").trim()
}

private fun JSONArray?.creditNames(): String {
    if (this == null) return ""
    return (0 until length()).mapNotNull { index ->
        optJSONObject(index)?.let { credit ->
            credit.optString("name").takeIf(String::isNotBlank)?.plus(credit.optString("joinphrase"))
        }
    }.joinToString("")
}

private fun JSONObject.bestGenre(): String {
    val genres = optJSONArray("genres") ?: optJSONArray("tags") ?: return ""
    return (0 until genres.length())
        .mapNotNull { genres.optJSONObject(it) }
        .maxByOrNull { it.optInt("count", 0) }
        ?.optString("name")
        .orEmpty()
}
