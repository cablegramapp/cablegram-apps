package app.cablegram.telegram

// Shared by apps/android-phone and apps/android-tv (CAB-60). Keep both copies identical.

/** A TMDB title named for one library video: [uniqueId] is the video's Telegram file id (file_unique_id). */
data class TgTmdbHint(val uniqueId: String, val tmdbType: String, val tmdbId: Int, val season: Int? = null, val episode: Int? = null)

/**
 * CAB-60: TMDB references in the library channel. A message that mentions a library video as `#<file_unique_id>` and
 * names one TMDB title (`#movie_<id>`, `#tv_<id>`, `#tv_<id>_s02e03`, or a themoviedb.org link) describes that video,
 * and so does a TMDB reference in the video's own caption. Bots and people both post these; nothing here depends on
 * who did or on how the message is laid out. Several different titles in one message are ambiguous and ignored.
 */
object ChannelHints {
    private data class Reference(val type: String, val id: Int, val season: Int?, val episode: Int?)

    private val tag = Regex("""#(movie|tv)_(\d{1,10})(?:_s(\d{1,4})e(\d{1,5}))?(?![A-Za-z0-9_])""", RegexOption.IGNORE_CASE)
    private val link = Regex(
        """themoviedb\.org/(movie|tv)/(\d{1,10})(?:-[^\s/?#]*)?(?:/season/(\d{1,4})(?:/episode/(\d{1,5}))?)?""",
        RegexOption.IGNORE_CASE,
    )
    private val mention = Regex("""#([A-Za-z0-9_-]{8,64})""")

    private fun reference(text: String): Reference? {
        val found = (tag.findAll(text) + link.findAll(text)).mapNotNull { m ->
            val (type, id, season, episode) = m.destructured
            val tmdbId = id.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val tv = type.equals("tv", ignoreCase = true)
            Reference(
                if (tv) "tv" else "movie", tmdbId,
                season.toIntOrNull()?.takeIf { tv && episode.isNotEmpty() },
                episode.toIntOrNull()?.takeIf { tv && season.isNotEmpty() },
            )
        }.toSet()
        // The same title written twice (a tag and a link) is one; two different titles are a guess not worth making.
        val titles = found.map { it.type to it.id }.toSet()
        if (titles.size != 1) return null
        return found.firstOrNull { it.season != null } ?: found.first()
    }

    /** The hints in [message] for videos in [known] (file ids); other hashtags are never sent anywhere. */
    fun from(message: TgMessage, known: Set<String>): List<TgTmdbHint> {
        val text = message.text.ifBlank { message.video?.caption.orEmpty() }
        val ref = reference(text) ?: return emptyList()
        val ids = linkedSetOf<String>()
        message.video?.file?.uniqueId?.takeIf { it.isNotEmpty() }?.let { ids += it }
        mention.findAll(text).map { it.groupValues[1] }.filter { it in known }.forEach { ids += it }
        return ids.map { TgTmdbHint(it, ref.type, ref.id, ref.season, ref.episode) }
    }
}
