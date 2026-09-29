package app.cablegram.ui

import android.content.Context

/**
 * The licence notice shown on the About screen. `NOTICE` comes from the repository root: the Gradle task
 * `copyLegalAssets` packs it (and `LICENSE`) as `assets/legal/`, so the text in the app is the text in the
 * repository. Only NOTICE is shown here; the full licence text is long and a remote cannot scroll it well.
 */
internal object LegalText {
    fun notice(context: Context): String =
        runCatching { context.assets.open("legal/NOTICE").bufferedReader().use { it.readText() } }
            .getOrElse { "NOTICE is missing from this build." }
}
