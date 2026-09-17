package com.kashef.archive.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.kashef.archive.domain.MoodClassifier

enum class ArchiveStatus {
    VERIFIED,
    NEEDS_REVIEW,
    UNIDENTIFIED,
    DUPLICATE,
    LOW_QUALITY,
    CORRUPTED,
    IGNORED,
    TRASH_SUGGESTED,
}

@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["artist"]),
        Index(value = ["album"]),
        Index(value = ["status"]),
        Index(value = ["title"]),
    ],
)
data class TrackEntity(
    @PrimaryKey val contentUri: String,
    val mediaStoreId: Long,
    val displayName: String,
    val title: String,
    val artist: String,
    val albumArtist: String,
    val album: String,
    val genre: String,
    val year: Int?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val durationMs: Long,
    val sizeBytes: Long,
    val mimeType: String,
    val bitrate: Int?,
    val hasArtwork: Boolean,
    val dateModifiedSeconds: Long,
    val healthScore: Int,
    val issueCodes: String,
    val status: ArchiveStatus,
    val isUserEdited: Boolean = false,
    val verifiedAtMillis: Long? = null,
    val originalTitle: String = "",
    val originalArtist: String = "",
    val originalAlbumArtist: String = "",
    val originalAlbum: String = "",
    val inferredMoodTags: String = "",
    val manualMoodTags: String = "",
    val fingerprint: String = "",
    val acoustId: String = "",
    val musicBrainzRecordingId: String = "",
    val matchConfidence: Int? = null,
    val matchSource: String = "",
) {
    val issues: List<String>
        get() = issueCodes.split('|').filter(String::isNotBlank)

    val moods: Set<String>
        get() = MoodClassifier.effective(this)
}
