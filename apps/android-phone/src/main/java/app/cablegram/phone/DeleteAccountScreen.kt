package app.cablegram.phone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** The public page that deletes an account without the app; also the link given to Google Play. */
internal const val DELETE_ACCOUNT_WEB_URL = "https://api.cablegram.app/delete-account"

/**
 * Deleting the account, step by step: this screen says what goes, mails a code to the account's address (accounts
 * have no password, spec 007), and a second dialog asks for one more explicit tap before anything is sent. The code
 * lives only in this composable's memory.
 */
@Composable
internal fun DeleteAccountScreen(viewModel: PhoneViewModel) {
    val deletion = viewModel.accountDeletion
    var code by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    val sentTo = viewModel.deletionCodeSentTo
    BackHandler(enabled = !deletion.running) { viewModel.closeDeleteAccount() }
    Box(Modifier.fillMaxSize().background(VlcBlack).safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            ScreenHeading(stringResource(R.string.delete_account_title), stringResource(R.string.delete_account_intro))
            SectionCard(stringResource(R.string.delete_account_removed_heading)) {
                Bullet(R.string.delete_account_removed_account)
                Bullet(R.string.delete_account_removed_household)
                Bullet(R.string.delete_account_removed_library)
                Bullet(R.string.delete_account_removed_telegram)
                Bullet(R.string.delete_account_removed_files)
            }
            SectionCard(stringResource(R.string.delete_account_kept_heading)) {
                Bullet(R.string.delete_account_kept_telegram)
                Bullet(R.string.delete_account_kept_phone)
                if (viewModel.telegramLink?.linked == true) Bullet(R.string.delete_account_tv_sessions)
            }
            if (sentTo == null) {
                Text(stringResource(R.string.delete_account_send_code_hint), color = VlcMuted, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(
                    onClick = viewModel::requestDeletionCode,
                    enabled = !viewModel.busy && !deletion.running,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).maestro("delete_account_send_code"),
                ) { Text(stringResource(R.string.delete_account_send_code)) }
                if (viewModel.deletionCodeFailed) StatusNote(stringResource(R.string.delete_account_code_failed))
            } else {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(6) },
                    modifier = Modifier.fillMaxWidth().maestro("delete_account_code"),
                    singleLine = true,
                    enabled = !deletion.running,
                    label = { Text(stringResource(R.string.delete_account_code_label)) },
                    supportingText = { Text(stringResource(R.string.delete_account_code_hint, sentTo)) },
                    isError = deletion.error == AccountDeletionError.WrongCode,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (code.length == 6 && !deletion.running) confirming = true }),
                )
                TextButton(
                    onClick = viewModel::requestDeletionCode,
                    enabled = !viewModel.busy && !deletion.running,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.delete_account_send_new_code)) }
                if (viewModel.deletionCodeFailed) StatusNote(stringResource(R.string.delete_account_code_failed))
            }
    deletion.error?.let { StatusNote(stringResource(it.messageRes())) }
            Button(
                onClick = { confirming = true },
                enabled = sentTo != null && code.length == 6 && !deletion.running,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).maestro("delete_account_continue"),
            ) {
                if (deletion.running) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(stringResource(if (deletion.running) R.string.delete_account_deleting else R.string.delete_account_continue))
            }
            TextButton(
                onClick = viewModel::closeDeleteAccount,
                enabled = !deletion.running,
                modifier = Modifier.fillMaxWidth().maestro("delete_account_cancel"),
            ) { Text(stringResource(R.string.delete_account_cancel)) }
        }
    }
    if (confirming) AlertDialog(
        modifier = Modifier.maestroRoot(),
        onDismissRequest = { confirming = false },
        title = { Text(stringResource(R.string.delete_account_confirm_title)) },
        text = { Text(stringResource(R.string.delete_account_confirm_text)) },
        confirmButton = {
            TextButton(
                onClick = { confirming = false; viewModel.deleteAccount(code) },
                modifier = Modifier.maestro("delete_account_confirm"),
            ) { Text(stringResource(R.string.delete_account_confirm_button), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.delete_account_cancel)) } },
    )
}

@Composable
private fun Bullet(text: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("•", color = VlcMuted)
        Text(stringResource(text), color = VlcMuted, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun AccountDeletionError.messageRes(): Int = when (this) {
    AccountDeletionError.WrongCode -> R.string.delete_account_error_wrong_code
    AccountDeletionError.RateLimited -> R.string.delete_account_error_rate_limited
    AccountDeletionError.SessionExpired -> R.string.delete_account_error_session_expired
    AccountDeletionError.Offline -> R.string.delete_account_error_offline
    AccountDeletionError.Failed -> R.string.delete_account_error_failed
}
