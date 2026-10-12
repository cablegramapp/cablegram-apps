package app.cablegram.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.cablegram.data.getRandomErrorVideoUrl

/** One error signature per visible warning/error; recompositions retain the chosen clip. */
@Composable
internal fun UnhappyVideoPanel(eventKey: String, modifier: Modifier = Modifier, muted: Boolean = true, videoUrl: String? = null) {
    val url = remember(eventKey, videoUrl) { videoUrl ?: getRandomErrorVideoUrl() }
    Box(modifier.clip(RoundedCornerShape(18.dp)).background(PanelRaised)) {
        SignatureVideo(url, muted = muted)
    }
}

/** Recovery actions remain usable while the decorative video loops or fails to load. */
@Composable
internal fun UnhappyScenarioFrame(
    eventKey: String,
    modifier: Modifier = Modifier,
    muted: Boolean = true,
    videoUrl: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier.fillMaxSize().background(InkDeep).padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UnhappyVideoPanel(eventKey, Modifier.weight(0.6f).fillMaxHeight(), muted, videoUrl)
        Column(
            Modifier.weight(0.4f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}
