package com.kashef.archive.data

data class MetadataCandidate(
    val recordingId: String,
    val title: String,
    val artist: String,
    val albumArtist: String,
    val album: String,
    val genre: String,
    val year: Int?,
    val confidence: Int,
    val durationDifferenceMs: Long?,
)
