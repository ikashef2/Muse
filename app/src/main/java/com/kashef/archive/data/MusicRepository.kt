package com.kashef.archive.data

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import com.kashef.archive.domain.MetadataQualityEvaluator
import com.kashef.archive.domain.MetadataSnapshot
import com.kashef.archive.domain.LatinMetadataNormalizer
import com.kashef.archive.domain.MoodClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

data class ScanReport(val found: Int, val addedOrUpdated: Int)

enum class IdentificationPhase {
    READING_TAGS,
    FINGERPRINTING,
    ACOUSTID,
    CATALOGS,
}

data class PreparedMetadataChange(
    val original: TrackEntity,
    val updated: TrackEntity,
    val tagWrite: PreparedTagWrite?,
) {
    val contentUri: String get() = updated.contentUri
}

class MusicRepository(
    private val context: Context,
    private val dao: TrackDao,
    private val evaluator: MetadataQualityEvaluator,
    private val musicBrainz: MusicBrainzClient,
    private val appleCatalog: AppleCatalogClient,
    private val acoustId: AcoustIdClient,
    private val fingerprintEngine: ChromaprintEngine,
    private val tagWriter: MetadataTagWriter,
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
        val projection = mutableListOf(
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
            MediaStore.Audio.Media.ALBUM_ID,
        )
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            projection += MediaStore.Audio.AudioColumns.ALBUM_ARTIST
            projection += MediaStore.Audio.AudioColumns.GENRE
            projection += MediaStore.MediaColumns.BITRATE
        }

        context.contentResolver.query(
            collection,
            projection.toTypedArray(),
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
            val albumIdIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val albumArtistIndex = cursor.getColumnIndex(MediaStore.Audio.AudioColumns.ALBUM_ARTIST)
            val genreIndex = cursor.getColumnIndex(MediaStore.Audio.AudioColumns.GENRE)
            val bitrateIndex = cursor.getColumnIndex(MediaStore.MediaColumns.BITRATE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val uri = ContentUris.withAppendedId(collection, id)
                val prior = existing[uri.toString()]
                val modified = cursor.getLong(modifiedIndex)
                if (prior != null && prior.dateModifiedSeconds == modified) {
                    scanned += prior
                    continue
                }

                val displayName = cursor.getString(nameIndex).orEmpty()
                val rawTitle = cursor.getString(titleIndex).orEmpty()
                val rawArtist = cursor.getString(artistIndex).orEmpty()
                val rawAlbumArtist = cursor.getStringOrEmpty(albumArtistIndex)
                val rawAlbum = cursor.getString(albumIndex).orEmpty()
                val rawGenre = cursor.getStringOrEmpty(genreIndex)
                val title = LatinMetadataNormalizer.canonicalize(rawTitle).latin
                val artist = LatinMetadataNormalizer.canonicalize(rawArtist).latin
                val albumArtist = LatinMetadataNormalizer.canonicalize(rawAlbumArtist).latin
                val album = LatinMetadataNormalizer.canonicalize(rawAlbum).latin
                val year = cursor.getIntOrNull(yearIndex)
                val trackNumber = cursor.getIntOrNull(trackIndex)?.let { it % 1000 }
                val duration = cursor.getLong(durationIndex)
                val bitrate = cursor.getIntOrNull(bitrateIndex) ?: prior?.bitrate
                val albumId = cursor.getLong(albumIdIndex)
                val hasArtwork = when {
                    prior?.hasArtwork == true -> true
                    albumId > 0L -> hasAlbumArtwork(albumId)
                    else -> false
                }
                val snapshot = MetadataSnapshot(
                    displayName = displayName,
                    title = title,
                    artist = artist,
                    albumArtist = albumArtist,
                    album = album,
                    genre = rawGenre,
                    year = year,
                    trackNumber = trackNumber,
                    durationMs = duration,
                    bitrate = bitrate,
                    hasArtwork = hasArtwork,
                )
                val quality = evaluator.evaluate(snapshot)
                val status = resolveScanStatus(duration, quality.score, prior)

                val discovered = TrackEntity(
                    contentUri = uri.toString(),
                    mediaStoreId = id,
                    displayName = displayName,
                    title = title,
                    artist = artist,
                    albumArtist = albumArtist,
                    album = album,
                    genre = rawGenre,
                    year = year,
                    trackNumber = trackNumber,
                    discNumber = prior?.discNumber,
                    durationMs = duration,
                    sizeBytes = cursor.getLong(sizeIndex),
                    mimeType = cursor.getString(mimeIndex).orEmpty(),
                    bitrate = bitrate,
                    hasArtwork = hasArtwork,
                    dateModifiedSeconds = modified,
                    healthScore = quality.score,
                    issueCodes = quality.issues.joinToString("|") { it.name },
                    status = status,
                    originalTitle = rawTitle.takeIf { it != title }.orEmpty(),
                    originalArtist = rawArtist.takeIf { it != artist }.orEmpty(),
                    originalAlbumArtist = rawAlbumArtist.takeIf { it != albumArtist }.orEmpty(),
                    originalAlbum = rawAlbum.takeIf { it != album }.orEmpty(),
                    inferredMoodTags = MoodClassifier.infer(title, album, rawGenre).joinToString("|"),
                    verifiedAtMillis = if (status == ArchiveStatus.VERIFIED) {
                        prior?.verifiedAtMillis ?: System.currentTimeMillis()
                    } else {
                        null
                    },
                )
                val enriched = prior?.let {
                    discovered.copy(
                        originalTitle = it.originalTitle.ifBlank { discovered.originalTitle },
                        originalArtist = it.originalArtist.ifBlank { discovered.originalArtist },
                        originalAlbumArtist = it.originalAlbumArtist.ifBlank { discovered.originalAlbumArtist },
                        originalAlbum = it.originalAlbum.ifBlank { discovered.originalAlbum },
                        manualMoodTags = it.manualMoodTags,
                        fingerprint = it.fingerprint,
                        acoustId = it.acoustId,
                        musicBrainzRecordingId = it.musicBrainzRecordingId,
                        matchConfidence = it.matchConfidence,
                        matchSource = it.matchSource,
                    )
                } ?: discovered
                scanned += if (prior?.isUserEdited == true) {
                    enriched.copy(
                        title = prior.title,
                        artist = prior.artist,
                        albumArtist = prior.albumArtist,
                        album = prior.album,
                        genre = prior.genre,
                        year = prior.year,
                        trackNumber = prior.trackNumber,
                        discNumber = prior.discNumber,
                        // Keep verified curator records verified; otherwise force review.
                        status = if (prior.status == ArchiveStatus.VERIFIED) {
                            ArchiveStatus.VERIFIED
                        } else {
                            ArchiveStatus.NEEDS_REVIEW
                        },
                        verifiedAtMillis = if (prior.status == ArchiveStatus.VERIFIED) {
                            prior.verifiedAtMillis ?: System.currentTimeMillis()
                        } else {
                            null
                        },
                        issueCodes = listOf(prior.issueCodes, "SOURCE_CHANGED_EXTERNALLY")
                            .filter(String::isNotBlank)
                            .joinToString("|"),
                        isUserEdited = true,
                    )
                } else enriched
            }
        }

        val indexed = markExactDuplicates(scanned)
        if (indexed.isNotEmpty()) {
            dao.upsertAll(indexed)
            syncRemovedTracks(existing.keys, indexed.map(TrackEntity::contentUri))
        }
        ScanReport(found = indexed.size, addedOrUpdated = indexed.count { existing[it.contentUri] != it })
    }

    private fun resolveScanStatus(durationMs: Long, score: Int, prior: TrackEntity?): ArchiveStatus = when {
        durationMs <= 0 -> ArchiveStatus.CORRUPTED
        score < 45 -> ArchiveStatus.UNIDENTIFIED
        score < 75 -> ArchiveStatus.LOW_QUALITY
        prior?.status == ArchiveStatus.VERIFIED && score >= 90 -> ArchiveStatus.VERIFIED
        else -> ArchiveStatus.NEEDS_REVIEW
    }

    private fun hasAlbumArtwork(albumId: Long): Boolean {
        val artUri = ContentUris.withAppendedId(ALBUM_ART_URI, albumId)
        return runCatching {
            context.contentResolver.openInputStream(artUri)?.use { true } ?: false
        }.getOrDefault(false)
    }

    private suspend fun syncRemovedTracks(previousUris: Set<String>, activeUris: List<String>) {
        val active = activeUris.toHashSet()
        val stale = previousUris.filterNot(active::contains)
        if (stale.isEmpty()) return
        // Chunk deletes to stay under SQLite variable limits on large libraries.
        stale.chunked(400).forEach { chunk -> dao.deleteUris(chunk) }
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
    suspend fun setManualMoodTags(uri: String, tags: String) = dao.updateManualMoodTags(uri, tags)

    suspend fun searchMetadata(
        track: TrackEntity,
        onPhase: (IdentificationPhase) -> Unit = {},
    ): List<MetadataCandidate> {
        onPhase(IdentificationPhase.READING_TAGS)
        val inspected = inspectTrack(track)
        if (!acoustId.isConfigured) {
            onPhase(IdentificationPhase.CATALOGS)
            return searchCatalogs(inspected)
        }
        onPhase(IdentificationPhase.FINGERPRINTING)
        val fingerprint = runCatching {
            inspected.fingerprint.takeIf(String::isNotBlank)?.let {
                AudioFingerprint(it, (inspected.durationMs / 1_000L).toInt())
            } ?: fingerprintEngine.fingerprint(Uri.parse(inspected.contentUri))
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            onPhase(IdentificationPhase.CATALOGS)
            return searchCatalogs(inspected)
        }

        onPhase(IdentificationPhase.ACOUSTID)
        val bestMatch = try {
            acoustId.lookup(fingerprint, inspected.durationMs).firstOrNull()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        if (bestMatch == null) {
            onPhase(IdentificationPhase.CATALOGS)
            return searchCatalogs(inspected)
        }
        val candidates = try {
            musicBrainz.lookupByRecordingIds(
                recordingIds = bestMatch.recordingIds,
                localDurationMs = inspected.durationMs,
                acoustId = bestMatch.acoustId,
                acoustConfidence = bestMatch.score,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
        val top = candidates.firstOrNull()
        dao.updateIdentification(
            uri = inspected.contentUri,
            fingerprint = fingerprint.encoded,
            acoustId = bestMatch.acoustId,
            recordingId = top?.recordingId.orEmpty(),
            confidence = top?.confidence ?: bestMatch.score,
            source = "ACOUSTID_FINGERPRINT",
        )
        if (candidates.isNotEmpty()) return candidates
        onPhase(IdentificationPhase.CATALOGS)
        return searchCatalogs(inspected)
    }

    private suspend fun searchCatalogs(track: TrackEntity): List<MetadataCandidate> = coroutineScope {
        val musicBrainzJob = async {
            try {
                musicBrainz.search(track)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }
        }
        val appleJob = async {
            try {
                appleCatalog.search(track)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }
        }
        val musicBrainzMatches = musicBrainzJob.await()
        val appleMatches = appleJob.await()
        (musicBrainzMatches + appleMatches)
            .distinctBy { "${it.title.lowercase()}|${it.artist.lowercase()}|${it.album.lowercase()}" }
            .sortedByDescending(MetadataCandidate::confidence)
            .take(10)
    }

    /** Performs the expensive container inspection only for the song being reviewed. */
    private suspend fun inspectTrack(track: TrackEntity): TrackEntity = withContext(Dispatchers.IO) {
        val embedded = extractEmbedded(Uri.parse(track.contentUri))
        val rawTitle = embedded.title.ifBlank { track.title }
        val rawArtist = embedded.artist.ifBlank { track.artist }
        val rawAlbumArtist = embedded.albumArtist.ifBlank { track.albumArtist }
        val rawAlbum = embedded.album.ifBlank { track.album }
        val title = LatinMetadataNormalizer.canonicalize(rawTitle).latin
        val artist = LatinMetadataNormalizer.canonicalize(rawArtist).latin
        val albumArtist = LatinMetadataNormalizer.canonicalize(rawAlbumArtist).latin
        val album = LatinMetadataNormalizer.canonicalize(rawAlbum).latin
        val inspected = if (track.isUserEdited) {
            track.copy(
                bitrate = embedded.bitrate ?: track.bitrate,
                hasArtwork = embedded.hasArtwork,
            )
        } else {
            track.copy(
                title = title,
                artist = artist,
                albumArtist = albumArtist,
                album = album,
                genre = embedded.genre.ifBlank { track.genre },
                year = embedded.year ?: track.year,
                trackNumber = embedded.trackNumber ?: track.trackNumber,
                discNumber = embedded.discNumber ?: track.discNumber,
                bitrate = embedded.bitrate ?: track.bitrate,
                hasArtwork = embedded.hasArtwork,
                originalTitle = rawTitle.takeIf { it != title }.orEmpty().ifBlank { track.originalTitle },
                originalArtist = rawArtist.takeIf { it != artist }.orEmpty().ifBlank { track.originalArtist },
                originalAlbumArtist = rawAlbumArtist.takeIf { it != albumArtist }.orEmpty().ifBlank { track.originalAlbumArtist },
                originalAlbum = rawAlbum.takeIf { it != album }.orEmpty().ifBlank { track.originalAlbum },
            )
        }
        val quality = evaluator.evaluate(inspected.toMetadataSnapshot())
        val updated = inspected.copy(
            healthScore = quality.score,
            issueCodes = quality.issues.joinToString("|") { it.name },
            inferredMoodTags = MoodClassifier.infer(inspected.title, inspected.album, inspected.genre).joinToString("|"),
        )
        dao.upsertAll(listOf(updated))
        updated
    }

    suspend fun prepareMetadataCandidate(track: TrackEntity, candidate: MetadataCandidate): PreparedMetadataChange {
        val updated = track.copy(
                title = candidate.title,
                artist = candidate.artist.ifBlank { track.artist },
                albumArtist = candidate.albumArtist.ifBlank { candidate.artist.ifBlank { track.albumArtist } },
                album = candidate.album.ifBlank { track.album },
                genre = candidate.genre.ifBlank { track.genre },
                year = candidate.year ?: track.year,
                acoustId = candidate.acoustId.ifBlank { track.acoustId },
                musicBrainzRecordingId = candidate.recordingId.takeUnless { it.startsWith("apple:") }.orEmpty(),
                matchConfidence = candidate.confidence,
                matchSource = when {
                    candidate.acoustId.isNotBlank() -> "ACOUSTID_FINGERPRINT"
                    candidate.recordingId.startsWith("apple:") -> "APPLE_CATALOG"
                    else -> "MUSICBRAINZ_TEXT"
                },
            )
        return prepareMetadataEdit(track, updated)
    }

    suspend fun prepareMetadataEdit(original: TrackEntity, updated: TrackEntity): PreparedMetadataChange {
        val canonical = updated.copy(
            title = LatinMetadataNormalizer.canonicalize(updated.title).latin,
            artist = LatinMetadataNormalizer.canonicalize(updated.artist).latin,
            albumArtist = LatinMetadataNormalizer.canonicalize(updated.albumArtist).latin,
            album = LatinMetadataNormalizer.canonicalize(updated.album).latin,
        )
        val write = if (tagWriter.supports(canonical)) tagWriter.prepare(canonical) else null
        return PreparedMetadataChange(original, canonical, write)
    }

    suspend fun commitMetadataChange(prepared: PreparedMetadataChange) {
        prepared.tagWrite?.let { tagWriter.commit(it) }
        edit(prepared.updated)
    }

    fun cancelMetadataChange(prepared: PreparedMetadataChange) {
        prepared.tagWrite?.let(tagWriter::cancel)
    }

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
        if (index < 0 || isNull(index)) null else getInt(index).takeIf { it != 0 }
    private fun android.database.Cursor.getStringOrEmpty(index: Int): String =
        if (index < 0 || isNull(index)) "" else getString(index).orEmpty()
}

private fun TrackEntity.toMetadataSnapshot() = MetadataSnapshot(
    displayName = displayName,
    title = title,
    artist = artist,
    albumArtist = albumArtist,
    album = album,
    genre = genre,
    year = year,
    trackNumber = trackNumber,
    durationMs = durationMs,
    bitrate = bitrate,
    hasArtwork = hasArtwork,
)

private val ALBUM_ART_URI: Uri = Uri.parse("content://media/external/audio/albumart")

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
