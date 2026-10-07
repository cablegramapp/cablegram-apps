package app.cablegram.cast

import android.content.Context
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import app.cablegram.player.PlayerEvent
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.tv.media.MediaManager
import org.json.JSONObject

/** Cast never opens a media URL: transport callbacks enqueue the same actions as relay commands. */
class TvMediaSession(context: Context, private val manager: MediaManager) {
    private val session = MediaSessionCompat(context, "Cablegram")
    var onControl: ((String, Long?) -> Unit)? = null
    private var state = PlaybackStateCompat.STATE_NONE
    private var positionMs = 0L
    private var deviceId: String? = null
    private var terminal = true

    init {
        session.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() { onControl?.invoke("play", null) }
            override fun onPause() { onControl?.invoke("pause", null) }
            override fun onStop() { onControl?.invoke("stop", null) }
            override fun onSeekTo(pos: Long) { onControl?.invoke("seek", pos.coerceAtLeast(0)) }
        })
        session.isActive = true
        manager.setSessionCompatToken(session.sessionToken)
        publishState()
    }

    fun identify(id: String?, reason: String? = null) {
        deviceId = id
        val data = JSONObject()
        id?.let { data.put("deviceId", it) }
        reason?.let { data.put("reason", it) }
        manager.mediaStatusModifier.setCustomData(data)
        manager.broadcastMediaStatus()
    }

    /** Keep the sanitized placeholder on an empty picker; existing player metadata stays authoritative. */
    fun restoreMetadataAfterLoad() {
        if (session.controller.metadata != null) manager.mediaStatusModifier.mediaInfoModifier?.clear()
    }

    fun begin(videoId: String, title: String, poster: String?, durationMs: Long?) {
        terminal = false
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, videoId)
            // The video ID is deliberately not a playable URI.
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI, videoId)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs ?: 0)
        publicPosterUrl(poster)?.let { metadata.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, it) }
        manager.mediaStatusModifier.mediaInfoModifier?.clear()
        session.setMetadata(metadata.build())
        positionMs = 0
        state = PlaybackStateCompat.STATE_BUFFERING
        manager.mediaStatusModifier.setIdleReason(null)
        identify(deviceId)
        publishState()
    }

    fun onPlayerEvent(event: PlayerEvent, position: Long, duration: Long?) {
        if (terminal) return
        positionMs = position.coerceAtLeast(0)
        when (event) {
            is PlayerEvent.IsPlayingChanged -> state = if (event.isPlaying)
                PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
            is PlayerEvent.Buffering -> if (event.percent < 100f) state = PlaybackStateCompat.STATE_BUFFERING
            is PlayerEvent.Fatal -> { stop(MediaStatus.IDLE_REASON_ERROR); return }
            PlayerEvent.Ended -> { stop(MediaStatus.IDLE_REASON_FINISHED); return }
            else -> Unit
        }
        duration?.let { session.controller.metadata?.let { old ->
            session.setMetadata(MediaMetadataCompat.Builder(old)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, it).build())
        } }
        publishState()
    }

    fun progress(position: Long, playing: Boolean) {
        if (terminal) return
        positionMs = position.coerceAtLeast(0)
        if (playing) state = PlaybackStateCompat.STATE_PLAYING
        else if (state != PlaybackStateCompat.STATE_BUFFERING) state = PlaybackStateCompat.STATE_PAUSED
        publishState()
    }

    fun stop(reason: Int = MediaStatus.IDLE_REASON_CANCELED) {
        if (terminal) return
        terminal = true
        state = PlaybackStateCompat.STATE_STOPPED
        manager.mediaStatusModifier.setIdleReason(reason)
        publishState()
        manager.broadcastMediaStatus()
    }

    private fun publishState() {
        session.setPlaybackState(PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SEEK_TO or PlaybackStateCompat.ACTION_STOP)
            .setState(state, positionMs, if (state == PlaybackStateCompat.STATE_PLAYING) 1f else 0f).build())
    }
}

/** Shared media status must not contain signed URLs or credentials. */
internal fun publicPosterUrl(raw: String?): String? = raw?.takeIf { runCatching {
    val uri = java.net.URI(it)
    uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null
}.getOrDefault(false) }
