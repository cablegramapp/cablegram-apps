package app.cablegram.phone

/** What a TV last said its player was doing (`GET /api/control/playback/{deviceId}`). */
data class TvPlayback(
    val state: String,
    val videoId: String?,
    val originId: String?,
    val positionMs: Long?,
    /** How long ago the server received the report; null when the TV has not reported since the server started. */
    val ageMs: Long?,
    val online: Boolean,
)

/** What the phone's remote should do after reading [TvPlayback]. */
sealed interface TvFollow {
    /** Nothing new, or nothing the phone can trust: keep showing what it has. */
    data object Keep : TvFollow
    /** The TV is not playing anything (the title ended or was stopped there, or the TV went away). */
    data object Stopped : TvFollow
    data class Playing(val paused: Boolean) : TvFollow
    /** The TV is playing another title, started on the TV itself. */
    data class Other(val videoId: String?, val originId: String?, val paused: Boolean) : TvFollow
}

/** How often the phone asks while a title plays on a TV and the app is open. */
const val TV_FOLLOW_POLL_MS = 3_000L
/** For this long after the phone's own command, a TV report made before it is not trusted (CAB-57). */
const val OWN_COMMAND_SHIELD_MS = 10_000L
/** A "playing" TV refreshes its report every 15 s; one this old has stopped reporting. */
const val TV_PLAYING_STALE_MS = 45_000L
/** The TV's command channel has been closed this long: it is off or the app was closed. */
const val TV_GONE_MS = 20_000L

/**
 * CAB-57: the phone's remote follows the TV instead of the last command it sent. [sinceOwnCommandMs] is how long ago
 * this phone's last command was answered: shortly after it, a report older than that may predate the command's effect.
 * Later the TV's newest report wins, even an old one: a stopped or paused TV does not report again.
 */
fun followTv(report: TvPlayback, nowPlayingId: String?, sinceOwnCommandMs: Long): TvFollow {
    val age = report.ageMs ?: return TvFollow.Keep
    if (report.state == "unknown") return TvFollow.Keep
    if (age >= sinceOwnCommandMs && sinceOwnCommandMs < OWN_COMMAND_SHIELD_MS) return TvFollow.Keep
    if (!report.online && age > TV_GONE_MS) return TvFollow.Stopped
    return when (report.state) {
        "stopped" -> TvFollow.Stopped
        "playing", "paused" -> {
            val paused = report.state == "paused"
            if (report.state == "playing" && age > TV_PLAYING_STALE_MS) TvFollow.Stopped
            else if (nowPlayingId == null || nowPlayingId == report.videoId || nowPlayingId == report.originId) TvFollow.Playing(paused)
            else TvFollow.Other(report.videoId, report.originId, paused)
        }
        else -> TvFollow.Keep
    }
}
