package app.cablegram.ui

import app.cablegram.R
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.Text

/**
 * What a new owner can do now this TV is paired. Setup happens mostly in the phone app, so the TV only names it
 * and says where it lives; the phone's welcome tour carries the detail.
 */
private val WHAT_YOU_CAN_DO = listOf(
    "Pick a video on your phone and it plays here.",
    "Connect Telegram, in the phone app or this TV's Settings, to watch here even when your phone is off. Recommended.",
    "Choose subtitles in your language from the phone.",
)

/**
 * Shown once over the library right after this TV is paired: what happens next, and where in the phone app.
 * "Start watching" has focus, so OK or Back simply closes it.
 */
@Composable
internal fun ConnectedWelcomeDialog(visible: Boolean, onDismiss: () -> Unit) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    if (!visible) return
    // The platform's default dialog width is far narrower than asked for (440 dp on a 960 dp Chromecast), which
    // wrapped the text into a column taller than the screen and squashed the button. Capped to the screen, it
    // scrolls with large fonts instead.
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(720.dp)
                .heightIn(max = screenHeight - 48.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Panel)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 36.dp, vertical = 28.dp),
        ) {
            BrandMark()
            Spacer(Modifier.height(20.dp))
            Text(ui.getString(R.string.ui_this_tv_is_connected_c944e9), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
                ui.getString(R.string.ui_your_phone_is_the_remote_and_the_library_add_videos_in_fc9a65),
                color = Muted,
                fontSize = 17.sp,
                lineHeight = 25.sp,
            )
            Spacer(Modifier.height(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                WHAT_YOU_CAN_DO.forEach { WelcomePoint(it, fontSize = 16.sp) }
            }
            Spacer(Modifier.height(24.dp))
            val startFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                // Let the Button start observing focus before focusing it.
                androidx.compose.runtime.withFrameNanos { }
                runCatching { startFocus.requestFocus() }
            }
            Button(onClick = onDismiss, modifier = Modifier.focusRequester(startFocus)) { Text(ui.getString(R.string.ui_start_watching_89562c)) }
        }
    }
}

@Composable
private fun WelcomePoint(text: String, fontSize: androidx.compose.ui.unit.TextUnit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Spacer(Modifier.padding(top = 7.dp).size(7.dp).clip(CircleShape).background(Acid))
        Text(text, color = Paper, fontSize = fontSize, lineHeight = fontSize * 1.4f)
    }
}
