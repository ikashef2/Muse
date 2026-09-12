package com.kashef.archive.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY artist COLLATE NOCASE, album COLLATE NOCASE, trackNumber")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks")
    suspend fun getAllOnce(): List<TrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tracks: List<TrackEntity>)

    @Query("DELETE FROM tracks WHERE contentUri NOT IN (:activeUris)")
    suspend fun removeMissing(activeUris: List<String>)

    @Query(
        """
        UPDATE tracks SET
            title = :title,
            artist = :artist,
            albumArtist = :albumArtist,
            album = :album,
            genre = :genre,
            year = :year,
            trackNumber = :trackNumber,
            discNumber = :discNumber,
            healthScore = :healthScore,
            issueCodes = :issueCodes,
            isUserEdited = 1,
            status = 'NEEDS_REVIEW'
        WHERE contentUri = :uri
        """
    )
    suspend fun updateCanonicalMetadata(
        uri: String,
        title: String,
        artist: String,
        albumArtist: String,
        album: String,
        genre: String,
        year: Int?,
        trackNumber: Int?,
        discNumber: Int?,
        healthScore: Int,
        issueCodes: String,
    )

    @Query("UPDATE tracks SET status = 'VERIFIED', verifiedAtMillis = :timestamp WHERE contentUri = :uri")
    suspend fun verify(uri: String, timestamp: Long)

    @Query("UPDATE tracks SET status = 'TRASH_SUGGESTED' WHERE contentUri = :uri")
    suspend fun suggestTrash(uri: String)

    @Query("UPDATE tracks SET manualMoodTags = :tags WHERE contentUri = :uri")
    suspend fun updateManualMoodTags(uri: String, tags: String)

    @Query(
        """
        UPDATE tracks SET
            fingerprint = :fingerprint,
            acoustId = :acoustId,
            musicBrainzRecordingId = :recordingId,
            matchConfidence = :confidence,
            matchSource = :source
        WHERE contentUri = :uri
        """
    )
    suspend fun updateIdentification(
        uri: String,
        fingerprint: String,
        acoustId: String,
        recordingId: String,
        confidence: Int?,
        source: String,
    )
}
