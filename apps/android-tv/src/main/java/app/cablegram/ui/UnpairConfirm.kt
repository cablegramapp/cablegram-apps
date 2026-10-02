package app.cablegram.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text

/** Unpairing revokes the TV on the server at once, so it is asked about first; [confirm] is the only way through. */
internal class UnpairConfirmState {
    var asking by mutableStateOf(false)
        private set

    fun ask() { asking = true }

    fun cancel() { asking = false }

    fun confirm(unpair: () -> Unit) {
        if (!asking) return
        asking = false
        unpair()
    }
}

/** Asks before unpairing. Cancel has focus, so a stray OK press does not unpair; Back also cancels. */
@Composable
internal fun UnpairConfirmDialog(state: UnpairConfirmState, onUnpair: () -> Unit) {
    if (!state.asking) return
    Dialog(onDismissRequest = state::cancel) {
        Column(
            Modifier.width(560.dp).clip(RoundedCornerShape(22.dp)).background(Panel).padding(32.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            Text("Unpair this TV?", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                "This TV will be signed out of Cablegram. To use it again you will need to pair it with your phone again.",
                color = Muted,
                fontSize = 17.sp,
                lineHeight = 25.sp,
            )
            Spacer(Modifier.height(28.dp))
            val cancelFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                // Let the Button start observing focus before focusing it.
                androidx.compose.runtime.withFrameNanos { }
                runCatching { cancelFocus.requestFocus() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = state::cancel, modifier = Modifier.focusRequester(cancelFocus)) { Text("Cancel") }
                Button(onClick = { state.confirm(onUnpair) }, colors = ButtonDefaults.colors(containerColor = Coral)) { Text("Unpair") }
            }
        }
    }
}
