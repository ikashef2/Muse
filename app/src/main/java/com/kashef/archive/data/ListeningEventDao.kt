package com.kashef.archive.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ListeningEventDao {
    @Insert
    suspend fun insert(event: ListeningEventEntity): Long

    @Query("SELECT * FROM listening_events ORDER BY startedAtMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<ListeningEventEntity>>

    @Query("SELECT * FROM listening_events ORDER BY startedAtMillis DESC LIMIT :limit")
    suspend fun recent(limit: Int = 100): List<ListeningEventEntity>

    @Query(
        """
        SELECT trackUri, COUNT(*) as playCount, SUM(listenedMs) as totalListened
        FROM listening_events
        WHERE startedAtMillis >= :sinceMillis
        GROUP BY trackUri
        ORDER BY playCount DESC, totalListened DESC
        LIMIT :limit
        """
    )
    suspend fun topTracksSince(sinceMillis: Long, limit: Int = 20): List<TrackPlayAggregate>

    @Query("DELETE FROM listening_events WHERE startedAtMillis < :beforeMillis")
    suspend fun pruneBefore(beforeMillis: Long)
}

data class TrackPlayAggregate(
    val trackUri: String,
    val playCount: Int,
    val totalListened: Long,
)
