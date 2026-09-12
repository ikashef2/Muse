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
        val eligible = if (mood == PlaylistMood.DISCOVERY) {
            playable
        } else {
            playable.filter { mood.name in it.moods }
        }
        val ranked = eligible.sortedWith(
            compareByDescending<TrackEntity> { score(it, mood) }
                .thenBy { stableOrder(it, mood) }
        )
        return GeneratedPlaylist(mood, ranked.take(limit))
    }

    private fun score(track: TrackEntity, mood: PlaylistMood): Int {
        if (mood == PlaylistMood.DISCOVERY) return track.healthScore / 10
        val correctedMoodMatch = mood.name in track.moods
        val wasExplicitlyCorrected = track.manualMoodTags.split('|').any { it.removePrefix("!").equals(mood.name, true) }
        return (if (correctedMoodMatch) 1_000 else 0) +
            (if (correctedMoodMatch && wasExplicitlyCorrected) 1_000 else 0) +
            track.healthScore
    }

    private fun stableOrder(track: TrackEntity, mood: PlaylistMood): Int =
        "${mood.name}:${track.contentUri}".hashCode().absoluteValue
}
