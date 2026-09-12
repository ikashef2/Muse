package com.kashef.archive.domain

import com.kashef.archive.data.TrackEntity

object MoodClassifier {
    private val rules = linkedMapOf(
        "FOCUS" to listOf("ambient", "instrumental", "classical", "piano", "lofi", "lo-fi", "soundtrack"),
        "ENERGY" to listOf("rock", "metal", "electronic", "dance", "techno", "house", "hip hop", "hip-hop", "rap"),
        "CALM" to listOf("acoustic", "folk", "classical", "ambient", "chill", "soft", "piano"),
        "NIGHT" to listOf("jazz", "soul", "r&b", "trip hop", "trip-hop", "dark", "blues", "downtempo"),
    )

    fun infer(title: String, album: String, genre: String): Set<String> {
        val text = "$title $album $genre".lowercase()
        return rules.filterValues { words -> words.any(text::contains) }.keys
    }

    fun effective(track: TrackEntity): Set<String> {
        val result = track.inferredMoodTags.tags().toMutableSet()
        track.manualMoodTags.tags(keepNegation = true).forEach { correction ->
            if (correction.startsWith("!")) result -= correction.drop(1) else result += correction
        }
        return result
    }

    fun toggle(track: TrackEntity, mood: String): String {
        val normalized = mood.uppercase()
        val corrections = track.manualMoodTags.tags(keepNegation = true).toMutableSet()
        corrections.remove(normalized)
        corrections.remove("!$normalized")
        if (normalized in effective(track)) corrections += "!$normalized" else corrections += normalized
        return corrections.sorted().joinToString("|")
    }

    private fun String.tags(keepNegation: Boolean = false): Set<String> = split('|')
        .map(String::trim)
        .filter(String::isNotBlank)
        .map(String::uppercase)
        .filter { keepNegation || !it.startsWith("!") }
        .toSet()
}
