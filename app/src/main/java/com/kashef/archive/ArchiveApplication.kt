package com.kashef.archive

import android.app.Application
import androidx.room.Room
import com.kashef.archive.data.ArchiveDatabase
import com.kashef.archive.data.AcoustIdClient
import com.kashef.archive.data.AppleCatalogClient
import com.kashef.archive.data.ChromaprintEngine
import com.kashef.archive.data.ListeningEventEntity
import com.kashef.archive.data.MusicRepository
import com.kashef.archive.data.MusicBrainzClient
import com.kashef.archive.data.MetadataTagWriter
import com.kashef.archive.domain.MetadataQualityEvaluator
import com.kashef.archive.playback.PlaybackConnection
import com.kashef.archive.playback.PlaybackSessionStore
import com.kashef.archive.source.LocalMusicSource
import com.kashef.archive.source.MusicSourceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ArchiveApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: ArchiveDatabase by lazy {
        Room.databaseBuilder(this, ArchiveDatabase::class.java, "archive.db")
            .addMigrations(ArchiveDatabase.MIGRATION_2_3)
            .fallbackToDestructiveMigration()
            .build()
    }

    val repository: MusicRepository by lazy {
        MusicRepository(
            context = this,
            dao = database.trackDao(),
            listeningDao = database.listeningEventDao(),
            evaluator = MetadataQualityEvaluator(),
            musicBrainz = MusicBrainzClient(),
            appleCatalog = AppleCatalogClient(),
            acoustId = AcoustIdClient(BuildConfig.ACOUSTID_CLIENT_KEY),
            fingerprintEngine = ChromaprintEngine(this),
            tagWriter = MetadataTagWriter(this),
        )
    }

    val sources: MusicSourceRegistry by lazy {
        MusicSourceRegistry(
            LocalMusicSource(
                searchTracks = { query, limit -> repository.search(query, limit) },
                resolveTrack = { uri -> repository.getTrack(uri) },
            ),
        )
    }

    val playback: PlaybackConnection by lazy {
        PlaybackConnection(
            context = this,
            sessionStore = PlaybackSessionStore(this),
            onListeningEvent = { snapshot ->
                appScope.launch {
                    repository.recordListening(
                        ListeningEventEntity(
                            trackUri = snapshot.trackUri,
                            startedAtMillis = System.currentTimeMillis() - snapshot.listenedMs,
                            listenedMs = snapshot.listenedMs,
                            trackDurationMs = snapshot.trackDurationMs,
                            skipped = snapshot.skipped,
                            completed = snapshot.completed,
                            context = snapshot.context,
                            sourceId = "local",
                        ),
                    )
                }
            },
        ).also { connection ->
            connection.setTrackResolver { uris -> repository.getTracks(uris) }
        }
    }
}
