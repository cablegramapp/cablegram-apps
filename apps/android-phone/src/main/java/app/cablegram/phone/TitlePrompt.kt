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
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    viewModel.pendingPrivacyItem ?: return
    AlertDialog(
        modifier = Modifier.maestroRoot(),
        onDismissRequest = { },
        title = { Text(ui.getString(R.string.ui_require_phone_approval_to_play_6ebd73)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    ui.getString(R.string.ui_the_title_stays_visible_on_the_tv_but_your_phone_must__e1e2d8),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.choosePrivacy(true) }) { Text(ui.getString(R.string.ui_require_approval_2eb949)) }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.choosePrivacy(false) }) { Text(ui.getString(R.string.ui_no_play_freely_on_the_tv_ea1f0c)) }
        },
    )
}
