package com.kashef.archive.playback

import android.content.Context
import org.json.JSONArray

data class PersistedPlaybackSession(
    val queueUris: List<String>,
    val startIndex: Int,
    val positionMs: Long,
    val shuffleEnabled: Boolean,
    val repeatMode: Int,
)

/**
 * Lightweight local persistence for the active queue so Muse can resume
 * after process death without requiring a full Room schema for every MediaItem.
 */
class PlaybackSessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(session: PersistedPlaybackSession) {
        if (session.queueUris.isEmpty()) {
            clear()
            return
        }
        prefs.edit()
            .putString(KEY_QUEUE, JSONArray(session.queueUris).toString())
            .putInt(KEY_INDEX, session.startIndex.coerceAtLeast(0))
            .putLong(KEY_POSITION, session.positionMs.coerceAtLeast(0L))
            .putBoolean(KEY_SHUFFLE, session.shuffleEnabled)
            .putInt(KEY_REPEAT, session.repeatMode)
            .apply()
    }

    fun load(): PersistedPlaybackSession? {
        val raw = prefs.getString(KEY_QUEUE, null) ?: return null
        val uris = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) add(array.getString(i))
            }
        }.getOrNull().orEmpty()
        if (uris.isEmpty()) return null
        return PersistedPlaybackSession(
            queueUris = uris,
            startIndex = prefs.getInt(KEY_INDEX, 0).coerceIn(0, uris.lastIndex),
            positionMs = prefs.getLong(KEY_POSITION, 0L).coerceAtLeast(0L),
            shuffleEnabled = prefs.getBoolean(KEY_SHUFFLE, false),
            repeatMode = prefs.getInt(KEY_REPEAT, 0),
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS = "muse_playback_session"
        private const val KEY_QUEUE = "queue"
        private const val KEY_INDEX = "index"
        private const val KEY_POSITION = "position"
        private const val KEY_SHUFFLE = "shuffle"
        private const val KEY_REPEAT = "repeat"
    }
}
