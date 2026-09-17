package com.kashef.archive.playback

import android.content.Context
import org.json.JSONArray

/**
 * Persists the active queue so Muse can restore playback after process death.
 */
class PlaybackSessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Session(
        val mediaIds: List<String>,
        val startIndex: Int,
        val positionMs: Long,
        val shuffleEnabled: Boolean,
        val repeatMode: Int,
        val wasPlaying: Boolean,
        val contextLabel: String = "",
    )

    fun save(session: Session) {
        if (session.mediaIds.isEmpty()) {
            clear()
            return
        }
        prefs.edit()
            .putString(KEY_IDS, JSONArray(session.mediaIds).toString())
            .putInt(KEY_INDEX, session.startIndex)
            .putLong(KEY_POSITION, session.positionMs.coerceAtLeast(0L))
            .putBoolean(KEY_SHUFFLE, session.shuffleEnabled)
            .putInt(KEY_REPEAT, session.repeatMode)
            .putBoolean(KEY_WAS_PLAYING, session.wasPlaying)
            .putString(KEY_CONTEXT, session.contextLabel)
            .apply()
    }

    fun load(): Session? {
        val raw = prefs.getString(KEY_IDS, null) ?: return null
        val ids = buildList {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val id = array.optString(i)
                if (id.isNotBlank()) add(id)
            }
        }
        if (ids.isEmpty()) return null
        return Session(
            mediaIds = ids,
            startIndex = prefs.getInt(KEY_INDEX, 0).coerceIn(0, ids.lastIndex),
            positionMs = prefs.getLong(KEY_POSITION, 0L).coerceAtLeast(0L),
            shuffleEnabled = prefs.getBoolean(KEY_SHUFFLE, false),
            repeatMode = prefs.getInt(KEY_REPEAT, 0),
            wasPlaying = prefs.getBoolean(KEY_WAS_PLAYING, false),
            contextLabel = prefs.getString(KEY_CONTEXT, "").orEmpty(),
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS = "muse_playback_session"
        private const val KEY_IDS = "media_ids"
        private const val KEY_INDEX = "start_index"
        private const val KEY_POSITION = "position_ms"
        private const val KEY_SHUFFLE = "shuffle"
        private const val KEY_REPEAT = "repeat_mode"
        private const val KEY_WAS_PLAYING = "was_playing"
        private const val KEY_CONTEXT = "context_label"
    }
}
