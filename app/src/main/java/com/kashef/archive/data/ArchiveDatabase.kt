package com.kashef.archive.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class ArchiveConverters {
    @TypeConverter fun statusToString(value: ArchiveStatus): String = value.name
    @TypeConverter fun stringToStatus(value: String): ArchiveStatus = ArchiveStatus.valueOf(value)
}

@Database(
    entities = [TrackEntity::class, ListeningEventEntity::class],
    version = 3,
    exportSchema = false,
)
@TypeConverters(ArchiveConverters::class)
abstract class ArchiveDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun listeningEventDao(): ListeningEventDao

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS listening_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        trackUri TEXT NOT NULL,
                        startedAtMillis INTEGER NOT NULL,
                        listenedMs INTEGER NOT NULL,
                        trackDurationMs INTEGER NOT NULL,
                        skipped INTEGER NOT NULL,
                        completed INTEGER NOT NULL,
                        context TEXT NOT NULL,
                        sourceId TEXT NOT NULL,
                        playlistId TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_listening_events_trackUri ON listening_events(trackUri)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_listening_events_startedAtMillis ON listening_events(startedAtMillis)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_artist ON tracks(artist)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_album ON tracks(album)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_title ON tracks(title)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_status ON tracks(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_dateModifiedSeconds ON tracks(dateModifiedSeconds)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_genre ON tracks(genre)")
            }
        }
    }
}
