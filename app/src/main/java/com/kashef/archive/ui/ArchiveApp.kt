package com.kashef.archive.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kashef.archive.data.ArchiveStatus
import com.kashef.archive.data.MetadataCandidate
import com.kashef.archive.data.TrackEntity
import com.kashef.archive.domain.GeneratedPlaylist
import com.kashef.archive.domain.PlaylistGenerator
import com.kashef.archive.domain.PlaylistMood
import com.kashef.archive.playback.PlaybackState

private enum class Destination(val label: String, val icon: ImageVector) {
    OVERVIEW("Overview", Icons.Default.HealthAndSafety),
    INBOX("Inbox", Icons.Default.Inbox),
    LIBRARY("Library", Icons.Default.LibraryMusic),
    MIXES("Mixes", Icons.Default.QueueMusic),
}

@Composable
fun ArchiveApp(viewModel: ArchiveViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val playback by viewModel.playback.state.collectAsStateWithLifecycle()
    var destination by remember { mutableStateOf(Destination.OVERVIEW) }
    var editorTrack by remember { mutableStateOf<TrackEntity?>(null) }
    val generatedMixes = remember(state.tracks) {
        val generator = PlaylistGenerator()
        PlaylistMood.entries.map { generator.generate(state.tracks, it) }
    }
    val audioPermission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else Manifest.permission.READ_EXTERNAL_STORAGE
    val permissions = buildList {
        add(audioPermission)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants -> if (grants[audioPermission] == true) viewModel.scan() }

    Scaffold(
        bottomBar = {
            Column {
                if (playback.mediaId != null) {
                    MiniPlayer(
                        state = playback,
                        onToggle = viewModel.playback::toggle,
                        onPrevious = viewModel.playback::previous,
                        onNext = viewModel.playback::next,
                    )
                }
                NavigationBar {
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
    ) { padding ->
        when (destination) {
            Destination.OVERVIEW -> OverviewScreen(
                state = state,
                onScan = { permissionLauncher.launch(permissions) },
                modifier = Modifier.padding(padding),
            )
            Destination.INBOX -> TrackListScreen(
                title = "Review inbox",
                subtitle = "Nothing enters your archive without earning its place.",
                tracks = state.tracks.filter { it.status != ArchiveStatus.VERIFIED },
                onEdit = { editorTrack = it },
                onPlay = viewModel::playTrack,
                onVerify = viewModel::verify,
                onTrash = viewModel::suggestTrash,
                modifier = Modifier.padding(padding),
            )
            Destination.LIBRARY -> TrackListScreen(
                title = "Verified library",
                subtitle = "Only approved, canonical records live here.",
                tracks = state.tracks.filter { it.status == ArchiveStatus.VERIFIED },
                onEdit = { editorTrack = it },
                onPlay = viewModel::playTrack,
                onVerify = viewModel::verify,
                onTrash = viewModel::suggestTrash,
                modifier = Modifier.padding(padding),
            )
            Destination.MIXES -> MixesScreen(
                mixes = generatedMixes,
                onPlay = viewModel::playQueue,
                modifier = Modifier.padding(padding),
            )
        }
    }

    editorTrack?.let { track ->
        MetadataEditor(
            initial = track,
            searchState = state.metadataSearch,
            onSearch = { viewModel.searchMetadata(track) },
            onApply = {
                viewModel.applyMetadata(track, it)
                editorTrack = null
            },
            onDismiss = {
                viewModel.clearMetadataSearch()
                editorTrack = null
            },
            onSave = {
                viewModel.save(it)
                editorTrack = null
            },
        )
    }
}

@Composable
private fun MiniPlayer(
    state: PlaybackState,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 8.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(42.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(state.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
            }
            IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, contentDescription = "Previous") }
            IconButton(onClick = onToggle) {
                Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play or pause")
            }
            IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, contentDescription = "Next") }
        }
    }
}

@Composable
private fun OverviewScreen(
    state: ArchiveUiState,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("MUSE / 03", color = MaterialTheme.colorScheme.primary)
                    Text(
                        "Order for your music.",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Every file inspected. Every record intentional.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(42.dp))
            }
        }
        item {
            HealthHero(score = state.averageHealth, count = state.tracks.size)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("Verified", state.verifiedCount, Modifier.weight(1f), Color(0xFFB7FF6A))
                MetricCard("Review", state.reviewCount, Modifier.weight(1f), Color(0xFFFFC66A))
                MetricCard("Duplicates", state.duplicateCount, Modifier.weight(1f), Color(0xFFFF766E))
            }
        }
        item {
            Button(onClick = onScan, enabled = !state.isScanning, modifier = Modifier.fillMaxWidth()) {
                if (state.isScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(if (state.tracks.isEmpty()) "Scan this device" else "Scan for changes")
            }
        }
        state.report?.let { report ->
            item {
                Text(
                    "Last scan found ${report.found} tracks · ${report.addedOrUpdated} changed",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
    }
}

@Composable
private fun HealthHero(score: Int, count: Int) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(86.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("$score", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
            }
            Column(Modifier.padding(start = 18.dp)) {
                Text("Metadata health", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("$count indexed tracks", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (score >= 90) "Pristine." else "The archive needs attention.", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun MetricCard(label: String, value: Int, modifier: Modifier, accent: Color) {
    Card(modifier = modifier, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(value.toString(), style = MaterialTheme.typography.headlineSmall, color = accent, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TrackListScreen(
    title: String,
    subtitle: String,
    tracks: List<TrackEntity>,
    onEdit: (TrackEntity) -> Unit,
    onPlay: (TrackEntity) -> Unit,
    onVerify: (TrackEntity) -> Unit,
    onTrash: (TrackEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
        }
        if (tracks.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text("Nothing here yet.", modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(tracks, key = TrackEntity::contentUri) { track ->
            TrackCard(track, onEdit, onPlay, onVerify, onTrash)
        }
    }
}

@Composable
private fun TrackCard(
    track: TrackEntity,
    onEdit: (TrackEntity) -> Unit,
    onPlay: (TrackEntity) -> Unit,
    onVerify: (TrackEntity) -> Unit,
    onTrash: (TrackEntity) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onPlay(track) }, shape = RoundedCornerShape(20.dp)) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(scoreColor(track.healthScore), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(track.healthScore.toString(), color = Color(0xFF10120F), fontWeight = FontWeight.Black)
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(track.title.ifBlank { track.displayName }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(
                    listOf(track.artist, track.album).filter(String::isNotBlank).joinToString(" · ").ifBlank { "Unknown record" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    track.status.name.replace('_', ' '),
                    style = MaterialTheme.typography.labelSmall,
                    color = scoreColor(track.healthScore),
                )
            }
            IconButton(onClick = { onPlay(track) }) { Icon(Icons.Default.PlayArrow, contentDescription = "Play") }
            IconButton(onClick = { onEdit(track) }) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
            if (track.status != ArchiveStatus.VERIFIED && track.healthScore >= 90) {
                IconButton(onClick = { onVerify(track) }) { Icon(Icons.Default.CheckCircle, contentDescription = "Verify") }
            }
            IconButton(onClick = { onTrash(track) }) { Icon(Icons.Default.DeleteOutline, contentDescription = "Suggest trash") }
        }
    }
}

@Composable
private fun MixesScreen(
    mixes: List<GeneratedPlaylist>,
    onPlay: (List<TrackEntity>) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Generated mixes", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Built locally from genre, album, title, and archive health.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
        items(mixes, key = { it.mood.name }) { mix ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable(enabled = mix.tracks.isNotEmpty()) { onPlay(mix.tracks) },
                shape = RoundedCornerShape(22.dp),
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(54.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.QueueMusic, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(mix.mood.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(mix.mood.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${mix.tracks.size} tracks", color = MaterialTheme.colorScheme.primary)
                    }
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play ${mix.mood.title}")
                }
            }
        }
    }
}

private fun scoreColor(score: Int): Color = when {
    score >= 90 -> Color(0xFFB7FF6A)
    score >= 70 -> Color(0xFFFFC66A)
    else -> Color(0xFFFF766E)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MetadataEditor(
    initial: TrackEntity,
    searchState: MetadataSearchState,
    onSearch: () -> Unit,
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
        title = { Text("Canonical metadata") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Text("Edit the archive record. The source file is not rewritten in this build.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item {
                    FilledTonalButton(onClick = onSearch, enabled = !searchState.isSearching, modifier = Modifier.fillMaxWidth()) {
                        if (searchState.isSearching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.AutoFixHigh, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(if (searchState.isSearching) "Searching MusicBrainz…" else "Find verified metadata online")
                    }
                }
                if (searchState.trackUri == initial.contentUri) {
                    searchState.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                    items(searchState.candidates, key = MetadataCandidate::recordingId) { candidate ->
                        MetadataCandidateCard(candidate = candidate, onApply = { onApply(candidate) })
                    }
                }
                item { OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(artist, { artist = it }, label = { Text("Primary artist") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(albumArtist, { albumArtist = it }, label = { Text("Album artist") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(album, { album = it }, label = { Text("Album") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(genre, { genre = it }, label = { Text("Genre") }, modifier = Modifier.fillMaxWidth()) }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(initial.copy(title = title, artist = artist, albumArtist = albumArtist, album = album, genre = genre))
            }) { Text("Save draft") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MetadataCandidateCard(candidate: MetadataCandidate, onApply: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(candidate.title, fontWeight = FontWeight.Bold)
                    Text(candidate.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        listOfNotNull(candidate.album.takeIf(String::isNotBlank), candidate.year?.toString()).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Text("${candidate.confidence}%", color = scoreColor(candidate.confidence), fontWeight = FontWeight.Bold)
            }
            candidate.durationDifferenceMs?.let {
                Text("Duration difference: ${it / 1_000}s", style = MaterialTheme.typography.labelSmall)
            }
            Button(onClick = onApply, modifier = Modifier.fillMaxWidth()) { Text("Apply suggestion") }
        }
    }
}
