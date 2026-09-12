package com.kashef.archive.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import com.kashef.archive.domain.MetadataQualityEvaluator
import com.kashef.archive.domain.MetadataSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

data class ScanReport(val found: Int, val addedOrUpdated: Int)

class MusicRepository(
    private val context: Context,
    private val dao: TrackDao,
    private val evaluator: MetadataQualityEvaluator,
) {
    fun observeTracks(): Flow<List<TrackEntity>> = dao.observeAll()

    suspend fun scanDevice(): ScanReport = withContext(Dispatchers.IO) {
        val existing = dao.getAllOnce().associateBy(TrackEntity::contentUri)
        val scanned = mutableListOf<TrackEntity>()
        val collection = if (android.os.Build.VERSION.SDK_INT >= 29) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.IS_MUSIC,
        )

        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val titleIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val yearIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val trackIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val uri = ContentUris.withAppendedId(collection, id)
                val prior = existing[uri.toString()]
                val modified = cursor.getLong(modifiedIndex)
                if (prior != null && prior.dateModifiedSeconds == modified) {
                    scanned += prior
                    continue
                }

                val embedded = extractEmbedded(uri)
                val displayName = cursor.getString(nameIndex).orEmpty()
                val title = embedded.title.ifBlank { cursor.getString(titleIndex).orEmpty() }
                val artist = embedded.artist.ifBlank { cursor.getString(artistIndex).orEmpty() }
                val album = embedded.album.ifBlank { cursor.getString(albumIndex).orEmpty() }
                val year = embedded.year ?: cursor.getIntOrNull(yearIndex)
                val trackNumber = embedded.trackNumber ?: cursor.getIntOrNull(trackIndex)?.let { it % 1000 }
                val duration = cursor.getLong(durationIndex)
                val snapshot = MetadataSnapshot(
                    displayName = displayName,
                    title = title,
                    artist = artist,
                    albumArtist = embedded.albumArtist,
                    album = album,
                    genre = embedded.genre,
                    year = year,
                    trackNumber = trackNumber,
                    durationMs = duration,
                    bitrate = embedded.bitrate,
                    hasArtwork = embedded.hasArtwork,
                )
                val quality = evaluator.evaluate(snapshot)
                val status = when {
                    duration <= 0 -> ArchiveStatus.CORRUPTED
                    quality.score < 45 -> ArchiveStatus.UNIDENTIFIED
                    quality.score < 75 -> ArchiveStatus.NEEDS_REVIEW
                    else -> ArchiveStatus.NEEDS_REVIEW
                }

                val discovered = TrackEntity(
                    contentUri = uri.toString(),
                    mediaStoreId = id,
                    displayName = displayName,
                    title = title,
                    artist = artist,
                    albumArtist = embedded.albumArtist,
                    album = album,
                    genre = embedded.genre,
                    year = year,
                    trackNumber = trackNumber,
                    discNumber = embedded.discNumber,
                    durationMs = duration,
                    sizeBytes = cursor.getLong(sizeIndex),
                    mimeType = cursor.getString(mimeIndex).orEmpty(),
                    bitrate = embedded.bitrate,
                    hasArtwork = embedded.hasArtwork,
                    dateModifiedSeconds = modified,
                    healthScore = quality.score,
                    issueCodes = quality.issues.joinToString("|") { it.name },
                    status = status,
                )
                scanned += if (prior?.isUserEdited == true) {
                    discovered.copy(
                        title = prior.title,
                        artist = prior.artist,
                        albumArtist = prior.albumArtist,
                        album = prior.album,
                        genre = prior.genre,
                        year = prior.year,
                        trackNumber = prior.trackNumber,
                        discNumber = prior.discNumber,
                        status = ArchiveStatus.NEEDS_REVIEW,
                        issueCodes = listOf(prior.issueCodes, "SOURCE_CHANGED_EXTERNALLY")
                            .filter(String::isNotBlank)
                            .joinToString("|"),
                        isUserEdited = true,
                    )
                } else discovered
            }
        }

        val indexed = markExactDuplicates(scanned)
        if (indexed.isNotEmpty()) {
            dao.upsertAll(indexed)
            dao.removeMissing(indexed.map(TrackEntity::contentUri))
        }
        ScanReport(found = indexed.size, addedOrUpdated = indexed.count { existing[it.contentUri] != it })
    }

    suspend fun edit(track: TrackEntity) {
        val quality = evaluator.evaluate(
            MetadataSnapshot(
                displayName = track.displayName,
                title = track.title,
                artist = track.artist,
                albumArtist = track.albumArtist,
                album = track.album,
                genre = track.genre,
                year = track.year,
                trackNumber = track.trackNumber,
                durationMs = track.durationMs,
                bitrate = track.bitrate,
                hasArtwork = track.hasArtwork,
            )
        )
        dao.updateCanonicalMetadata(
            uri = track.contentUri,
            title = track.title.trim(),
            artist = track.artist.trim(),
            albumArtist = track.albumArtist.trim(),
            album = track.album.trim(),
            genre = track.genre.trim(),
            year = track.year,
            trackNumber = track.trackNumber,
            discNumber = track.discNumber,
            healthScore = quality.score,
            issueCodes = quality.issues.joinToString("|") { it.name },
        )
    }

    suspend fun verify(uri: String) = dao.verify(uri, System.currentTimeMillis())
    suspend fun suggestTrash(uri: String) = dao.suggestTrash(uri)

    private fun markExactDuplicates(tracks: List<TrackEntity>): List<TrackEntity> {
        val duplicateUris = tracks
            .filter { it.sizeBytes > 0 && it.durationMs > 0 }
            .groupBy { "${it.sizeBytes}:${it.durationMs}:${it.title.trim().lowercase()}:${it.artist.trim().lowercase()}" }
            .values
            .filter { it.size > 1 }
            .flatMap { group ->
                group.sortedWith(
                    compareByDescending<TrackEntity> { it.status == ArchiveStatus.VERIFIED }
                        .thenByDescending { it.healthScore }
                        .thenBy { it.contentUri }
                ).drop(1)
            }
            .mapTo(hashSetOf(), TrackEntity::contentUri)

        return tracks.map { track ->
            if (track.contentUri in duplicateUris && track.status != ArchiveStatus.VERIFIED) {
                track.copy(
                    status = ArchiveStatus.DUPLICATE,
                    issueCodes = listOf(track.issueCodes, "EXACT_DUPLICATE")
                        .filter(String::isNotBlank)
                        .joinToString("|"),
                )
            } else track
        }
    }

    private fun extractEmbedded(uri: Uri): EmbeddedMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            EmbeddedMetadata(
                title = retriever.text(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.text(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                albumArtist = retriever.text(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                album = retriever.text(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                genre = retriever.text(MediaMetadataRetriever.METADATA_KEY_GENRE),
                year = retriever.text(MediaMetadataRetriever.METADATA_KEY_YEAR).toIntOrNull(),
                trackNumber = retriever.text(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    .substringBefore('/').toIntOrNull(),
                discNumber = retriever.text(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
                    .substringBefore('/').toIntOrNull(),
                bitrate = retriever.text(MediaMetadataRetriever.METADATA_KEY_BITRATE).toIntOrNull(),
                hasArtwork = retriever.embeddedPicture != null,
            )
        } catch (_: Exception) {
            EmbeddedMetadata()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun MediaMetadataRetriever.text(key: Int): String = extractMetadata(key).orEmpty().trim()
    private fun android.database.Cursor.getIntOrNull(index: Int): Int? =
        if (isNull(index)) null else getInt(index).takeIf { it != 0 }
}

private data class EmbeddedMetadata(
    val title: String = "",
    val artist: String = "",
    val albumArtist: String = "",
    val album: String = "",
    val genre: String = "",
    val year: Int? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val bitrate: Int? = null,
    val hasArtwork: Boolean = false,
)
