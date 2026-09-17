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

    @Query("SELECT * FROM tracks WHERE contentUri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE contentUri IN (:uris)")
    suspend fun getByUris(uris: List<String>): List<TrackEntity>

    @Query(
        """
        SELECT * FROM tracks
        WHERE title LIKE '%' || :query || '%' COLLATE NOCASE
           OR artist LIKE '%' || :query || '%' COLLATE NOCASE
           OR album LIKE '%' || :query || '%' COLLATE NOCASE
           OR genre LIKE '%' || :query || '%' COLLATE NOCASE
           OR originalTitle LIKE '%' || :query || '%' COLLATE NOCASE
           OR originalArtist LIKE '%' || :query || '%' COLLATE NOCASE
        ORDER BY
            CASE
                WHEN title LIKE :query || '%' COLLATE NOCASE THEN 0
                WHEN artist LIKE :query || '%' COLLATE NOCASE THEN 1
                ELSE 2
            END,
            artist COLLATE NOCASE,
            album COLLATE NOCASE,
            trackNumber
        LIMIT :limit
        """
    )
    suspend fun search(query: String, limit: Int = 80): List<TrackEntity>

    @Query(
        """
        SELECT * FROM tracks
        WHERE durationMs > 0
          AND status != 'CORRUPTED'
          AND status != 'TRASH_SUGGESTED'
        ORDER BY dateModifiedSeconds DESC
        LIMIT :limit
        """
    )
    suspend fun recentlyAdded(limit: Int = 40): List<TrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tracks: List<TrackEntity>)

    @Query("DELETE FROM tracks WHERE contentUri NOT IN (:activeUris)")
    suspend fun removeMissing(activeUris: List<String>)

    @Query("DELETE FROM tracks WHERE contentUri IN (:uris)")
    suspend fun deleteUris(uris: List<String>)

    @Query("DELETE FROM tracks")
    suspend fun deleteAll()

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
