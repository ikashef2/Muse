package com.kashef.archive.domain

import com.kashef.archive.data.ArchiveStatus
import com.kashef.archive.data.ListeningEventEntity
import com.kashef.archive.data.TrackEntity
import com.kashef.archive.data.TrackPlayAggregate

data class HomeSection(
    val id: String,
    val title: String,
    val subtitle: String,
    val tracks: List<TrackEntity>,
)

/**
 * Builds Home dashboard sections only from available local signals.
 * Empty sections are omitted so the page stays alive without placeholders.
 */
class HomeFeedBuilder {
    fun build(
        tracks: List<TrackEntity>,
        continueTrack: TrackEntity?,
        recentEvents: List<ListeningEventEntity>,
        heavyRotation: List<TrackPlayAggregate>,
        limitPerSection: Int = 12,
    ): List<HomeSection> {
        val playable = tracks.filter(::isPlayable)
        if (playable.isEmpty()) return emptyList()

        val byUri = playable.associateBy(TrackEntity::contentUri)
        val sections = mutableListOf<HomeSection>()

        continueTrack?.takeIf(::isPlayable)?.let { track ->
            sections += HomeSection(
                id = "continue",
                title = "Continue Listening",
                subtitle = "Pick up where you left off",
                tracks = listOf(track),
            )
        }

        val recentlyPlayed = recentEvents
            .mapNotNull { byUri[it.trackUri] }
            .distinctBy(TrackEntity::contentUri)
            .take(limitPerSection)
        if (recentlyPlayed.isNotEmpty()) {
            sections += HomeSection(
                id = "recently_played",
                title = "Recently Played",
                subtitle = "Your latest sessions",
                tracks = recentlyPlayed,
            )
        }

        val heavy = heavyRotation
            .mapNotNull { byUri[it.trackUri] }
            .take(limitPerSection)
        if (heavy.isNotEmpty()) {
            sections += HomeSection(
                id = "heavy_rotation",
                title = "Your Heavy Rotation",
                subtitle = "Tracks you keep coming back to",
                tracks = heavy,
            )
        }

        val recentlyAdded = playable
            .sortedByDescending { it.dateModifiedSeconds }
            .take(limitPerSection)
        if (recentlyAdded.isNotEmpty()) {
            sections += HomeSection(
                id = "recently_added",
                title = "Recently Added",
                subtitle = "Fresh arrivals in your library",
                tracks = recentlyAdded,
            )
        }

        val verified = playable
            .filter { it.status == ArchiveStatus.VERIFIED }
            .sortedByDescending { it.healthScore }
            .take(limitPerSection)
        if (verified.isNotEmpty()) {
            sections += HomeSection(
                id = "made_for_you",
                title = "Made for You",
                subtitle = "Clean, verified archive picks",
                tracks = verified,
            )
        }

        return sections
    }

    private fun isPlayable(track: TrackEntity): Boolean =
        track.durationMs > 0 &&
            track.status != ArchiveStatus.CORRUPTED &&
            track.status != ArchiveStatus.TRASH_SUGGESTED
}
