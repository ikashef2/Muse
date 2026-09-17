package com.kashef.archive.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One listening interaction used by taste modeling and analytics.
 * Kept lean: only fields that improve recommendations or history UI.
 */
@Entity(
    tableName = "listening_events",
    indices = [
        Index(value = ["trackUri"]),
        Index(value = ["startedAtMillis"]),
    ],
)
data class ListeningEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackUri: String,
    val startedAtMillis: Long,
    val listenedMs: Long,
    val trackDurationMs: Long,
    val skipped: Boolean,
    val completed: Boolean,
    val context: String = "",
    val sourceId: String = "local",
    val playlistId: String = "",
)
