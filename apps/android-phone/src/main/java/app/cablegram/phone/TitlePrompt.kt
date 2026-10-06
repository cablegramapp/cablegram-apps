package app.cablegram.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PrivacyPrompt(viewModel: PhoneViewModel) {
    viewModel.pendingPrivacyItem ?: return
    AlertDialog(
        modifier = Modifier.maestroRoot(),
        onDismissRequest = { },
        title = { Text("Require phone approval to play?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The title stays visible on the TV, but your phone must approve each play — one playback at a time.",
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.choosePrivacy(true) }) { Text("Require approval") }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.choosePrivacy(false) }) { Text("No, play freely on the TV") }
        },
    )
}
