package com.kashef.archive.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class ArchiveConverters {
    @TypeConverter fun statusToString(value: ArchiveStatus): String = value.name
    @TypeConverter fun stringToStatus(value: String): ArchiveStatus =
        runCatching { ArchiveStatus.valueOf(value) }.getOrDefault(ArchiveStatus.NEEDS_REVIEW)
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_artist` ON `tracks` (`artist`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_album` ON `tracks` (`album`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_status` ON `tracks` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tracks_title` ON `tracks` (`title`)")
    }
}

@Database(entities = [TrackEntity::class], version = 3, exportSchema = false)
@TypeConverters(ArchiveConverters::class)
abstract class ArchiveDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
}
