package com.kashef.archive.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class ArchiveConverters {
    @TypeConverter fun statusToString(value: ArchiveStatus): String = value.name
    @TypeConverter fun stringToStatus(value: String): ArchiveStatus = ArchiveStatus.valueOf(value)
}

@Database(entities = [TrackEntity::class], version = 2, exportSchema = false)
@TypeConverters(ArchiveConverters::class)
abstract class ArchiveDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
}
