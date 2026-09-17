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
            playable.filter { matchesMood(it, mood) }
        }
        val ranked = eligible.sortedWith(
            compareByDescending<TrackEntity> { score(it, mood) }
                .thenBy { stableOrder(it, mood) },
        )
        return GeneratedPlaylist(mood, ranked.take(limit))
    }

    private fun matchesMood(track: TrackEntity, mood: PlaylistMood): Boolean {
        if (mood.name in track.moods) return true
        return track.moods.any { it in relatedAttributes(mood) }
    }

    private fun relatedAttributes(mood: PlaylistMood): Set<String> = when (mood) {
        PlaylistMood.FOCUS -> setOf("ATMOSPHERIC", "INTROSPECTIVE", "CALM")
        PlaylistMood.ENERGY -> setOf("ENERGETIC", "AGGRESSIVE", "CHAOTIC", "EUPHORIC", "EPIC")
        PlaylistMood.CALM -> setOf("CALM", "MELANCHOLIC", "ATMOSPHERIC")
        PlaylistMood.NIGHT -> setOf("DARK", "MELANCHOLIC", "INTROSPECTIVE")
        PlaylistMood.DISCOVERY -> emptySet()
    }

    private fun score(track: TrackEntity, mood: PlaylistMood): Int {
        if (mood == PlaylistMood.DISCOVERY) return track.healthScore / 10
        val correctedMoodMatch = matchesMood(track, mood)
        val wasExplicitlyCorrected = track.manualMoodTags.split('|').any {
            val tag = it.removePrefix("!").uppercase()
            tag == mood.name || tag in relatedAttributes(mood)
        }
        return (if (correctedMoodMatch) 1_000 else 0) +
            (if (correctedMoodMatch && wasExplicitlyCorrected) 1_000 else 0) +
            track.healthScore
    }

    private fun stableOrder(track: TrackEntity, mood: PlaylistMood): Int =
        "${mood.name}:${track.contentUri}".hashCode().absoluteValue
}
