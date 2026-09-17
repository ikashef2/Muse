package com.kashef.archive.domain

import com.kashef.archive.data.TrackEntity

/**
 * Structured mood attributes with keyword inference.
 * Multiple moods per track are allowed; manual corrections always win.
 */
object MoodClassifier {
    /** Product-facing mood vocabulary (multi-label). */
    val ATTRIBUTES = listOf(
        "DARK",
        "AGGRESSIVE",
        "MELANCHOLIC",
        "ATMOSPHERIC",
        "ENERGETIC",
        "CALM",
        "EUPHORIC",
        "EPIC",
        "INTROSPECTIVE",
        "CHAOTIC",
    )

    /** Legacy mix buckets retained for playlist generation compatibility. */
    private val mixRules = linkedMapOf(
        "FOCUS" to listOf("ambient", "instrumental", "classical", "piano", "lofi", "lo-fi", "soundtrack", "atmospheric", "introspective"),
        "ENERGY" to listOf("rock", "metal", "electronic", "dance", "techno", "house", "hip hop", "hip-hop", "rap", "aggressive", "energetic", "chaotic", "djent", "thrash"),
        "CALM" to listOf("acoustic", "folk", "classical", "ambient", "chill", "soft", "piano", "calm", "melancholic"),
        "NIGHT" to listOf("jazz", "soul", "r&b", "trip hop", "trip-hop", "dark", "blues", "downtempo", "melancholic", "introspective"),
    )

    private val attributeRules = linkedMapOf(
        "DARK" to listOf("dark", "black metal", "doom", "gothic", "noir", "shadow"),
        "AGGRESSIVE" to listOf("thrash", "hardcore", "death metal", "grind", "metalcore", "aggressive", "brutal"),
        "MELANCHOLIC" to listOf("sad", "melanchol", "ballad", "minor", "sorrow", "lament"),
        "ATMOSPHERIC" to listOf("atmospheric", "ambient", "post metal", "post-rock", "shoegaze", "drone"),
        "ENERGETIC" to listOf("energy", "upbeat", "dance", "punk", "power metal", "speed"),
        "CALM" to listOf("calm", "acoustic", "chill", "soft", "lofi", "lo-fi", "piano"),
        "EUPHORIC" to listOf("euphoric", "uplifting", "trance", "anthem", "celebrat"),
        "EPIC" to listOf("epic", "symphonic", "orchestral", "cinematic", "soundtrack"),
        "INTROSPECTIVE" to listOf("introspect", "progressive", "concept", "meditat"),
        "CHAOTIC" to listOf("chaotic", "math", "technical death", "avant", "free jazz", "grind"),
    )

    fun infer(title: String, album: String, genre: String): Set<String> {
        val text = "$title $album $genre".lowercase()
        val mixes = mixRules.filterValues { words -> words.any(text::contains) }.keys
        val attributes = attributeRules.filterValues { words -> words.any(text::contains) }.keys
        return mixes + attributes
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
