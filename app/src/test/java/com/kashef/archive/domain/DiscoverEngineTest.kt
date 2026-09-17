package com.kashef.archive.domain

import com.kashef.archive.data.ArchiveStatus
import com.kashef.archive.data.TrackEntity
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverEngineTest {
    @Test
    fun rankPrefersFamiliarArtistsAndSpecificMetalGenres() {
        val tracks = listOf(
            track("1", "Opeth", "Ghost of Perdition", "Progressive Death Metal"),
            track("2", "Random Band", "Filler", "Pop"),
            track("3", "Ne Obliviscaris", "Devour Me", "Progressive Death Metal"),
        )
        val ranked = DiscoverEngine().rank(tracks, topArtistNames = listOf("Opeth"))
        assertTrue(ranked.first().track.artist == "Opeth")
        assertTrue(ranked.first().reason.contains("Taste Match"))
        assertTrue(ranked.any { it.reason.contains("Progressive Death Metal") })
    }

    @Test
    fun buildOmitsEmptyLibrary() {
        assertTrue(DiscoverEngine().build(emptyList()).isEmpty())
    }

    private fun track(id: String, artist: String, title: String, genre: String) = TrackEntity(
        contentUri = id,
        mediaStoreId = id.hashCode().toLong(),
        displayName = "$title.mp3",
        title = title,
        artist = artist,
        albumArtist = artist,
        album = "Album",
        genre = genre,
        year = 2005,
        trackNumber = 1,
        discNumber = 1,
        durationMs = 200_000,
        sizeBytes = 1_000,
        mimeType = "audio/mpeg",
        bitrate = 320_000,
        hasArtwork = true,
        dateModifiedSeconds = 1,
        healthScore = 92,
        issueCodes = "",
        status = ArchiveStatus.VERIFIED,
    )
}
