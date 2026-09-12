package com.kashef.archive.domain

import com.kashef.archive.data.ArchiveStatus
import com.kashef.archive.data.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistGeneratorTest {
    private val generator = PlaylistGenerator()

    @Test
    fun energyMixRanksMatchingGenreFirst() {
        val calm = track("calm", "Ambient")
        val energy = track("energy", "Electronic Rock")

        val result = generator.generate(listOf(calm, energy), PlaylistMood.ENERGY)

        assertEquals("energy", result.tracks.first().contentUri)
    }

    @Test
    fun corruptedAndTrashSuggestedTracksAreNeverPlayable() {
        val corrupted = track("broken", "Rock", ArchiveStatus.CORRUPTED)
        val trashed = track("trash", "Rock", ArchiveStatus.TRASH_SUGGESTED)

        val result = generator.generate(listOf(corrupted, trashed), PlaylistMood.DISCOVERY)

        assertTrue(result.tracks.isEmpty())
    }

    private fun track(uri: String, genre: String, status: ArchiveStatus = ArchiveStatus.NEEDS_REVIEW) = TrackEntity(
        contentUri = uri,
        mediaStoreId = 1,
        displayName = "$uri.mp3",
        title = uri,
        artist = "Artist",
        albumArtist = "Artist",
        album = "Album",
        genre = genre,
        year = 2025,
        trackNumber = 1,
        discNumber = 1,
        durationMs = 180_000,
        sizeBytes = 1_000,
        mimeType = "audio/mpeg",
        bitrate = 320_000,
        hasArtwork = true,
        dateModifiedSeconds = 1,
        healthScore = 90,
        issueCodes = "",
        status = status,
    )
}
