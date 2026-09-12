package com.kashef.archive.domain

enum class MetadataIssue(val penalty: Int) {
    MISSING_TITLE(30),
    UNKNOWN_ARTIST(25),
    MISSING_ALBUM(12),
    MISSING_ALBUM_ARTIST(5),
    MISSING_TRACK_NUMBER(4),
    MISSING_YEAR(5),
    INVALID_YEAR(8),
    MISSING_GENRE(3),
    MISSING_ARTWORK(8),
    DIRTY_FILENAME(4),
    INVALID_DURATION(50),
    SUSPICIOUSLY_SHORT(15),
    LOW_BITRATE(10),
}

data class MetadataSnapshot(
    val displayName: String,
    val title: String?,
    val artist: String?,
    val albumArtist: String?,
    val album: String?,
    val genre: String?,
    val year: Int?,
    val trackNumber: Int?,
    val durationMs: Long,
    val bitrate: Int?,
    val hasArtwork: Boolean,
)

data class QualityResult(
    val score: Int,
    val issues: List<MetadataIssue>,
)

class MetadataQualityEvaluator {
    private val unknownValues = setOf("<unknown>", "unknown", "unknown artist", "various", "")
    private val dirtyFilenameTokens = listOf(
        "official audio", "official video", "lyrics", "320kbps", "youtube", "ytmp3", "download"
    )

    fun evaluate(metadata: MetadataSnapshot): QualityResult {
        val issues = buildList {
            if (metadata.title.isNullOrBlank()) add(MetadataIssue.MISSING_TITLE)
            if (metadata.artist.normalized() in unknownValues) add(MetadataIssue.UNKNOWN_ARTIST)
            if (metadata.album.isNullOrBlank()) add(MetadataIssue.MISSING_ALBUM)
            if (metadata.albumArtist.isNullOrBlank()) add(MetadataIssue.MISSING_ALBUM_ARTIST)
            if (metadata.trackNumber == null || metadata.trackNumber <= 0) add(MetadataIssue.MISSING_TRACK_NUMBER)
            if (metadata.year == null) add(MetadataIssue.MISSING_YEAR)
            else if (metadata.year !in 1888..2100) add(MetadataIssue.INVALID_YEAR)
            if (metadata.genre.isNullOrBlank()) add(MetadataIssue.MISSING_GENRE)
            if (!metadata.hasArtwork) add(MetadataIssue.MISSING_ARTWORK)
            if (dirtyFilenameTokens.any { metadata.displayName.contains(it, ignoreCase = true) }) {
                add(MetadataIssue.DIRTY_FILENAME)
            }
            if (metadata.durationMs <= 0) add(MetadataIssue.INVALID_DURATION)
            else if (metadata.durationMs < 30_000) add(MetadataIssue.SUSPICIOUSLY_SHORT)
            if (metadata.bitrate != null && metadata.bitrate in 1 until 128_000) {
                add(MetadataIssue.LOW_BITRATE)
            }
        }
        return QualityResult(
            score = (100 - issues.sumOf(MetadataIssue::penalty)).coerceIn(0, 100),
            issues = issues,
        )
    }

    private fun String?.normalized(): String = this?.trim()?.lowercase().orEmpty()
}
