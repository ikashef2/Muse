package com.kashef.archive.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.kashef.archive.ArchiveApplication
import com.kashef.archive.data.ArchiveStatus
import com.kashef.archive.data.CollectionCount
import com.kashef.archive.data.IdentificationPhase
import com.kashef.archive.data.LibrarySummary
import com.kashef.archive.data.MetadataCandidate
import com.kashef.archive.data.MusicScanWorker
import com.kashef.archive.data.PreparedMetadataChange
import com.kashef.archive.data.ScanReport
import com.kashef.archive.data.TrackEntity
import com.kashef.archive.domain.GeneratedPlaylist
import com.kashef.archive.domain.MoodClassifier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

data class ArchiveUiState(
    val summary: LibrarySummary = LibrarySummary(),
    val mixes: List<GeneratedPlaylist> = emptyList(),
    val currentTrack: TrackEntity? = null,
    val isScanning: Boolean = false,
    val report: ScanReport? = null,
    val error: String? = null,
    val metadataSearch: MetadataSearchState = MetadataSearchState(),
    val searchQuery: String = "",
    val searchResults: List<TrackEntity> = emptyList(),
    val isSearchLoading: Boolean = false,
    val importTracks: List<TrackEntity> = emptyList(),
    val moodTeacherTrack: TrackEntity? = null,
) {
    val hasLibrary: Boolean get() = summary.playable > 0 || summary.total > 0
    val pendingReview: Int get() = summary.pendingReview
}

data class MetadataSearchState(
    val trackUri: String? = null,
    val isSearching: Boolean = false,
    val phase: IdentificationPhase? = null,
    val candidates: List<MetadataCandidate> = emptyList(),
    val error: String? = null,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class ArchiveViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as ArchiveApplication).repository
    private val scanning = MutableStateFlow(false)
    private val report = MutableStateFlow<ScanReport?>(null)
    private val error = MutableStateFlow<String?>(null)
    private val metadataSearch = MutableStateFlow(MetadataSearchState())
    private val mutablePendingMetadataChange = MutableStateFlow<PreparedMetadataChange?>(null)
    private val searchQuery = MutableStateFlow("")
    private val mixes = MutableStateFlow<List<GeneratedPlaylist>>(emptyList())
    private val importTracks = MutableStateFlow<List<TrackEntity>>(emptyList())
    private val moodTeacherTrack = MutableStateFlow<TrackEntity?>(null)
    private var metadataSearchJob: Job? = null
    private var didRestorePlayback = false

    val pendingMetadataChange: StateFlow<PreparedMetadataChange?> = mutablePendingMetadataChange
    val playback = (application as ArchiveApplication).playback

    val playablePaging: StateFlow<PagingData<TrackEntity>> = repository.observePlayablePaged()
        .cachedIn(viewModelScope)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PagingData.empty())

    private val searchResults = searchQuery
        .debounce(220)
        .distinctUntilChanged()
        .flatMapLatest { query ->
            flow {
                if (query.isBlank()) {
                    emit(emptyList<TrackEntity>() to false)
                } else {
                    emit(emptyList<TrackEntity>() to true)
                    emit(repository.searchPlayable(query) to false)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<TrackEntity>() to false)

    private val currentTrack = playback.state
        .map { it.mediaId }
        .distinctUntilChanged()
        .flatMapLatest { mediaId ->
            if (mediaId.isNullOrBlank()) flowOf(null) else repository.observeByUri(mediaId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val state: StateFlow<ArchiveUiState> = combine(
        combine(
            repository.observeLibrarySummary(),
            mixes,
            currentTrack,
            scanning,
            report,
        ) { summary, mixList, track, isScanning, latestReport ->
            Quint(summary, mixList, track, isScanning, latestReport)
        },
        combine(
            error,
            metadataSearch,
            searchQuery,
            searchResults,
            importTracks,
        ) { latestError, search, query, resultsPair, imports ->
            Quint(latestError, search, query, resultsPair, imports)
        },
        moodTeacherTrack,
    ) { left, right, teacher ->
        ArchiveUiState(
            summary = left.a,
            mixes = left.b,
            currentTrack = left.c,
            isScanning = left.d,
            report = left.e,
            error = right.a,
            metadataSearch = right.b,
            searchQuery = right.c,
            searchResults = right.d.first,
            isSearchLoading = right.d.second,
            importTracks = right.e,
            moodTeacherTrack = teacher ?: left.c,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ArchiveUiState())

    init {
        refreshMixes()
        viewModelScope.launch {
            repository.observeLibrarySummary().collect { summary ->
                if (!didRestorePlayback && summary.total > 0) {
                    didRestorePlayback = true
                    val ids = playback.savedMediaIds()
                    if (ids.isNotEmpty()) {
                        playback.restoreSavedQueue(repository.getByUris(ids).associateBy(TrackEntity::contentUri))
                    }
                }
                if (summary.total > 0) {
                    refreshMixes()
                    moodTeacherTrack.value = repository.getUntaggedSample() ?: currentTrack.value
                }
            }
        }
    }

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun refreshMixes() = viewModelScope.launch {
        runCatching { repository.moodMixes() }
            .onSuccess { mixes.value = it }
    }

    fun loadImportPage() = viewModelScope.launch {
        importTracks.value = repository.getImportPage(limit = 80, offset = 0)
    }

    suspend fun loadArtistPage(offset: Int, limit: Int = 40): List<CollectionCount> =
        repository.getArtistPage(limit, offset)

    suspend fun loadAlbumPage(offset: Int, limit: Int = 40): List<CollectionCount> =
        repository.getAlbumPage(limit, offset)

    suspend fun loadGenrePage(offset: Int, limit: Int = 40): List<CollectionCount> =
        repository.getGenrePage(limit, offset)

    suspend fun openArtist(name: String): List<TrackEntity> = repository.getTracksForArtistName(name)
    suspend fun openAlbum(name: String): List<TrackEntity> = repository.getTracksForAlbumName(name)
    suspend fun openGenre(name: String): List<TrackEntity> = repository.getTracksForGenreName(name)

    fun scan() {
        if (scanning.value) return
        viewModelScope.launch {
            scanning.value = true
            error.value = null
            runCatching { repository.scanDevice() }
                .onSuccess {
                    report.value = it
                    scheduleLibraryWatch()
                    refreshMixes()
                    loadImportPage()
                }
                .onFailure { error.value = it.message ?: "The scan could not finish." }
            scanning.value = false
        }
    }

    fun save(original: TrackEntity, updated: TrackEntity) = stageMetadataChange {
        repository.prepareMetadataEdit(original, updated)
    }

    fun verify(track: TrackEntity) = viewModelScope.launch {
        repository.verify(track.contentUri)
        loadImportPage()
    }

    fun suggestTrash(track: TrackEntity) = viewModelScope.launch {
        repository.suggestTrash(track.contentUri)
        loadImportPage()
    }

    fun toggleMood(track: TrackEntity, mood: String) = viewModelScope.launch {
        repository.setManualMoodTags(track.contentUri, MoodClassifier.toggle(track, mood))
        refreshMixes()
    }

    fun playTrack(track: TrackEntity) {
        if (!track.isPlayable()) return
        viewModelScope.launch {
            val albumQueue = if (track.album.isNotBlank()) {
                repository.getAlbumTracks(track.artist, track.album)
            } else {
                emptyList()
            }
            val queue = if (albumQueue.size > 1) albumQueue else listOf(track)
            val label = if (albumQueue.size > 1) {
                "Album · ${track.album}"
            } else {
                "Song · ${track.title.ifBlank { track.displayName }}"
            }
            playback.playQueue(
                queue,
                queue.indexOfFirst { it.contentUri == track.contentUri }.coerceAtLeast(0),
                label,
            )
        }
    }

    fun playQueue(tracks: List<TrackEntity>, startIndex: Int = 0, contextLabel: String = "") {
        val requested = tracks.getOrNull(startIndex)
        val playable = tracks.filter(TrackEntity::isPlayable)
        val playableIndex = requested
            ?.let { target -> playable.indexOfFirst { it.contentUri == target.contentUri } }
            ?.takeIf { it >= 0 }
            ?: 0
        val label = contextLabel.ifBlank {
            when {
                playable.size <= 1 -> "Song"
                playable.map { it.album }.distinct().size == 1 && playable.first().album.isNotBlank() ->
                    "Album · ${playable.first().album}"
                playable.map { it.artist }.distinct().size == 1 && playable.first().artist.isNotBlank() ->
                    "Artist · ${playable.first().artist}"
                else -> "Queue · ${playable.size} tracks"
            }
        }
        playback.playQueue(playable, playableIndex, label)
    }

    fun playMix(mix: GeneratedPlaylist) {
        playQueue(mix.tracks, 0, "Mix · ${mix.mood.title}")
    }

    fun searchMetadata(track: TrackEntity) {
        if (metadataSearch.value.isSearching) return
        metadataSearchJob = viewModelScope.launch {
            metadataSearch.value = MetadataSearchState(
                trackUri = track.contentUri,
                isSearching = true,
                phase = IdentificationPhase.READING_TAGS,
            )
            runCatching {
                repository.searchMetadata(track) { phase ->
                    metadataSearch.value = metadataSearch.value.copy(phase = phase)
                }
            }
                .onSuccess { candidates ->
                    metadataSearch.value = MetadataSearchState(
                        trackUri = track.contentUri,
                        candidates = candidates,
                        error = if (candidates.isEmpty()) "No credible MusicBrainz match found." else null,
                    )
                }
                .onFailure {
                    if (it !is CancellationException) {
                        metadataSearch.value = MetadataSearchState(
                            trackUri = track.contentUri,
                            error = it.message ?: "Metadata search failed.",
                        )
                    }
                }
        }
    }

    fun cancelMetadataSearch() {
        metadataSearchJob?.cancel()
        metadataSearchJob = null
        metadataSearch.value = MetadataSearchState()
    }

    fun applyMetadata(track: TrackEntity, candidate: MetadataCandidate) = viewModelScope.launch {
        metadataSearchJob = null
        metadataSearch.value = MetadataSearchState()
        runCatching { repository.prepareMetadataCandidate(track, candidate) }
            .onSuccess { mutablePendingMetadataChange.value = it }
            .onFailure { error.value = it.message ?: "Muse could not prepare the tag rewrite." }
    }

    fun commitPendingMetadataChange() {
        val pending = mutablePendingMetadataChange.value ?: return
        mutablePendingMetadataChange.value = null
        viewModelScope.launch {
            runCatching { repository.commitMetadataChange(pending) }
                .onSuccess {
                    scan()
                }
                .onFailure { error.value = it.message ?: "The tag rewrite failed." }
        }
    }

    fun cancelPendingMetadataChange() {
        mutablePendingMetadataChange.value?.let(repository::cancelMetadataChange)
        mutablePendingMetadataChange.value = null
    }

    fun clearMetadataSearch() {
        metadataSearchJob?.cancel()
        metadataSearchJob = null
        metadataSearch.value = MetadataSearchState()
    }

    private fun stageMetadataChange(block: suspend () -> PreparedMetadataChange) {
        viewModelScope.launch {
            error.value = null
            runCatching { block() }
                .onSuccess { mutablePendingMetadataChange.value = it }
                .onFailure { error.value = it.message ?: "Muse could not prepare the metadata change." }
        }
    }

    private fun scheduleLibraryWatch() {
        val request = PeriodicWorkRequestBuilder<MusicScanWorker>(6, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build()
            )
            .build()
        WorkManager.getInstance(getApplication())
            .enqueueUniquePeriodicWork("archive-library-watch", ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

internal fun TrackEntity.isPlayable(): Boolean =
    durationMs > 0 && status != ArchiveStatus.CORRUPTED && status != ArchiveStatus.TRASH_SUGGESTED

private data class Quint<A, B, C, D, E>(
    val a: A,
    val b: B,
    val c: C,
    val d: D,
    val e: E,
)
