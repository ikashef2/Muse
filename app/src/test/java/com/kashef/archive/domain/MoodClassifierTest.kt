package com.kashef.archive.domain

import com.kashef.archive.data.ArchiveStatus
import com.kashef.archive.data.TrackEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoodClassifierTest {
    @Test
    fun manualCorrectionOverridesAutomaticMood() {
        val track = track(inferred = "ENERGY", manual = "!ENERGY|NIGHT")
        assertFalse("ENERGY" in track.moods)
        assertTrue("NIGHT" in track.moods)
    }

    @Test
    fun infersStructuredMetalMoods() {
        val moods = MoodClassifier.infer("Ghost of Perdition", "Ghost Reveries", "Progressive Death Metal")
        assertTrue("AGGRESSIVE" in moods || "ENERGY" in moods)
        assertTrue("INTROSPECTIVE" in moods || "FOCUS" in moods)
    }

    private fun track(inferred: String, manual: String = "") = TrackEntity(
        contentUri = "track", mediaStoreId = 1, displayName = "track.mp3", title = "Track",
        artist = "Artist", albumArtist = "Artist", album = "Album", genre = "Rock", year = 2024,
        trackNumber = 1, discNumber = 1, durationMs = 180_000, sizeBytes = 1_000,
        mimeType = "audio/mpeg", bitrate = 320_000, hasArtwork = true, dateModifiedSeconds = 1,
        healthScore = 90, issueCodes = "", status = ArchiveStatus.NEEDS_REVIEW,
        inferredMoodTags = inferred, manualMoodTags = manual,
    )
}
