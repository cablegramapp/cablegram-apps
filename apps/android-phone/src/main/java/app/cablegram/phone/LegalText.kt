package app.cablegram.phone

import android.content.Context

/**
 * The licence and third-party notices shown on the About/Settings screen. `NOTICE` and `LICENSE` come from
 * the repository root: the Gradle task `copyLegalAssets` packs them as `assets/legal/`, so the text in the
 * app is always the text in the repository.
 */
object LegalText {
    fun read(context: Context): String = listOf("NOTICE", "LICENSE").joinToString("\n\n") { name ->
        runCatching { context.assets.open("legal/$name").bufferedReader().use { it.readText() } }
            .getOrElse { "$name is missing from this build." }
    }
}
