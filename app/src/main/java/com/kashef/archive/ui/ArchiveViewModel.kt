package com.kashef.archive.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.kashef.archive.ArchiveApplication
import com.kashef.archive.data.ArchiveStatus
import com.kashef.archive.data.MusicScanWorker
import com.kashef.archive.data.MetadataCandidate
import com.kashef.archive.data.IdentificationPhase
import com.kashef.archive.data.ScanReport
import com.kashef.archive.data.TrackEntity
import com.kashef.archive.data.PreparedMetadataChange
import com.kashef.archive.domain.DiscoverEngine
import com.kashef.archive.domain.DiscoverSection
import com.kashef.archive.domain.HomeFeedBuilder
import com.kashef.archive.domain.HomeSection
import com.kashef.archive.domain.MoodClassifier
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

data class ArchiveUiState(
    val tracks: List<TrackEntity> = emptyList(),
    val isScanning: Boolean = false,
    val report: ScanReport? = null,
    val error: String? = null,
    val metadataSearch: MetadataSearchState = MetadataSearchState(),
    val homeSections: List<HomeSection> = emptyList(),
    val discoverSections: List<DiscoverSection> = emptyList(),
    val searchResults: List<TrackEntity> = emptyList(),
    val searchQuery: String = "",
    val isSearching: Boolean = false,
) {
    val verifiedCount get() = tracks.count { it.status == ArchiveStatus.VERIFIED }
    val reviewCount get() = tracks.count { it.status == ArchiveStatus.NEEDS_REVIEW || it.status == ArchiveStatus.UNIDENTIFIED }
    val duplicateCount get() = tracks.count { it.status == ArchiveStatus.DUPLICATE }
    val averageHealth get() = if (tracks.isEmpty()) 0 else tracks.sumOf { it.healthScore } / tracks.size
}

data class MetadataSearchState(
    val trackUri: String? = null,
    val isSearching: Boolean = false,
    val phase: IdentificationPhase? = null,
    val candidates: List<MetadataCandidate> = emptyList(),
    val error: String? = null,
)

class ArchiveViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ArchiveApplication
    private val repository = app.repository
    private val scanning = MutableStateFlow(false)
    private val report = MutableStateFlow<ScanReport?>(null)
    private val error = MutableStateFlow<String?>(null)
    private val metadataSearch = MutableStateFlow(MetadataSearchState())
    private val mutablePendingMetadataChange = MutableStateFlow<PreparedMetadataChange?>(null)
    private val homeSections = MutableStateFlow<List<HomeSection>>(emptyList())
    private val discoverSections = MutableStateFlow<List<DiscoverSection>>(emptyList())
    private val searchQuery = MutableStateFlow("")
    private val searchResults = MutableStateFlow<List<TrackEntity>>(emptyList())
    private val isSearching = MutableStateFlow(false)
    private var metadataSearchJob: Job? = null
    private val homeFeedBuilder = HomeFeedBuilder()
    private val discoverEngine = DiscoverEngine()

    val pendingMetadataChange: StateFlow<PreparedMetadataChange?> = mutablePendingMetadataChange
    val playback = app.playback

    val state: StateFlow<ArchiveUiState> = combine(
        combine(repository.observeTracks(), scanning, report, error, metadataSearch) { tracks, isScanning, latestReport, latestError, search ->
            Quint(tracks, isScanning, latestReport, latestError, search)
        },
        combine(homeSections, discoverSections, searchQuery, searchResults, isSearching) { home, discover, query, results, searching ->
            Quint(home, discover, query, results, searching)
        },
    ) { core, feeds ->
        ArchiveUiState(
            tracks = core.a,
            isScanning = core.b,
            report = core.c,
            error = core.d,
            metadataSearch = core.e,
            homeSections = feeds.a,
            discoverSections = feeds.b,
            searchQuery = feeds.c,
            searchResults = feeds.d,
            isSearching = feeds.e,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ArchiveUiState())

    init {
        viewModelScope.launch {
            combine(
                repository.observeTracks(),
                repository.observeRecentListening(80),
                playback.state.map { it.mediaId },
            ) { tracks, events, mediaId -> Triple(tracks, events, mediaId) }
                .collect { (tracks, events, mediaId) ->
                    val since = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
                    val heavy = repository.heavyRotation(since, 20)
                    val continueTrack = mediaId?.let { uri -> tracks.firstOrNull { it.contentUri == uri } }
                    homeSections.value = homeFeedBuilder.build(tracks, continueTrack, events, heavy)

                    val topArtists = events
                        .mapNotNull { event -> tracks.find { it.contentUri == event.trackUri }?.artist }
                        .filter { it.isNotBlank() }
                        .groupingBy { it }
                        .eachCount()
                        .entries
                        .sortedByDescending { it.value }
                        .map { it.key }
                    discoverSections.value = discoverEngine.build(tracks, topArtists)
                }
        }

        viewModelScope.launch {
            searchQuery
                .debounce(180)
                .collect { query ->
                    if (query.isBlank()) {
                        searchResults.value = emptyList()
                        isSearching.value = false
                        return@collect
                    }
                    isSearching.value = true
                    searchResults.value = repository.search(query)
                    isSearching.value = false
                }
        }
    }

    fun scan() {
        if (scanning.value) return
        viewModelScope.launch {
            scanning.value = true
            error.value = null
            runCatching { repository.scanDevice() }
                .onSuccess {
                    report.value = it
                    scheduleLibraryWatch()
                }
                .onFailure { error.value = it.message ?: "The scan could not finish." }
            scanning.value = false
        }
    }

    fun save(original: TrackEntity, updated: TrackEntity) = stageMetadataChange {
        repository.prepareMetadataEdit(original, updated)
    }

    fun verify(track: TrackEntity) = viewModelScope.launch { repository.verify(track.contentUri) }
    fun suggestTrash(track: TrackEntity) = viewModelScope.launch { repository.suggestTrash(track.contentUri) }
    fun toggleMood(track: TrackEntity, mood: String) = viewModelScope.launch {
        repository.setManualMoodTags(track.contentUri, MoodClassifier.toggle(track, mood))
    }

    fun updateSearchQuery(query: String) {
        searchQuery.value = query
    }

    /**
     * Prefer album context when available so Next/Previous stay musically coherent
     * without enqueueing the entire device library.
     */
    fun playTrack(track: TrackEntity) {
        if (!track.isPlayable()) return
        val album = track.album.trim()
        val albumArtist = track.albumArtist.ifBlank { track.artist }.trim()
        val queue = if (album.isNotBlank()) {
            state.value.tracks
                .filter {
                    it.isPlayable() &&
                        it.album.equals(album, ignoreCase = true) &&
                        it.albumArtist.ifBlank { it.artist }.equals(albumArtist, ignoreCase = true)
                }
                .sortedWith(
                    compareBy(
                        { it.discNumber ?: 0 },
                        { it.trackNumber ?: Int.MAX_VALUE },
                        { it.title },
                    ),
                )
        } else {
            listOf(track)
        }.ifEmpty { listOf(track) }

        playback.playQueue(
            tracks = queue,
            startIndex = queue.indexOfFirst { it.contentUri == track.contentUri }.coerceAtLeast(0),
            contextLabel = if (album.isNotBlank()) "album:$album" else "track",
        )
    }

    fun playQueue(tracks: List<TrackEntity>, startIndex: Int = 0, contextLabel: String = "queue") {
        val requested = tracks.getOrNull(startIndex)
        val playable = tracks.filter(TrackEntity::isPlayable)
        val playableIndex = requested
            ?.let { target -> playable.indexOfFirst { it.contentUri == target.contentUri } }
            ?.takeIf { it >= 0 }
            ?: 0
        playback.playQueue(playable, playableIndex, contextLabel)
    }

    fun playNext(track: TrackEntity) {
        if (!track.isPlayable()) return
        playback.playNext(track)
    }

    fun addToQueue(track: TrackEntity) {
        if (!track.isPlayable()) return
        playback.addToQueue(track)
    }

    fun cycleRepeat() = playback.cycleRepeat()

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
                .onSuccess { /* avoid full-device rescan; Room already updated */ }
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
                    .build(),
            )
            .build()
        WorkManager.getInstance(getApplication())
            .enqueueUniquePeriodicWork("archive-library-watch", ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

private fun TrackEntity.isPlayable(): Boolean =
    durationMs > 0 && status != ArchiveStatus.CORRUPTED && status != ArchiveStatus.TRASH_SUGGESTED

private data class Quint<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)

fun repeatModeLabel(mode: Int): String = when (mode) {
    Player.REPEAT_MODE_ONE -> "Repeat track"
    Player.REPEAT_MODE_ALL -> "Repeat queue"
    else -> "Repeat off"
}
