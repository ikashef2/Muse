package com.kashef.archive.domain

import com.kashef.archive.data.ArchiveStatus
import com.kashef.archive.data.TrackEntity
import kotlin.math.absoluteValue

enum class PlaylistMood(val title: String, val subtitle: String) {
    FOCUS("Focus", "Quiet, instrumental, ambient, and precise"),
    ENERGY("Energy", "Fast, loud, electronic, rock, and workout-ready"),
    CALM("Calm", "Soft, acoustic, classical, and low-friction"),
    NIGHT("Night", "Dark, jazz, soul, trip-hop, and after-hours"),
    DISCOVERY("Discovery", "A clean shuffle across the whole archive"),
}

data class GeneratedPlaylist(
    val mood: PlaylistMood,
    val tracks: List<TrackEntity>,
)

class PlaylistGenerator {
    fun generate(tracks: List<TrackEntity>, mood: PlaylistMood, limit: Int = 50): GeneratedPlaylist {
        val playable = tracks.filter {
            it.status != ArchiveStatus.CORRUPTED &&
                it.status != ArchiveStatus.TRASH_SUGGESTED &&
                it.durationMs > 0
        }
        val ranked = playable.sortedWith(
            compareByDescending<TrackEntity> { score(it, mood) }
                .thenBy { stableOrder(it, mood) }
        )
        return GeneratedPlaylist(mood, ranked.take(limit))
    }

    private fun score(track: TrackEntity, mood: PlaylistMood): Int {
        if (mood == PlaylistMood.DISCOVERY) return track.healthScore / 10
        val haystack = "${track.genre} ${track.album} ${track.title}".lowercase()
        val keywords = when (mood) {
            PlaylistMood.FOCUS -> listOf("ambient", "instrumental", "classical", "piano", "lofi", "lo-fi", "soundtrack")
            PlaylistMood.ENERGY -> listOf("rock", "metal", "electronic", "dance", "techno", "house", "hip hop", "rap")
            PlaylistMood.CALM -> listOf("acoustic", "folk", "classical", "ambient", "chill", "soft", "piano")
            PlaylistMood.NIGHT -> listOf("jazz", "soul", "r&b", "trip hop", "dark", "blues", "downtempo")
            PlaylistMood.DISCOVERY -> emptyList()
        }
        return keywords.count(haystack::contains) * 100 + track.healthScore
    }

    private fun stableOrder(track: TrackEntity, mood: PlaylistMood): Int =
        "${mood.name}:${track.contentUri}".hashCode().absoluteValue
}
