package com.kashef.archive.source

import com.kashef.archive.data.TrackEntity

/**
 * Capability flags for a playback/metadata provider.
 * Not every source needs every capability.
 */
data class MusicSourceCapabilities(
    val search: Boolean = false,
    val stream: Boolean = false,
    val resolveTrack: Boolean = false,
    val fetchMetadata: Boolean = false,
    val fetchArtwork: Boolean = false,
    val localLibrary: Boolean = false,
)

/**
 * Abstraction over local files and future online providers.
 * Track identity remains canonical in Room; sources only supply playback and enrichment.
 */
interface MusicSource {
    val id: String
    val displayName: String
    val capabilities: MusicSourceCapabilities

    suspend fun search(query: String, limit: Int = 40): List<TrackEntity> = emptyList()
    suspend fun resolve(contentUri: String): TrackEntity? = null
}

class LocalMusicSource(
    private val searchTracks: suspend (String, Int) -> List<TrackEntity>,
    private val resolveTrack: suspend (String) -> TrackEntity?,
) : MusicSource {
    override val id: String = "local"
    override val displayName: String = "Local library"
    override val capabilities = MusicSourceCapabilities(
        search = true,
        stream = true,
        resolveTrack = true,
        fetchMetadata = true,
        fetchArtwork = true,
        localLibrary = true,
    )

    override suspend fun search(query: String, limit: Int): List<TrackEntity> =
        searchTracks(query, limit)

    override suspend fun resolve(contentUri: String): TrackEntity? =
        resolveTrack(contentUri)
}

class MusicSourceRegistry(vararg sources: MusicSource) {
    private val byId = sources.associateBy(MusicSource::id)
    val all: List<MusicSource> = sources.toList()

    fun get(id: String): MusicSource? = byId[id]
    fun local(): MusicSource? = byId["local"]
}
