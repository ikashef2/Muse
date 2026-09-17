package com.kashef.archive.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.kashef.archive.data.TrackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    val queueSize: Int = 0,
)

data class ListeningSnapshot(
    val trackUri: String,
    val listenedMs: Long,
    val trackDurationMs: Long,
    val skipped: Boolean,
    val completed: Boolean,
    val context: String,
)

class PlaybackConnection(
    context: Context,
    private val sessionStore: PlaybackSessionStore = PlaybackSessionStore(context),
    private val onListeningEvent: ((ListeningSnapshot) -> Unit)? = null,
) {
    private val appContext = context.applicationContext
    private val executor = ContextCompat.getMainExecutor(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controllerFuture = MediaController.Builder(
        appContext,
        SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java)),
    ).buildAsync()
    private var controller: MediaController? = null
    private val mutableState = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    private var queueUris: List<String> = emptyList()
    private var playbackContext: String = ""
    private var listenStartedAt: Long = 0L
    private var listenTrackUri: String? = null
    private var listenAccumulatedMs: Long = 0L
    private var lastPositionMs: Long = 0L
    private var persistJob: Job? = null
    private var pendingRestore: PersistedPlaybackSession? = sessionStore.load()
    private var trackResolver: (suspend (List<String>) -> List<TrackEntity>)? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) ||
                events.contains(Player.EVENT_IS_PLAYING_CHANGED)
            ) {
                handleListenTransition(player)
            }
            publish(player)
            schedulePersist(player)
        }
    }

    init {
        controllerFuture.addListener({
            runCatching { controllerFuture.get() }.onSuccess { player ->
                controller = player
                player.addListener(listener)
                publish(player)
                maybeRestore(player)
            }
        }, executor)
        scope.launch {
            while (isActive) {
                controller?.let { player ->
                    accumulateListen(player)
                    publish(player)
                }
                delay(1_000L)
            }
        }
    }

    fun setTrackResolver(resolver: suspend (List<String>) -> List<TrackEntity>) {
        trackResolver = resolver
        controller?.let(::maybeRestore)
    }

    fun playQueue(tracks: List<TrackEntity>, startIndex: Int = 0, contextLabel: String = "") {
        if (tracks.isEmpty()) return
        val capped = tracks.take(MAX_QUEUE_SIZE)
        val index = startIndex.coerceIn(0, capped.lastIndex)
        playbackContext = contextLabel
        queueUris = capped.map(TrackEntity::contentUri)
        withController { player ->
            flushListening(player, forceSkip = false)
            val items = capped.map(TrackEntity::asMediaItem)
            player.setMediaItems(items, index, 0L)
            player.prepare()
            player.play()
            beginListen(player)
            schedulePersist(player, immediate = true)
        }
    }

    fun playNext(track: TrackEntity) {
        withController { player ->
            val current = player.currentMediaItemIndex
            player.addMediaItem((current + 1).coerceAtLeast(0), track.asMediaItem())
            queueUris = queueUris.toMutableList().apply {
                add((current + 1).coerceAtMost(size), track.contentUri)
            }
            schedulePersist(player, immediate = true)
        }
    }

    fun addToQueue(track: TrackEntity) {
        withController { player ->
            player.addMediaItem(track.asMediaItem())
            queueUris = queueUris + track.contentUri
            schedulePersist(player, immediate = true)
        }
    }

    fun toggle() = withController {
        if (it.isPlaying) it.pause() else it.play()
    }

    fun next() = withController {
        if (it.hasNextMediaItem()) it.seekToNextMediaItem()
    }

    fun previous() = withController {
        if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() else it.seekTo(0L)
    }

    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs.coerceAtLeast(0L)) }

    fun toggleShuffle() = withController {
        it.shuffleModeEnabled = !it.shuffleModeEnabled
        schedulePersist(it, immediate = true)
    }

    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        schedulePersist(it, immediate = true)
    }

    private fun maybeRestore(player: Player) {
        val session = pendingRestore ?: return
        val resolver = trackResolver ?: return
        pendingRestore = null
        scope.launch {
            val tracks = resolver(session.queueUris)
            if (tracks.isEmpty()) {
                sessionStore.clear()
                return@launch
            }
            val ordered = session.queueUris.mapNotNull { uri -> tracks.find { it.contentUri == uri } }
            if (ordered.isEmpty()) {
                sessionStore.clear()
                return@launch
            }
            queueUris = ordered.map(TrackEntity::contentUri)
            playbackContext = "restored"
            withController { ctrl ->
                ctrl.setMediaItems(
                    ordered.map(TrackEntity::asMediaItem),
                    session.startIndex.coerceIn(0, ordered.lastIndex),
                    session.positionMs,
                )
                ctrl.shuffleModeEnabled = session.shuffleEnabled
                ctrl.repeatMode = session.repeatMode
                ctrl.prepare()
                ctrl.pause()
                beginListen(ctrl)
                publish(ctrl)
            }
        }
    }

    private fun beginListen(player: Player) {
        listenTrackUri = player.currentMediaItem?.mediaId
        listenStartedAt = System.currentTimeMillis()
        listenAccumulatedMs = 0L
        lastPositionMs = player.currentPosition.coerceAtLeast(0L)
    }

    private fun accumulateListen(player: Player) {
        if (!player.isPlaying) return
        val position = player.currentPosition.coerceAtLeast(0L)
        val delta = position - lastPositionMs
        if (delta in 1..2_500) listenAccumulatedMs += delta
        lastPositionMs = position
    }

    private fun handleListenTransition(player: Player) {
        val currentId = player.currentMediaItem?.mediaId
        if (listenTrackUri != null && currentId != listenTrackUri) {
            flushListening(player, forceSkip = true)
            beginListen(player)
        } else if (listenTrackUri == null && currentId != null) {
            beginListen(player)
        }
    }

    private fun flushListening(player: Player, forceSkip: Boolean) {
        val uri = listenTrackUri ?: return
        accumulateListen(player)
        val duration = player.duration.takeIf { it > 0 } ?: 0L
        val listened = listenAccumulatedMs.coerceAtLeast(0L)
        if (listened < 1_500L && !forceSkip) {
            listenTrackUri = null
            return
        }
        val completed = duration > 0 && listened >= (duration * 0.85).toLong()
        val skipped = forceSkip && !completed && listened < (duration * 0.85).toLong()
        onListeningEvent?.invoke(
            ListeningSnapshot(
                trackUri = uri,
                listenedMs = listened,
                trackDurationMs = duration,
                skipped = skipped,
                completed = completed,
                context = playbackContext,
            ),
        )
        listenTrackUri = null
        listenAccumulatedMs = 0L
    }

    private fun schedulePersist(player: Player, immediate: Boolean = false) {
        persistJob?.cancel()
        persistJob = scope.launch {
            if (!immediate) delay(750L)
            val uris = buildList {
                for (i in 0 until player.mediaItemCount) {
                    player.getMediaItemAt(i).mediaId?.let(::add)
                }
            }.ifEmpty { queueUris }
            if (uris.isEmpty()) return@launch
            sessionStore.save(
                PersistedPlaybackSession(
                    queueUris = uris,
                    startIndex = player.currentMediaItemIndex.coerceAtLeast(0),
                    positionMs = player.currentPosition.coerceAtLeast(0L),
                    shuffleEnabled = player.shuffleModeEnabled,
                    repeatMode = player.repeatMode,
                ),
            )
            queueUris = uris
        }
    }

    private fun withController(action: (MediaController) -> Unit) {
        controller?.let(action) ?: controllerFuture.addListener({
            runCatching { controllerFuture.get() }.onSuccess(action)
        }, executor)
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
            hasPrevious = player.hasPreviousMediaItem() || player.currentPosition > 3_000L,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.takeIf { it > 0 } ?: 0L,
            shuffleEnabled = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            queueSize = player.mediaItemCount.takeIf { it > 0 } ?: queueUris.size,
        )
    }

    companion object {
        /** Hard cap so a library tap never materializes tens of thousands of MediaItems. */
        const val MAX_QUEUE_SIZE = 500
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
            .build(),
    )
    .build()
