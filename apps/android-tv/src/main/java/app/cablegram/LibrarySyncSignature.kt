package app.cablegram

import app.cablegram.data.Video

/** Include manual metadata so an unchanged title cannot hide a shared correction. */
internal fun librarySyncSignature(videos: List<Video>): String = videos.joinToString("|") { v ->
    "${v.id}:${v.ingestProgress}:${v.ingestStage}:${v.tier}:${v.inMyList}:${v.resumePositionSeconds}:${v.title}:${v.posterUrl}:${v.releaseYear}:${v.overview}:${v.mediaType}"
}
