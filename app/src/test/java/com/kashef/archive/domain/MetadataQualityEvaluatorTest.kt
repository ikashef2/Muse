package com.kashef.archive.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataQualityEvaluatorTest {
    private val evaluator = MetadataQualityEvaluator()

    @Test
    fun completeMetadataScoresOneHundred() {
        val result = evaluator.evaluate(
            MetadataSnapshot(
                displayName = "01 - Time.flac",
                title = "Time",
                artist = "Pink Floyd",
                albumArtist = "Pink Floyd",
                album = "The Dark Side of the Moon",
                genre = "Progressive Rock",
                year = 1973,
                trackNumber = 4,
                durationMs = 413_000,
                bitrate = 900_000,
                hasArtwork = true,
            )
        )
        assertEquals(100, result.score)
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun dirtyUnknownFileIsQuarantinedByScore() {
        val result = evaluator.evaluate(
            MetadataSnapshot(
                displayName = "unknown official audio youtube 320kbps.mp3",
                title = null,
                artist = "Unknown Artist",
                albumArtist = null,
                album = null,
                genre = null,
                year = null,
                trackNumber = null,
                durationMs = 240_000,
                bitrate = 96_000,
                hasArtwork = false,
            )
        )
        assertEquals(0, result.score)
        assertTrue(result.issues.contains(MetadataIssue.UNKNOWN_ARTIST))
        assertTrue(result.issues.contains(MetadataIssue.DIRTY_FILENAME))
    }
}
