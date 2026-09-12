package com.kashef.archive

import android.app.Application
import androidx.room.Room
import com.kashef.archive.data.ArchiveDatabase
import com.kashef.archive.data.MusicRepository
import com.kashef.archive.domain.MetadataQualityEvaluator

class ArchiveApplication : Application() {
    val database: ArchiveDatabase by lazy {
        Room.databaseBuilder(this, ArchiveDatabase::class.java, "archive.db")
            .fallbackToDestructiveMigration()
            .build()
    }

    val repository: MusicRepository by lazy {
        MusicRepository(
            context = this,
            dao = database.trackDao(),
            evaluator = MetadataQualityEvaluator(),
        )
    }
}
