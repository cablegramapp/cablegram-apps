package app.cablegram.ui

import androidx.annotation.DrawableRes
import app.cablegram.R
import app.cablegram.data.Video

// Branded stand-ins for private titles: the TV never shows a private video's own artwork.
private val privateThumbnails = intArrayOf(
    R.drawable.private_thumb_midnight,
    R.drawable.private_thumb_plum,
    R.drawable.private_thumb_teal,
    R.drawable.private_thumb_ember,
    R.drawable.private_thumb_aurora,
    R.drawable.private_thumb_forest,
)

/** The stand-in for [key]; the same title always gets the same picture, so cards do not flicker on refresh. */
@DrawableRes
internal fun privateThumbnail(key: String): Int = privateThumbnails[Math.floorMod(key.hashCode(), privateThumbnails.size)]

/** What a card draws for [this]: its poster URL, or for a private title the stand-in drawable. */
internal fun Video.posterModel(): Any? = if (isPrivate) privateThumbnail(id) else posterUrl?.takeIf { it.isNotBlank() }
