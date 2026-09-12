package com.kashef.archive.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.json.JSONObject
import java.io.File

data class PreparedTagWrite(
    val contentUri: String,
    val backupPath: String,
    val editedPath: String,
    val verificationPath: String,
    val expected: TrackEntity,
)

class MetadataTagWriter(private val context: Context) {
    private val supportedExtensions = setOf("mp3", "flac", "m4a", "mp4", "ogg", "opus")

    fun supports(track: TrackEntity): Boolean = track.displayName.substringAfterLast('.', "").lowercase() in supportedExtensions

    suspend fun prepare(track: TrackEntity): PreparedTagWrite = withContext(Dispatchers.IO) {
        require(supports(track)) { "Embedded tag writing is not supported for this audio format." }
        val extension = track.displayName.substringAfterLast('.').lowercase()
        val token = track.contentUri.hashCode().toUInt().toString(16)
        val folder = File(context.cacheDir, "muse-tag-writes").apply { mkdirs() }
        val backup = File(folder, "$token-original.$extension")
        val edited = File(folder, "$token-edited.$extension")
        val verification = File(folder, "$token-verify.$extension")
        val uri = Uri.parse(track.contentUri)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Muse could not read the original audio file." }
            backup.outputStream().use { output -> input.copyTo(output) }
        }
        backup.copyTo(edited, overwrite = true)
        try {
            val audio = AudioFileIO.read(edited)
            val tag = audio.tagOrCreateAndSetDefault
            tag.setField(FieldKey.TITLE, track.title)
            tag.setField(FieldKey.ARTIST, track.artist)
            tag.setField(FieldKey.ALBUM_ARTIST, track.albumArtist)
            tag.setField(FieldKey.ALBUM, track.album)
            tag.setField(FieldKey.GENRE, track.genre)
            track.year?.let { tag.setField(FieldKey.YEAR, it.toString()) }
            track.trackNumber?.let { tag.setField(FieldKey.TRACK, it.toString()) }
            track.discNumber?.let { tag.setField(FieldKey.DISC_NO, it.toString()) }
            audio.commit()
            validate(edited, track)
            persistTagBackup(track, backup.length())
            PreparedTagWrite(track.contentUri, backup.path, edited.path, verification.path, track)
        } catch (error: Exception) {
            backup.delete()
            edited.delete()
            verification.delete()
            throw error
        }
    }

    suspend fun commit(prepared: PreparedTagWrite) = withContext(Dispatchers.IO) {
        val uri = Uri.parse(prepared.contentUri)
        val backup = File(prepared.backupPath)
        val edited = File(prepared.editedPath)
        val verification = File(prepared.verificationPath)
        require(backup.exists() && edited.exists()) { "The prepared rewrite expired. Please identify the track again." }
        try {
            writeFile(uri, edited)
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Muse could not verify the rewritten file." }
                verification.outputStream().use { output -> input.copyTo(output) }
            }
            validate(verification, prepared.expected)
        } catch (writeError: Exception) {
            runCatching { writeFile(uri, backup) }.getOrElse { restoreError ->
                throw IllegalStateException(
                    "The metadata rewrite failed and Muse could not restore the original automatically.",
                    restoreError,
                )
            }
            throw IllegalStateException("The rewrite failed; the original audio was restored.", writeError)
        } finally {
            backup.delete()
            edited.delete()
            verification.delete()
        }
    }

    fun cancel(prepared: PreparedTagWrite) {
        File(prepared.backupPath).delete()
        File(prepared.editedPath).delete()
        File(prepared.verificationPath).delete()
    }

    private fun writeFile(uri: Uri, source: File) {
        context.contentResolver.openOutputStream(uri, "rwt").use { output ->
            requireNotNull(output) { "Android did not grant write access to this track." }
            source.inputStream().use { it.copyTo(output) }
        }
    }

    private fun validate(file: File, expected: TrackEntity) {
        val tag = AudioFileIO.read(file).tag ?: error("The rewritten file has no readable metadata.")
        check(tag.getFirst(FieldKey.TITLE).trim() == expected.title.trim()) { "Title validation failed." }
        check(tag.getFirst(FieldKey.ARTIST).trim() == expected.artist.trim()) { "Artist validation failed." }
        check(tag.getFirst(FieldKey.ALBUM).trim() == expected.album.trim()) { "Album validation failed." }
    }

    private fun persistTagBackup(track: TrackEntity, originalSize: Long) {
        val folder = File(context.filesDir, "metadata-backups").apply { mkdirs() }
        val token = track.contentUri.hashCode().toUInt().toString(16)
        val json = JSONObject()
            .put("contentUri", track.contentUri)
            .put("createdAt", System.currentTimeMillis())
            .put("originalSize", originalSize)
            .put("title", track.originalTitle.ifBlank { track.title })
            .put("artist", track.originalArtist.ifBlank { track.artist })
            .put("albumArtist", track.originalAlbumArtist.ifBlank { track.albumArtist })
            .put("album", track.originalAlbum.ifBlank { track.album })
            .put("genre", track.genre)
            .put("year", track.year)
            .put("trackNumber", track.trackNumber)
            .put("discNumber", track.discNumber)
        File(folder, "$token.json").writeText(json.toString(2))
    }
}
