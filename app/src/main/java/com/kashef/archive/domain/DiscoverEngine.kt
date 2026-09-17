package com.kashef.archive.domain

import com.kashef.archive.data.ArchiveStatus
import com.kashef.archive.data.TrackEntity

data class DiscoverSection(
    val id: String,
    val title: String,
    val subtitle: String,
    val tracks: List<TrackEntity>,
    val explanation: String = "",
)

data class ScoredRecommendation(
    val track: TrackEntity,
    val score: Double,
    val reason: String,
)

/**
 * Local-first discovery ranked from library signals only.
 * No fake remote catalogs — every row is a real track the user owns.
 */
class DiscoverEngine(
    private val familiarRatio: Double = 0.60,
    private val adjacentRatio: Double = 0.30,
) {
    fun build(
        tracks: List<TrackEntity>,
        topArtistNames: List<String> = emptyList(),
        limitPerSection: Int = 12,
    ): List<DiscoverSection> {
        val playable = tracks.filter(::isPlayable)
        if (playable.isEmpty()) return emptyList()

        val sections = mutableListOf<DiscoverSection>()
        val ranked = rank(playable, topArtistNames)
        val recommended = ranked.take(limitPerSection)
        if (recommended.isNotEmpty()) {
            sections += DiscoverSection(
                id = "recommended",
                title = "Recommended for You",
                subtitle = "Ranked from your library taste signals",
                tracks = recommended.map(ScoredRecommendation::track),
                explanation = recommended.first().reason,
            )
        }

        topArtistNames.take(3).forEach { artist ->
            val related = playable
                .filter { it.artist.equals(artist, ignoreCase = true) }
                .sortedByDescending { it.healthScore }
                .take(limitPerSection)
            if (related.isNotEmpty()) {
                sections += DiscoverSection(
                    id = "because_$artist",
                    title = "Because You Like $artist",
                    subtitle = "More from an artist already in your orbit",
                    tracks = related,
                    explanation = "Artist affinity · ${related.size} library matches",
                )
            }
        }

        val hiddenGems = playable
            .filter { it.healthScore >= 70 && it.status == ArchiveStatus.VERIFIED }
            .sortedBy { it.dateModifiedSeconds }
            .take(limitPerSection)
        if (hiddenGems.isNotEmpty()) {
            sections += DiscoverSection(
                id = "hidden_gems",
                title = "Hidden Gems",
                subtitle = "Strong metadata, quieter corners of your archive",
                tracks = hiddenGems,
            )
        }

        val genreBuckets = playable
            .filter { it.genre.isNotBlank() }
            .groupBy { normalizeGenre(it.genre) }
            .entries
            .sortedByDescending { it.value.size }
            .take(3)
        genreBuckets.forEach { (genre, genreTracks) ->
            sections += DiscoverSection(
                id = "genre_$genre",
                title = "Explore $genre",
                subtitle = "From your classified library",
                tracks = genreTracks.sortedByDescending { it.healthScore }.take(limitPerSection),
                explanation = "${genreTracks.size} tracks tagged $genre",
            )
        }

        val exploration = ranked
            .asReversed()
            .take((limitPerSection * explorationRatio()).toInt().coerceAtLeast(4))
            .map(ScoredRecommendation::track)
            .distinctBy(TrackEntity::contentUri)
        if (exploration.isNotEmpty()) {
            sections += DiscoverSection(
                id = "outside_comfort",
                title = "Outside Your Comfort Zone",
                subtitle = "Still in your library — just less familiar",
                tracks = exploration.take(limitPerSection),
            )
        }

        return sections
    }

    fun rank(tracks: List<TrackEntity>, topArtistNames: List<String>): List<ScoredRecommendation> {
        val topArtists = topArtistNames.map { it.lowercase() }.toSet()
        val genreAffinity = tracks
            .map { normalizeGenre(it.genre) }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
        val total = tracks.size.coerceAtLeast(1).toDouble()

        return tracks.map { track ->
            val genre = normalizeGenre(track.genre)
            val artistHit = track.artist.lowercase() in topArtists
            val genreScore = (genreAffinity[genre] ?: 0) / total
            val moodBonus = track.moods.size * 0.03
            val health = track.healthScore / 100.0
            val novelty = 1.0 - genreScore
            val score =
                (if (artistHit) 0.35 else 0.0) +
                    genreScore * 0.30 +
                    health * 0.20 +
                    moodBonus +
                    novelty * 0.10 * explorationRatio()
            val matchPct = ((score.coerceIn(0.0, 1.0)) * 100).toInt()
            val reason = buildString {
                append("$matchPct% Taste Match")
                if (genre.isNotBlank()) append(" · $genre")
                if (track.moods.isNotEmpty()) append(" · ${track.moods.take(3).joinToString(", ") { it.lowercase().replaceFirstChar(Char::uppercase) }}")
                if (artistHit) append(" · Similar to artists you replay")
            }
            ScoredRecommendation(track, score, reason)
        }.sortedByDescending { it.score }
    }

    private fun explorationRatio(): Double = (1.0 - familiarRatio - adjacentRatio).coerceAtLeast(0.05)

    private fun normalizeGenre(raw: String): String {
        val g = raw.trim()
        if (g.isBlank()) return ""
        val lower = g.lowercase()
        return when {
            "progressive death" in lower -> "Progressive Death Metal"
            "tech" in lower && "death" in lower -> "Technical Death Metal"
            "melodic death" in lower -> "Melodic Death Metal"
            "death metal" in lower -> "Death Metal"
            "black metal" in lower && "atmospheric" in lower -> "Atmospheric Black Metal"
            "black metal" in lower -> "Black Metal"
            "doom" in lower -> "Doom Metal"
            "thrash" in lower -> "Thrash Metal"
            "djent" in lower -> "Djent"
            "metalcore" in lower -> "Metalcore"
            "post-metal" in lower || "post metal" in lower -> "Post Metal"
            "progressive metal" in lower || "prog metal" in lower -> "Progressive Metal"
            "power metal" in lower -> "Power Metal"
            "symphonic metal" in lower -> "Symphonic Metal"
            "folk metal" in lower -> "Folk Metal"
            "sludge" in lower -> "Sludge"
            "heavy metal" in lower -> "Heavy Metal"
            lower == "metal" -> "Metal"
            else -> g.split(',', '/', '|').first().trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    private fun isPlayable(track: TrackEntity): Boolean =
        track.durationMs > 0 &&
            track.status != ArchiveStatus.CORRUPTED &&
            track.status != ArchiveStatus.TRASH_SUGGESTED
}
