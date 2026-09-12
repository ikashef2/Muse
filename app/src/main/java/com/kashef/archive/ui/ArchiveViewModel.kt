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
import com.kashef.archive.data.ScanReport
import com.kashef.archive.data.TrackEntity
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
) {
    val verifiedCount get() = tracks.count { it.status == ArchiveStatus.VERIFIED }
    val reviewCount get() = tracks.count { it.status == ArchiveStatus.NEEDS_REVIEW || it.status == ArchiveStatus.UNIDENTIFIED }
    val duplicateCount get() = tracks.count { it.status == ArchiveStatus.DUPLICATE }
    val averageHealth get() = if (tracks.isEmpty()) 0 else tracks.sumOf { it.healthScore } / tracks.size
}

class ArchiveViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as ArchiveApplication).repository
    private val scanning = MutableStateFlow(false)
    private val report = MutableStateFlow<ScanReport?>(null)
    private val error = MutableStateFlow<String?>(null)

    val state: StateFlow<ArchiveUiState> = combine(
        repository.observeTracks(), scanning, report, error
    ) { tracks, isScanning, latestReport, latestError ->
        ArchiveUiState(tracks, isScanning, latestReport, latestError)
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

    fun save(track: TrackEntity) = viewModelScope.launch { repository.edit(track) }
    fun verify(track: TrackEntity) = viewModelScope.launch { repository.verify(track.contentUri) }
    fun suggestTrash(track: TrackEntity) = viewModelScope.launch { repository.suggestTrash(track.contentUri) }

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
