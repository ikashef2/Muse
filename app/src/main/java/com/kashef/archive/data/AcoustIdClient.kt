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

data class AcoustIdMatch(
    val acoustId: String,
    val score: Int,
    val recordingIds: List<String>,
)

class AcoustIdClient(private val clientKey: String) {
    private val mutex = Mutex()
    private var lastRequestAt = 0L

    val isConfigured: Boolean get() = clientKey.isNotBlank()

    suspend fun lookup(fingerprint: AudioFingerprint, trackDurationMs: Long): List<AcoustIdMatch> = mutex.withLock {
        require(isConfigured) { "AcoustID is not configured in this build." }
        val waitMs = 350L - (System.currentTimeMillis() - lastRequestAt)
        if (waitMs > 0) delay(waitMs)
        try {
            withContext(Dispatchers.IO) { request(fingerprint, trackDurationMs) }
        } finally {
            lastRequestAt = System.currentTimeMillis()
        }
    }

    private fun request(fingerprint: AudioFingerprint, trackDurationMs: Long): List<AcoustIdMatch> {
        val durationSeconds = (trackDurationMs / 1_000L).coerceAtLeast(fingerprint.analyzedDurationSeconds.toLong())
        val encoded = URLEncoder.encode(fingerprint.encoded, StandardCharsets.UTF_8.name())
        val key = URLEncoder.encode(clientKey, StandardCharsets.UTF_8.name())
        val url = "https://api.acoustid.org/v2/lookup" +
            "?client=$key&format=json&meta=recordings&duration=$durationSeconds&fingerprint=$encoded"
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Muse/0.4.2 (https://github.com/ikashef2/Muse)")
        return try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("AcoustID returned HTTP ${connection.responseCode}")
            }
            parse(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    internal fun parse(json: String): List<AcoustIdMatch> {
        val root = JSONObject(json)
        if (root.optString("status") != "ok") return emptyList()
        val results = root.optJSONArray("results") ?: JSONArray()
        return (0 until results.length()).mapNotNull { index ->
            val result = results.optJSONObject(index) ?: return@mapNotNull null
            val recordings = result.optJSONArray("recordings") ?: JSONArray()
            val ids = (0 until recordings.length()).mapNotNull { recordingIndex ->
                recordings.optJSONObject(recordingIndex)?.optString("id")?.takeIf(String::isNotBlank)
            }.distinct()
            if (ids.isEmpty()) return@mapNotNull null
            AcoustIdMatch(
                acoustId = result.optString("id"),
                score = (result.optDouble("score", 0.0) * 100).toInt().coerceIn(0, 100),
                recordingIds = ids,
            )
        }.sortedByDescending(AcoustIdMatch::score)
    }
}
