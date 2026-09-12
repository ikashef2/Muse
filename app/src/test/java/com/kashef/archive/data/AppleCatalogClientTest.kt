package com.kashef.archive.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleCatalogClientTest {
    @Test
    fun parsesAHighConfidenceCatalogMatch() {
        val local = track(title = "Delam Gerefte", artist = "Mohsen Chavoshi")
        val json = """
            {
              "resultCount": 1,
              "results": [{
                "trackId": 42,
                "trackName": "Delam Gerefte",
                "artistName": "Mohsen Chavoshi",
                "collectionName": "Single",
                "primaryGenreName": "Pop",
                "releaseDate": "2024-03-01T12:00:00Z",
                "trackTimeMillis": 180500
              }]
            }
        """.trimIndent()

        val candidate = AppleCatalogClient().parse(json, local).single()

        assertEquals("apple:42", candidate.recordingId)
        assertEquals("Mohsen Chavoshi", candidate.artist)
        assertEquals(2024, candidate.year)
        assertTrue(candidate.confidence >= 90)
    }

    private fun track(title: String, artist: String) = TrackEntity(
        contentUri = "content://track",
        mediaStoreId = 1,
        displayName = "$artist - $title.mp3",
        title = title,
        artist = artist,
        albumArtist = artist,
        album = "",
        genre = "",
        year = null,
        trackNumber = null,
        discNumber = null,
        durationMs = 180_000,
        sizeBytes = 1_000,
        mimeType = "audio/mpeg",
        bitrate = 320_000,
        hasArtwork = false,
        dateModifiedSeconds = 1,
        healthScore = 50,
        issueCodes = "",
        status = ArchiveStatus.UNIDENTIFIED,
    )
}
