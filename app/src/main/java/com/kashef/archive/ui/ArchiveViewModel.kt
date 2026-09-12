package com.kashef.archive.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.kashef.archive.ArchiveApplication
import com.kashef.archive.data.ArchiveStatus
import com.kashef.archive.data.MusicScanWorker
import com.kashef.archive.data.MetadataCandidate
import com.kashef.archive.data.ScanReport
import com.kashef.archive.data.TrackEntity
import com.kashef.archive.data.PreparedMetadataChange
import com.kashef.archive.domain.MoodClassifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

data class ArchiveUiState(
    val tracks: List<TrackEntity> = emptyList(),
    val isScanning: Boolean = false,
    val report: ScanReport? = null,
    val error: String? = null,
    val metadataSearch: MetadataSearchState = MetadataSearchState(),
) {
    val verifiedCount get() = tracks.count { it.status == ArchiveStatus.VERIFIED }
    val reviewCount get() = tracks.count { it.status == ArchiveStatus.NEEDS_REVIEW || it.status == ArchiveStatus.UNIDENTIFIED }
    val duplicateCount get() = tracks.count { it.status == ArchiveStatus.DUPLICATE }
    val averageHealth get() = if (tracks.isEmpty()) 0 else tracks.sumOf { it.healthScore } / tracks.size
}

data class MetadataSearchState(
    val trackUri: String? = null,
    val isSearching: Boolean = false,
    val candidates: List<MetadataCandidate> = emptyList(),
    val error: String? = null,
)

class ArchiveViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as ArchiveApplication).repository
    private val scanning = MutableStateFlow(false)
    private val report = MutableStateFlow<ScanReport?>(null)
    private val error = MutableStateFlow<String?>(null)
    private val metadataSearch = MutableStateFlow(MetadataSearchState())
    private val mutablePendingMetadataChange = MutableStateFlow<PreparedMetadataChange?>(null)
    val pendingMetadataChange: StateFlow<PreparedMetadataChange?> = mutablePendingMetadataChange
    val playback = (application as ArchiveApplication).playback

    val state: StateFlow<ArchiveUiState> = combine(
        repository.observeTracks(), scanning, report, error, metadataSearch
    ) { tracks, isScanning, latestReport, latestError, search ->
        ArchiveUiState(tracks, isScanning, latestReport, latestError, search)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ArchiveUiState())

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

    fun playTrack(track: TrackEntity) {
        if (!track.isPlayable()) return
        val queue = state.value.tracks.filter {
            it.isPlayable()
        }
        playback.playQueue(queue, queue.indexOfFirst { it.contentUri == track.contentUri }.coerceAtLeast(0))
    }

    fun playQueue(tracks: List<TrackEntity>, startIndex: Int = 0) {
        val requested = tracks.getOrNull(startIndex)
        val playable = tracks.filter(TrackEntity::isPlayable)
        val playableIndex = requested
            ?.let { target -> playable.indexOfFirst { it.contentUri == target.contentUri } }
            ?.takeIf { it >= 0 }
            ?: 0
        playback.playQueue(playable, playableIndex)
    }

    fun searchMetadata(track: TrackEntity) {
        if (metadataSearch.value.isSearching) return
        viewModelScope.launch {
            metadataSearch.value = MetadataSearchState(trackUri = track.contentUri, isSearching = true)
            runCatching { repository.searchMetadata(track) }
                .onSuccess { candidates ->
                    metadataSearch.value = MetadataSearchState(
                        trackUri = track.contentUri,
                        candidates = candidates,
                        error = if (candidates.isEmpty()) "No credible MusicBrainz match found." else null,
                    )
                }
                .onFailure {
                    metadataSearch.value = MetadataSearchState(
                        trackUri = track.contentUri,
                        error = it.message ?: "Metadata search failed.",
                    )
                }
        }
    }

    fun applyMetadata(track: TrackEntity, candidate: MetadataCandidate) = viewModelScope.launch {
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
                .onSuccess { scan() }
                .onFailure { error.value = it.message ?: "The tag rewrite failed." }
        }
    }

    fun cancelPendingMetadataChange() {
        mutablePendingMetadataChange.value?.let(repository::cancelMetadataChange)
        mutablePendingMetadataChange.value = null
    }

    fun clearMetadataSearch() {
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

private fun TrackEntity.isPlayable(): Boolean =
    durationMs > 0 && status != ArchiveStatus.CORRUPTED && status != ArchiveStatus.TRASH_SUGGESTED
