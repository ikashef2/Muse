package com.kashef.archive

import android.app.Application
import androidx.room.Room
import com.kashef.archive.data.ArchiveDatabase
import com.kashef.archive.data.AcoustIdClient
import com.kashef.archive.data.AppleCatalogClient
import com.kashef.archive.data.ChromaprintEngine
import com.kashef.archive.data.MIGRATION_2_3
import com.kashef.archive.data.MusicRepository
import com.kashef.archive.data.MusicBrainzClient
import com.kashef.archive.data.MetadataTagWriter
import com.kashef.archive.domain.MetadataQualityEvaluator
import com.kashef.archive.playback.PlaybackConnection

class ArchiveApplication : Application() {
    val playback: PlaybackConnection by lazy { PlaybackConnection(this) }

    val database: ArchiveDatabase by lazy {
        Room.databaseBuilder(this, ArchiveDatabase::class.java, "archive.db")
            .addMigrations(MIGRATION_2_3)
            // Only wipe truly ancient v1 installs; never destroy v2→v3+ upgrades.
            .fallbackToDestructiveMigrationFrom(1)
            .build()
    }

    val repository: MusicRepository by lazy {
        MusicRepository(
            context = this,
            dao = database.trackDao(),
            evaluator = MetadataQualityEvaluator(),
            musicBrainz = MusicBrainzClient(),
            appleCatalog = AppleCatalogClient(),
            acoustId = AcoustIdClient(BuildConfig.ACOUSTID_CLIENT_KEY),
            fingerprintEngine = ChromaprintEngine(this),
            tagWriter = MetadataTagWriter(this),
        )
    }
}
