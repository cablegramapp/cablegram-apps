package app.cablegram.player

import android.content.Context

data class PlayerSettingsPreference(
    val playbackSpeed: Float = 1f,
    val aspectRatio: PlayerAspectRatio = PlayerAspectRatio.FIT,
    val audioDelayMs: Long = 0L,
    val subtitleDelayMs: Long = 0L,
)

data class SubtitlePreference(
    val off: Boolean = false,
    val trackId: String? = null,
    val label: String? = null,
    /** The saved Cablegram subtitle this choice was made for; a new subtitle starts afresh. */
    val smartId: String? = null,
)

fun matchSubtitleTrack(preference: SubtitlePreference, tracks: List<PlayerTrack>): String? {
    if (preference.off) return null
    preference.label?.let { label ->
        tracks.firstOrNull { it.label.equals(label, ignoreCase = true) }?.let { return it.id.toString() }
    }
    preference.trackId?.toIntOrNull()?.let { id ->
        tracks.firstOrNull { it.id == id }?.let { return it.id.toString() }
    }
    return null
}

class PlaybackPreferencesStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun subtitle(videoId: String): SubtitlePreference? {
        if (!preferences.contains(offKey(videoId)) && !preferences.contains(labelKey(videoId))) return null
        return SubtitlePreference(
            off = preferences.getBoolean(offKey(videoId), false),
            trackId = preferences.getString(idKey(videoId), null),
            label = preferences.getString(labelKey(videoId), null),
            smartId = preferences.getString(smartKey(videoId), null),
        )
    }

    fun saveSubtitle(videoId: String, preference: SubtitlePreference) {
        preferences.edit()
            .putBoolean(offKey(videoId), preference.off)
            .putString(idKey(videoId), preference.trackId)
            .putString(labelKey(videoId), preference.label)
            .putString(smartKey(videoId), preference.smartId)
            .apply()
    }

    fun playerSettings(): PlayerSettingsPreference = PlayerSettingsPreference(
        playbackSpeed = preferences.getFloat(SPEED, 1f),
        aspectRatio = runCatching {
            PlayerAspectRatio.valueOf(preferences.getString(ASPECT, PlayerAspectRatio.FIT.name) ?: PlayerAspectRatio.FIT.name)
        }.getOrDefault(PlayerAspectRatio.FIT),
        audioDelayMs = preferences.getLong(AUDIO_DELAY, 0L),
        subtitleDelayMs = preferences.getLong(SUBTITLE_DELAY, 0L),
    )

    fun savePlayerSettings(settings: PlayerSettingsPreference) {
        preferences.edit()
            .putFloat(SPEED, settings.playbackSpeed)
            .putString(ASPECT, settings.aspectRatio.name)
            .putLong(AUDIO_DELAY, settings.audioDelayMs)
            .putLong(SUBTITLE_DELAY, settings.subtitleDelayMs)
            .apply()
    }

    private fun offKey(videoId: String) = "subtitle_off_$videoId"
    private fun idKey(videoId: String) = "subtitle_id_$videoId"
    private fun labelKey(videoId: String) = "subtitle_label_$videoId"
    private fun smartKey(videoId: String) = "subtitle_smart_$videoId"

    private companion object {
        const val PREFS = "cablegram_playback"
        const val SPEED = "player_speed"
        const val ASPECT = "player_aspect"
        const val AUDIO_DELAY = "player_audio_delay_ms"
        const val SUBTITLE_DELAY = "player_subtitle_delay_ms"
    }
}
