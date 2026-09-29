package app.cablegram.phone

/**
 * What the library shows as one poster: a film, or a whole series (all its episodes together), as the TV does.
 */
sealed interface LibraryEntry {
    val key: String
    val title: String
    /** The item whose poster, progress and menu the card uses. */
    val lead: LibraryItem

    data class Film(val item: LibraryItem) : LibraryEntry {
        override val key = item.id
        override val title = item.title
        override val lead = item
    }

    data class Series(override val key: String, val episodes: List<LibraryItem>) : LibraryEntry {
        /** The show name is the title of its first episode, as on the TV. */
        override val title = episodes.first().title
        /** The episode in progress, else the first with a poster, else the first. */
        override val lead: LibraryItem = episodes.maxByOrNull { it.positionSeconds }?.takeIf { it.positionSeconds > 0 }
            ?: episodes.firstOrNull { it.posterPath != null || it.posterUrl != null }
            ?: episodes.first()
        val episodeCount: Int get() = episodes.size
        val seasonCount: Int get() = episodes.mapNotNull { it.seasonNumber }.distinct().size

        fun summary(): String {
            val count = if (episodes.size == 1) "1 episode" else "${episodes.size} episodes"
            return if (seasonCount > 1) "$seasonCount seasons · $count" else count
        }
    }
}

/** The same key the TV uses: one series per TMDB id, or per title when it has none. */
fun seriesKey(item: LibraryItem): String = item.tmdbId?.let { "tmdb:$it" } ?: "title:${item.title.trim().lowercase()}"

val episodeOrder: Comparator<LibraryItem> = compareBy<LibraryItem>(
    { it.seasonNumber ?: Int.MAX_VALUE },
    { it.episodeNumber ?: Int.MAX_VALUE },
    { it.importedAt },
)

/**
 * Films stay as they are; the episodes of one series collapse into a single [LibraryEntry.Series] placed where its
 * first episode was, so the shelf order (recently added, continue watching…) is kept.
 */
fun groupIntoEntries(items: List<LibraryItem>): List<LibraryEntry> {
    val episodesByKey = items.filter { it.mediaType == "tv" }.groupBy(::seriesKey)
    val placed = mutableSetOf<String>()
    return items.mapNotNull { item ->
        if (item.mediaType != "tv") return@mapNotNull LibraryEntry.Film(item)
        val key = seriesKey(item)
        if (!placed.add(key)) null else LibraryEntry.Series(key, episodesByKey.getValue(key).sortedWith(episodeOrder))
    }
}

/** "S1 · E3" for an episode, or just "E3", or nothing when its position is unknown. */
fun episodeBadge(item: LibraryItem): String? = when {
    item.seasonNumber != null && item.episodeNumber != null -> "S${item.seasonNumber} · E${item.episodeNumber}"
    item.episodeNumber != null -> "E${item.episodeNumber}"
    else -> null
}
