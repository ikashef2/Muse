package com.kashef.archive.data

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class LibrarySummary(
    val total: Int = 0,
    val playable: Int = 0,
    val verified: Int = 0,
    val pendingReview: Int = 0,
)

data class CollectionCount(
    val name: String,
    val trackCount: Int,
)

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY artist COLLATE NOCASE, album COLLATE NOCASE, trackNumber")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks")
    suspend fun getAllOnce(): List<TrackEntity>

    @Query(
        """
        SELECT
            COUNT(*) AS total,
            COALESCE(SUM(CASE WHEN durationMs > 0 AND status NOT IN ('CORRUPTED', 'TRASH_SUGGESTED') THEN 1 ELSE 0 END), 0) AS playable,
            COALESCE(SUM(CASE WHEN status = 'VERIFIED' THEN 1 ELSE 0 END), 0) AS verified,
            COALESCE(SUM(CASE WHEN status IN ('NEEDS_REVIEW', 'UNIDENTIFIED', 'LOW_QUALITY') THEN 1 ELSE 0 END), 0) AS pendingReview
        FROM tracks
        """
    )
    fun observeLibrarySummary(): Flow<LibrarySummary>

    @Query(
        """
        SELECT * FROM tracks
        WHERE durationMs > 0
          AND status NOT IN ('CORRUPTED', 'TRASH_SUGGESTED')
        ORDER BY title COLLATE NOCASE
        """
    )
    fun observePlayablePaged(): PagingSource<Int, TrackEntity>

    @Query(
        """
        SELECT * FROM tracks
        WHERE durationMs > 0
          AND status NOT IN ('CORRUPTED', 'TRASH_SUGGESTED')
          AND (
            title LIKE '%' || :query || '%' COLLATE NOCASE
            OR artist LIKE '%' || :query || '%' COLLATE NOCASE
            OR album LIKE '%' || :query || '%' COLLATE NOCASE
            OR genre LIKE '%' || :query || '%' COLLATE NOCASE
            OR originalTitle LIKE '%' || :query || '%' COLLATE NOCASE
            OR originalArtist LIKE '%' || :query || '%' COLLATE NOCASE
            OR displayName LIKE '%' || :query || '%' COLLATE NOCASE
          )
        ORDER BY
          CASE WHEN title LIKE :query || '%' COLLATE NOCASE THEN 0
               WHEN artist LIKE :query || '%' COLLATE NOCASE THEN 1
               ELSE 2 END,
          title COLLATE NOCASE
        LIMIT :limit
        """
    )
    suspend fun searchPlayable(query: String, limit: Int = 80): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE contentUri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE contentUri = :uri LIMIT 1")
    fun observeByUri(uri: String): Flow<TrackEntity?>

    @Query("SELECT * FROM tracks WHERE contentUri IN (:uris)")
    suspend fun getByUris(uris: List<String>): List<TrackEntity>

    @Query(
        """
        SELECT * FROM tracks
        WHERE durationMs > 0
          AND status NOT IN ('CORRUPTED', 'TRASH_SUGGESTED')
          AND album = :album
          AND artist = :artist
        ORDER BY discNumber, trackNumber, title COLLATE NOCASE
        """
    )
    suspend fun getAlbumTracks(artist: String, album: String): List<TrackEntity>

    @Query(
        """
        SELECT * FROM tracks
        WHERE durationMs > 0
          AND status NOT IN ('CORRUPTED', 'TRASH_SUGGESTED')
          AND artist = :artist
        ORDER BY album COLLATE NOCASE, discNumber, trackNumber, title COLLATE NOCASE
        LIMIT :limit
        """
    )
    suspend fun getArtistTracks(artist: String, limit: Int = 200): List<TrackEntity>

    @Query(
        """
        SELECT * FROM tracks
        WHERE durationMs > 0
          AND status NOT IN ('CORRUPTED', 'TRASH_SUGGESTED')
          AND (
            (inferredMoodTags LIKE '%' || :mood || '%' OR manualMoodTags LIKE '%' || :mood || '%')
            AND manualMoodTags NOT LIKE '%!' || :mood || '%'
          )
        ORDER BY healthScore DESC, title COLLATE NOCASE
        LIMIT :limit
        """
    )
    suspend fun getMoodTracks(mood: String, limit: Int = 40): List<TrackEntity>

    @Query(
        """
        SELECT CASE WHEN artist = '' THEN 'Unknown artist' ELSE artist END AS name,
               COUNT(*) AS trackCount
        FROM tracks
        WHERE durationMs > 0 AND status NOT IN ('CORRUPTED', 'TRASH_SUGGESTED')
        GROUP BY name
        ORDER BY name COLLATE NOCASE
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getArtistPage(limit: Int, offset: Int): List<CollectionCount>

    @Query(
        """
        SELECT CASE WHEN album = '' THEN 'Unknown album' ELSE album END AS name,
               COUNT(*) AS trackCount
        FROM tracks
        WHERE durationMs > 0 AND status NOT IN ('CORRUPTED', 'TRASH_SUGGESTED')
        GROUP BY name
        ORDER BY name COLLATE NOCASE
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getAlbumPage(limit: Int, offset: Int): List<CollectionCount>

    @Query(
        """
        SELECT CASE WHEN genre = '' THEN 'Unclassified' ELSE genre END AS name,
               COUNT(*) AS trackCount
        FROM tracks
        WHERE durationMs > 0 AND status NOT IN ('CORRUPTED', 'TRASH_SUGGESTED')
        GROUP BY name
        ORDER BY name COLLATE NOCASE
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getGenrePage(limit: Int, offset: Int): List<CollectionCount>

    @Query(
        """
        SELECT * FROM tracks
        WHERE durationMs > 0
          AND status NOT IN ('CORRUPTED', 'TRASH_SUGGESTED')
          AND CASE WHEN album = '' THEN 'Unknown album' ELSE album END = :name
        ORDER BY artist COLLATE NOCASE, discNumber, trackNumber, title COLLATE NOCASE
        LIMIT :limit
        """
    )
    suspend fun getTracksForAlbumName(name: String, limit: Int = 200): List<TrackEntity>

    @Query(
        """
        SELECT * FROM tracks
        WHERE durationMs > 0
          AND status NOT IN ('CORRUPTED', 'TRASH_SUGGESTED')
          AND CASE WHEN artist = '' THEN 'Unknown artist' ELSE artist END = :name
        ORDER BY album COLLATE NOCASE, discNumber, trackNumber, title COLLATE NOCASE
        LIMIT :limit
        """
    )
    suspend fun getTracksForArtistName(name: String, limit: Int = 200): List<TrackEntity>

    @Query(
        """
        SELECT * FROM tracks
        WHERE durationMs > 0
          AND status NOT IN ('CORRUPTED', 'TRASH_SUGGESTED')
          AND CASE WHEN genre = '' THEN 'Unclassified' ELSE genre END = :name
        ORDER BY artist COLLATE NOCASE, title COLLATE NOCASE
        LIMIT :limit
        """
    )
    suspend fun getTracksForGenreName(name: String, limit: Int = 200): List<TrackEntity>

    @Query(
        """
        SELECT * FROM tracks
        WHERE status NOT IN ('VERIFIED', 'CORRUPTED', 'TRASH_SUGGESTED')
        ORDER BY healthScore ASC, title COLLATE NOCASE
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getImportPage(limit: Int, offset: Int): List<TrackEntity>

    @Query(
        """
        SELECT * FROM tracks
        WHERE durationMs > 0
          AND status NOT IN ('CORRUPTED', 'TRASH_SUGGESTED')
          AND (manualMoodTags = '' OR manualMoodTags IS NULL)
          AND (inferredMoodTags = '' OR inferredMoodTags IS NULL)
        ORDER BY dateModifiedSeconds DESC
        LIMIT 1
        """
    )
    suspend fun getUntaggedSample(): TrackEntity?

    @Query(
        """
        SELECT * FROM tracks
        WHERE durationMs > 0
          AND status NOT IN ('CORRUPTED', 'TRASH_SUGGESTED')
        ORDER BY healthScore DESC, dateModifiedSeconds DESC
        LIMIT :limit
        """
    )
    suspend fun getDiscoveryTracks(limit: Int = 40): List<TrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tracks: List<TrackEntity>)

    @Query("DELETE FROM tracks WHERE contentUri NOT IN (:activeUris)")
    suspend fun removeMissing(activeUris: List<String>)

    @Query("DELETE FROM tracks WHERE contentUri IN (:uris)")
    suspend fun deleteUris(uris: List<String>)

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
