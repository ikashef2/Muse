package com.kashef.archive.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.kashef.archive.data.TrackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackState(
    val connected: Boolean = false,
    val isPlaying: Boolean = false,
    val mediaId: String? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val error: String? = null,
)

class PlaybackConnection(context: Context) {
    private val appContext = context.applicationContext
    private val executor = ContextCompat.getMainExecutor(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessionStore = PlaybackSessionStore(appContext)
    private val controllerFuture = MediaController.Builder(
        appContext,
        SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java)),
    ).buildAsync()
    private var controller: MediaController? = null
    private val mutableState = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = mutableState.asStateFlow()
    private var pendingRestore: PlaybackSessionStore.Session? = sessionStore.load()
    private var lastQueueIds: List<String> = pendingRestore?.mediaIds.orEmpty()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish(player)
            persist(player)
        }

        override fun onPlayerError(error: PlaybackException) {
            mutableState.value = mutableState.value.copy(
                error = error.message ?: "Playback failed.",
                isPlaying = false,
            )
        }
    }

    init {
        controllerFuture.addListener({
            runCatching { controllerFuture.get() }.onSuccess { player ->
                controller = player
                player.addListener(listener)
                restoreSessionIfNeeded(player)
                publish(player)
            }
        }, executor)
        scope.launch {
            while (isActive) {
                controller?.let { player ->
                    publish(player)
                    if (player.isPlaying) persist(player)
                }
                delay(500L)
            }
        }
    }

    fun playQueue(tracks: List<TrackEntity>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        withController { player ->
            val items = tracks.map(TrackEntity::asMediaItem)
            lastQueueIds = items.map { it.mediaId }
            pendingRestore = null
            player.setMediaItems(items, startIndex.coerceIn(items.indices), 0L)
            player.prepare()
            player.play()
            persist(player)
        }
    }

    /**
     * Restores a persisted queue when track rows are available after a cold start.
     * Safe to call repeatedly; no-ops once a live queue exists or nothing was saved.
     */
    fun restoreSavedQueue(tracksByUri: Map<String, TrackEntity>) {
        val saved = pendingRestore ?: sessionStore.load() ?: return
        if (controller?.mediaItemCount?.let { it > 0 } == true) {
            pendingRestore = null
            return
        }
        val ordered = saved.mediaIds.mapNotNull { tracksByUri[it] }
        if (ordered.isEmpty()) {
            sessionStore.clear()
            pendingRestore = null
            return
        }
        val startUri = saved.mediaIds.getOrNull(saved.startIndex)
        val startIndex = ordered.indexOfFirst { it.contentUri == startUri }.takeIf { it >= 0 } ?: 0
        withController { player ->
            lastQueueIds = ordered.map { it.contentUri }
            player.shuffleModeEnabled = saved.shuffleEnabled
            player.repeatMode = saved.repeatMode
            player.setMediaItems(ordered.map(TrackEntity::asMediaItem), startIndex, saved.positionMs)
            player.prepare()
            if (saved.wasPlaying) player.play() else player.pause()
            pendingRestore = null
            persist(player)
            publish(player)
        }
    }

    fun toggle() = withController { if (it.isPlaying) it.pause() else it.play() }
    fun next() = withController { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
    fun previous() = withController {
        if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() else it.seekTo(0L)
    }
    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs.coerceAtLeast(0L)) }
    fun toggleShuffle() = withController {
        it.shuffleModeEnabled = !it.shuffleModeEnabled
        persist(it)
    }

    fun cycleRepeatMode() = withController { player ->
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
        persist(player)
        publish(player)
    }

    fun clearError() {
        mutableState.value = mutableState.value.copy(error = null)
    }

    private fun withController(action: (MediaController) -> Unit) {
        controller?.let(action) ?: controllerFuture.addListener({
            runCatching { controllerFuture.get() }.onSuccess(action)
        }, executor)
    }

    private fun restoreSessionIfNeeded(player: Player) {
        if (player.mediaItemCount > 0) {
            pendingRestore = null
            return
        }
        // Full restore needs TrackEntity metadata; ViewModel supplies that via restoreSavedQueue.
    }

    private fun persist(player: Player) {
        val count = player.mediaItemCount
        if (count <= 0) return
        val ids = if (lastQueueIds.size == count) {
            lastQueueIds
        } else {
            buildList {
                for (i in 0 until count) {
                    player.getMediaItemAt(i).mediaId.takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }
        if (ids.isEmpty()) return
        lastQueueIds = ids
        sessionStore.save(
            PlaybackSessionStore.Session(
                mediaIds = ids,
                startIndex = player.currentMediaItemIndex.coerceIn(0, ids.lastIndex),
                positionMs = player.currentPosition.coerceAtLeast(0L),
                shuffleEnabled = player.shuffleModeEnabled,
                repeatMode = player.repeatMode,
                wasPlaying = player.isPlaying || player.playWhenReady,
            )
        )
    }

    private fun publish(player: Player) {
        val metadata = player.currentMediaItem?.mediaMetadata
        mutableState.value = PlaybackState(
            connected = true,
            isPlaying = player.isPlaying,
            mediaId = player.currentMediaItem?.mediaId,
            title = metadata?.title?.toString().orEmpty(),
            artist = metadata?.artist?.toString().orEmpty(),
            album = metadata?.albumTitle?.toString().orEmpty(),
            hasNext = player.hasNextMediaItem(),
            hasPrevious = player.hasPreviousMediaItem(),
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.takeIf { it > 0 } ?: 0L,
            shuffleEnabled = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            error = mutableState.value.error,
        )
    }
}

private fun TrackEntity.asMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(contentUri)
    .setUri(contentUri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title.ifBlank { displayName })
            .setArtist(artist.ifBlank { "Unknown artist" })
            .setAlbumTitle(album)
            .build()
    )
    .build()
