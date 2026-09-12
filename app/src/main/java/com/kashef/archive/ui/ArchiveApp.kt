package com.kashef.archive.ui

import android.Manifest
import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kashef.archive.data.ArchiveStatus
import com.kashef.archive.data.MetadataCandidate
import com.kashef.archive.data.IdentificationPhase
import com.kashef.archive.data.TrackEntity
import com.kashef.archive.domain.GeneratedPlaylist
import com.kashef.archive.domain.PlaylistGenerator
import com.kashef.archive.domain.PlaylistMood
import com.kashef.archive.playback.PlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private enum class Destination(val label: String, val icon: ImageVector) {
    PLAYER("Player", Icons.Default.Headphones),
    LIBRARY("Library", Icons.Default.LibraryMusic),
    PLAYLISTS("Playlists", Icons.Default.QueueMusic),
    IMPORT("Import", Icons.Default.FileDownload),
}

private enum class LibraryMode(val label: String) { ARTISTS("Artists"), ALBUMS("Albums"), SONGS("Songs"), GENRES("Genres") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveApp(viewModel: ArchiveViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val playback by viewModel.playback.state.collectAsStateWithLifecycle()
    val pendingMetadataChange by viewModel.pendingMetadataChange.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var destination by remember { mutableStateOf(Destination.PLAYER) }
    var editorTrack by remember { mutableStateOf<TrackEntity?>(null) }
    val generator = remember { PlaylistGenerator() }
    val mixes = remember(state.tracks) { PlaylistMood.entries.map { generator.generate(state.tracks, it) } }
    val currentTrack = state.tracks.firstOrNull { it.contentUri == playback.mediaId }
    val audioPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val permissions = buildList {
        add(audioPermission)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[audioPermission] == true) viewModel.scan()
    }
    val writePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.commitPendingMetadataChange()
        else viewModel.cancelPendingMetadataChange()
    }
    val requestScan = { permissionLauncher.launch(permissions) }

    LaunchedEffect(state.error) {
        state.error?.let { snackbarHostState.showSnackbar(it) }
    }

    LaunchedEffect(pendingMetadataChange) {
        val pending = pendingMetadataChange ?: return@LaunchedEffect
        if (pending.tagWrite != null && Build.VERSION.SDK_INT >= 30) {
            val request = MediaStore.createWriteRequest(context.contentResolver, listOf(Uri.parse(pending.contentUri)))
            writePermissionLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } else {
            viewModel.commitPendingMetadataChange()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                Column {
                    if (destination != Destination.PLAYER && playback.mediaId != null) {
                        MiniPlayer(playback, viewModel.playback::toggle, viewModel.playback::next)
                    }
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        Destination.entries.forEach { item ->
                            NavigationBarItem(
                                selected = destination == item,
                                onClick = { destination = item },
                                icon = { Icon(item.icon, contentDescription = null) },
                                label = { Text(item.label) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        when (destination) {
            Destination.PLAYER -> PlayerScreen(
                state = state,
                playback = playback,
                currentTrack = currentTrack,
                mixes = mixes,
                onScan = requestScan,
                onPlayTrack = viewModel::playTrack,
                onPlayMix = viewModel::playQueue,
                onToggle = viewModel.playback::toggle,
                onPrevious = viewModel.playback::previous,
                onNext = viewModel.playback::next,
                onShuffle = viewModel.playback::toggleShuffle,
                onSeek = viewModel.playback::seekTo,
                onToggleMood = viewModel::toggleMood,
                modifier = Modifier.padding(padding),
            )
            Destination.LIBRARY -> LibraryScreen(state.tracks, viewModel::playQueue, Modifier.padding(padding))
            Destination.PLAYLISTS -> PlaylistsScreen(
                mixes = mixes,
                moodTrack = currentTrack
                    ?: state.tracks.firstOrNull { it.moods.isEmpty() }
                    ?: state.tracks.firstOrNull(),
                onPlay = viewModel::playQueue,
                onToggleMood = viewModel::toggleMood,
                modifier = Modifier.padding(padding),
            )
            Destination.IMPORT -> ImportScreen(
                state = state,
                onScan = requestScan,
                onEdit = { editorTrack = it },
                onVerify = viewModel::verify,
                onTrash = viewModel::suggestTrash,
                modifier = Modifier.padding(padding),
            )
        }
    }

    editorTrack?.let { track ->
        MetadataEditor(
            initial = track,
            searchState = state.metadataSearch,
            onSearch = { viewModel.searchMetadata(track) },
            onCancelSearch = viewModel::cancelMetadataSearch,
            onApply = {
                viewModel.applyMetadata(track, it)
                editorTrack = null
            },
            onDismiss = {
                viewModel.clearMetadataSearch()
                editorTrack = null
            },
            onSave = {
                viewModel.save(track, it)
                editorTrack = null
            },
        )
    }
}

@Composable
private fun AppHeader(eyebrow: String, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(eyebrow.uppercase(Locale.US), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PlayerScreen(
    state: ArchiveUiState,
    playback: PlaybackState,
    currentTrack: TrackEntity?,
    mixes: List<GeneratedPlaylist>,
    onScan: () -> Unit,
    onPlayTrack: (TrackEntity) -> Unit,
    onPlayMix: (List<TrackEntity>) -> Unit,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onShuffle: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleMood: (TrackEntity, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item { AppHeader("Muse / 04", "Now playing") }
        if (state.tracks.isEmpty()) {
            item { EmptyLibrary(onScan, state.isScanning) }
        } else {
            val shownTrack = currentTrack ?: state.tracks.first()
            item { PlayerArtwork(shownTrack) }
            item {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(shownTrack.title.ifBlank { shownTrack.displayName }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(shownTrack.artist.ifBlank { "Unknown artist" }, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
            item {
                val duration = playback.durationMs.takeIf { playback.mediaId == shownTrack.contentUri && it > 0 } ?: shownTrack.durationMs
                val position = playback.positionMs.takeIf { playback.mediaId == shownTrack.contentUri } ?: 0L
                Column {
                    Slider(
                        value = position.coerceIn(0L, duration.coerceAtLeast(1L)).toFloat(),
                        onValueChange = { if (playback.mediaId == shownTrack.contentUri) onSeek(it.toLong()) },
                        valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatTime(position), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatTime(duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onShuffle) { Icon(Icons.Default.Shuffle, contentDescription = "Shuffle", tint = if (playback.shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
                    IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(34.dp)) }
                    FilledIconButton(
                        onClick = { if (playback.mediaId == shownTrack.contentUri) onToggle() else onPlayTrack(shownTrack) },
                        modifier = Modifier.size(68.dp),
                        shape = CircleShape,
                    ) {
                        Icon(if (playback.isPlaying && playback.mediaId == shownTrack.contentUri) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play or pause", modifier = Modifier.size(38.dp))
                    }
                    IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, contentDescription = "Next", modifier = Modifier.size(34.dp)) }
                }
            }
            item { SectionTitle("Made for this moment", "Generated from your archive") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(mixes.filter { it.mood != PlaylistMood.DISCOVERY }, key = { it.mood.name }) { mix ->
                        MoodTile(mix, enabled = mix.tracks.isNotEmpty()) { onPlayMix(mix.tracks) }
                    }
                }
            }
            item {
                MoodTeacher(track = shownTrack, onToggle = { onToggleMood(shownTrack, it) })
            }
        }
    }
}

@Composable
private fun PlayerArtwork(track: TrackEntity) {
    val hue = (track.title.hashCode().toUInt().toLong() % 3).toInt()
    val secondary = listOf(Color(0xFF5A2412), Color(0xFF3B203E), Color(0xFF432A16))[hue]
    val artwork by embeddedArtwork(track.contentUri)
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFFFF6B1A), secondary, Color(0xFF090909)))),
    ) {
        artwork?.let {
            Image(
                bitmap = it,
                contentDescription = "Artwork for ${track.title}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } ?: Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("MUSE ARCHIVE", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = .72f))
            Text(
                track.title.ifBlank { "UNKNOWN TRACK" }.uppercase(Locale.US),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Black,
                color = Color.White,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun embeddedArtwork(contentUri: String): androidx.compose.runtime.State<ImageBitmap?> {
    val context = LocalContext.current
    return produceState<ImageBitmap?>(initialValue = null, contentUri) {
        value = withContext(Dispatchers.IO) {
            ArtworkMemoryCache.get(contentUri)?.asImageBitmap() ?: run {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, Uri.parse(contentUri))
                    retriever.embeddedPicture?.let { bytes ->
                        decodeSampledArtwork(bytes)?.also { ArtworkMemoryCache.put(contentUri, it) }?.asImageBitmap()
                    }
                } catch (_: Exception) {
                    null
                } finally {
                    runCatching { retriever.release() }
                }
            }
        }
    }
}

private object ArtworkMemoryCache {
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024L / 16L).coerceAtMost(24L * 1024L).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount / 1024
    }

    fun get(key: String): Bitmap? = cache.get(key)
    fun put(key: String, bitmap: Bitmap) = cache.put(key, bitmap)
}

private fun decodeSampledArtwork(bytes: ByteArray, maxEdgePx: Int = 1024): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sampleSize = 1
    while (bounds.outWidth / sampleSize > maxEdgePx || bounds.outHeight / sampleSize > maxEdgePx) {
        sampleSize *= 2
    }
    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        },
    )
}

@Composable
private fun EmptyLibrary(onScan: () -> Unit, isScanning: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(46.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Bring your music into Muse", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Your audio stays on this device. Muse indexes it, checks it, and builds a player around it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onScan, enabled = !isScanning, modifier = Modifier.fillMaxWidth()) {
                if (isScanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp)); Text(if (isScanning) "Scanning…" else "Scan this device")
            }
        }
    }
}

@Composable
private fun LibraryScreen(
    tracks: List<TrackEntity>,
    onPlayQueue: (List<TrackEntity>, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val playableTracks = remember(tracks) {
        tracks.filter {
            it.durationMs > 0 &&
                it.status != ArchiveStatus.CORRUPTED &&
                it.status != ArchiveStatus.TRASH_SUGGESTED
        }
    }
    var query by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(LibraryMode.ARTISTS) }
    var openCollection by remember { mutableStateOf<Pair<String, List<TrackEntity>>?>(null) }
    val filtered = remember(playableTracks, query) {
        playableTracks.filter { query.isBlank() || listOf(it.title, it.artist, it.album, it.genre).any { field -> field.contains(query, true) } }
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        openCollection?.let { (name, collectionTracks) ->
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { openCollection = null }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back to library")
                    }
                    Column {
                        Text("COLLECTION", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        Text(name, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                        Text("${collectionTracks.size} tracks", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            itemsIndexed(collectionTracks, key = { _, track -> track.contentUri }) { index, track ->
                TrackRow(track) { onPlayQueue(collectionTracks, index) }
            }
            return@LazyColumn
        }
        item { AppHeader("Your archive", "Library") }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text("Artist, album or song") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
            )
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LibraryMode.entries.forEach { item ->
                    if (mode == item) Button(onClick = { mode = item }) { Text(item.label) }
                    else OutlinedButton(onClick = { mode = item }) { Text(item.label) }
                }
            }
        }
        when (mode) {
            LibraryMode.SONGS -> itemsIndexed(filtered, key = { _, track -> track.contentUri }) { index, track ->
                TrackRow(track) { onPlayQueue(filtered, index) }
            }
            LibraryMode.ARTISTS -> {
                val artists = filtered.groupBy { it.artist.ifBlank { "Unknown artist" } }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                items(artists.entries.toList(), key = { it.key }) { (artist, artistTracks) ->
                    CollectionRow(artist, "${artistTracks.size} tracks", Icons.Default.Headphones) {
                        openCollection = artist to artistTracks
                    }
                }
            }
            LibraryMode.ALBUMS -> {
                val albums = filtered.groupBy { it.album.ifBlank { "Unknown album" } }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                items(albums.entries.toList(), key = { it.key }) { (album, albumTracks) ->
                    CollectionRow(album, "${albumTracks.firstOrNull()?.artist.orEmpty()} · ${albumTracks.size} tracks", Icons.Default.Album) {
                        openCollection = album to albumTracks
                    }
                }
            }
            LibraryMode.GENRES -> {
                val genres = filtered.groupBy { it.genre.ifBlank { "Unclassified" } }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                items(genres.entries.toList(), key = { it.key }) { (genre, genreTracks) ->
                    CollectionRow(genre, "${genreTracks.size} tracks", Icons.Default.MusicNote) {
                        openCollection = genre to genreTracks
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Open collection")
        }
    }
}

@Composable
private fun TrackRow(track: TrackEntity, onPlay: (TrackEntity) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onPlay(track) }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(track.title.ifBlank { track.displayName }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(track.artist, track.album).filter(String::isNotBlank).joinToString(" · ").ifBlank { "Unknown record" }, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { onPlay(track) }) { Icon(Icons.Default.PlayArrow, contentDescription = "Play") }
    }
}

@Composable
private fun PlaylistsScreen(
    mixes: List<GeneratedPlaylist>,
    moodTrack: TrackEntity?,
    onPlay: (List<TrackEntity>) -> Unit,
    onToggleMood: (TrackEntity, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { AppHeader("Adaptive mixes", "Playlists") }
        items(mixes, key = { it.mood.name }) { mix ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable(enabled = mix.tracks.isNotEmpty()) { onPlay(mix.tracks) },
                colors = CardDefaults.cardColors(containerColor = if (mix.mood == PlaylistMood.ENERGY) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(22.dp),
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(17.dp)), contentAlignment = Alignment.Center) {
                        Icon(moodIcon(mix.mood), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(mix.mood.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(mix.mood.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        Text("${mix.tracks.size} tracks", color = MaterialTheme.colorScheme.primary)
                    }
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play ${mix.mood.title}")
                }
            }
        }
        moodTrack?.let { track ->
            item {
                SectionTitle(
                    if (track.moods.isEmpty()) "Classify your untagged music" else "Refine this track",
                    "${track.title.ifBlank { track.displayName }} · Your choices update the mixes immediately",
                )
            }
            item { MoodTeacher(track, onToggle = { onToggleMood(track, it) }) }
        }
    }
}

@Composable
private fun MoodTeacher(track: TrackEntity, onToggle: (String) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Teach Muse this track", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Automatic suggestions stay editable. Your corrections always win.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("CALM", "ENERGY", "FOCUS", "NIGHT").forEach { mood ->
                    AssistChip(
                        onClick = { onToggle(mood) },
                        label = { Text(mood.lowercase().replaceFirstChar(Char::uppercase)) },
                        leadingIcon = if (mood in track.moods) {
                            { Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportScreen(
    state: ArchiveUiState,
    onScan: () -> Unit,
    onEdit: (TrackEntity) -> Unit,
    onVerify: (TrackEntity) -> Unit,
    onTrash: (TrackEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pending = state.tracks.filter { it.status != ArchiveStatus.VERIFIED }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { AppHeader("Metadata workspace", "Import") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Ready to inspect", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("${pending.size} tracks", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Button(onClick = onScan, enabled = !state.isScanning, modifier = Modifier.fillMaxWidth()) {
                        if (state.isScanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp)); Text(if (state.isScanning) "Scanning device…" else "Scan for new music")
                    }
                }
            }
        }
        state.report?.let { report -> item { Text("${report.found} indexed · ${report.addedOrUpdated} changed", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        if (pending.isEmpty()) item {
            Text(if (state.tracks.isEmpty()) "Scan your device to begin." else "Your archive is clean.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(pending, key = TrackEntity::contentUri) { track ->
            ImportTrackCard(track, onEdit, onVerify, onTrash)
        }
    }
}

@Composable
private fun ImportTrackCard(track: TrackEntity, onEdit: (TrackEntity) -> Unit, onVerify: (TrackEntity) -> Unit, onTrash: (TrackEntity) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (track.matchSource == "ACOUSTID_FINGERPRINT") Icons.Default.Fingerprint else Icons.Default.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(track.title.ifBlank { track.displayName }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist.ifBlank { "Unknown artist" }, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Text("${track.healthScore}%", color = scoreColor(track.healthScore), fontWeight = FontWeight.Bold)
            }
            if (track.originalTitle.isNotBlank()) Text("Original-script alias preserved for matching", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (track.issues.isNotEmpty()) {
                Text(
                    track.issues.take(4).joinToString(" · ") { it.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase) },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .45f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(onClick = { onEdit(track) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(5.dp)); Text("Identify") }
                if (track.healthScore >= 90) IconButton(onClick = { onVerify(track) }) { Icon(Icons.Default.CheckCircle, "Verify") }
                IconButton(onClick = { onTrash(track) }) { Icon(Icons.Default.DeleteOutline, "Suggest removal") }
            }
        }
    }
}

@Composable
private fun MiniPlayer(state: PlaybackState, onToggle: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text(state.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onToggle) { Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause") }
        IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "Next") }
    }
}

@Composable
private fun MoodTile(mix: GeneratedPlaylist, enabled: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.width(148.dp).height(116.dp).clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(moodIcon(mix.mood), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            Text(mix.mood.title, fontWeight = FontWeight.Bold)
            Text("${mix.tracks.size} tracks", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun moodIcon(mood: PlaylistMood): ImageVector = when (mood) {
    PlaylistMood.FOCUS -> Icons.Default.Headphones
    PlaylistMood.ENERGY -> Icons.Default.LocalFireDepartment
    PlaylistMood.CALM -> Icons.Default.AutoAwesome
    PlaylistMood.NIGHT -> Icons.Default.DarkMode
    PlaylistMood.DISCOVERY -> Icons.Default.Shuffle
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

private fun scoreColor(score: Int): Color = when {
    score >= 90 -> Color(0xFFFF8A4C)
    score >= 70 -> Color(0xFFFFB36B)
    else -> Color(0xFFFF6B62)
}

private fun formatTime(milliseconds: Long): String {
    val seconds = (milliseconds / 1_000L).coerceAtLeast(0L)
    return "%d:%02d".format(Locale.US, seconds / 60, seconds % 60)
}

@Composable
private fun MetadataEditor(
    initial: TrackEntity,
    searchState: MetadataSearchState,
    onSearch: () -> Unit,
    onCancelSearch: () -> Unit,
    onApply: (MetadataCandidate) -> Unit,
    onDismiss: () -> Unit,
    onSave: (TrackEntity) -> Unit,
) {
    var title by remember(initial) { mutableStateOf(initial.title) }
    var artist by remember(initial) { mutableStateOf(initial.artist) }
    var albumArtist by remember(initial) { mutableStateOf(initial.albumArtist) }
    var album by remember(initial) { mutableStateOf(initial.album) }
    var genre by remember(initial) { mutableStateOf(initial.genre) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review metadata") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text("Fingerprint evidence comes first. Latin-script metadata is the canonical archive format.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (initial.originalTitle.isNotBlank() || initial.originalArtist.isNotBlank()) item {
                    Text("Original: ${listOf(initial.originalTitle, initial.originalArtist).filter(String::isNotBlank).joinToString(" · ")}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    Button(
                        onClick = if (searchState.isSearching) onCancelSearch else onSearch,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (searchState.isSearching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Fingerprint, contentDescription = null)
                        Spacer(Modifier.width(8.dp)); Text(
                            if (searchState.isSearching) "${searchState.phase.label()} · Tap to cancel" else "Identify from audio"
                        )
                    }
                }
                if (searchState.trackUri == initial.contentUri) {
                    searchState.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                    items(searchState.candidates, key = MetadataCandidate::recordingId) { candidate ->
                        MetadataCandidateCard(candidate) { onApply(candidate) }
                    }
                }
                item { OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(artist, { artist = it }, label = { Text("Primary artist") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(albumArtist, { albumArtist = it }, label = { Text("Album artist") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(album, { album = it }, label = { Text("Album") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(genre, { genre = it }, label = { Text("Genre") }, modifier = Modifier.fillMaxWidth()) }
            }
        },
        confirmButton = { Button(onClick = { onSave(initial.copy(title = title, artist = artist, albumArtist = albumArtist, album = album, genre = genre)) }) { Text("Save canonical record") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun IdentificationPhase?.label(): String = when (this) {
    IdentificationPhase.READING_TAGS -> "Reading this file"
    IdentificationPhase.FINGERPRINTING -> "Listening for up to 60 seconds"
    IdentificationPhase.ACOUSTID -> "Matching the fingerprint"
    IdentificationPhase.CATALOGS -> "Searching music catalogs"
    null -> "Preparing"
}

@Composable
private fun MetadataCandidateCard(candidate: MetadataCandidate, onApply: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(candidate.title, fontWeight = FontWeight.Bold)
                    Text(candidate.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(listOfNotNull(candidate.album.takeIf(String::isNotBlank), candidate.year?.toString()).joinToString(" · "), style = MaterialTheme.typography.labelMedium)
                }
                Text("${candidate.confidence}%", color = scoreColor(candidate.confidence), fontWeight = FontWeight.Bold)
            }
            Text(candidate.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Button(onClick = onApply, modifier = Modifier.fillMaxWidth()) { Text("Use this match") }
        }
    }
}
