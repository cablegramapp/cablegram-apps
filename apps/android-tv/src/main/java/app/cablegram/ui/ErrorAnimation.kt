package app.cablegram.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import app.cablegram.R
import coil3.compose.AsyncImage

/** Looping error animations bundled in the app (scripts/error-animations/generate.py), so a warning never waits on the network. */
private val ERROR_ANIMATIONS = listOf(R.raw.error_signal, R.raw.error_rain, R.raw.error_frame)

/** One animation per visible warning or error: recompositions with the same [eventKey] keep it. Fills its panel, cropping the 4:3 loop. */
@Composable
internal fun ErrorAnimation(eventKey: Any?, modifier: Modifier = Modifier) {
    val animation = remember(eventKey) { ERROR_ANIMATIONS.random() }
    AsyncImage(
        model = animation,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.fillMaxSize(),
    )
}
