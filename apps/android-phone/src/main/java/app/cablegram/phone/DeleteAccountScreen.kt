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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** The public page that deletes an account without the app; also the link given to Google Play. */
internal const val DELETE_ACCOUNT_WEB_URL = "https://api.cablegram.app/delete-account"

/**
 * Deleting the account, step by step: this screen says what goes, asks for the password, and a second dialog
 * asks for one more explicit tap before anything is sent. The password lives only in this composable's memory.
 */
@Composable
internal fun DeleteAccountScreen(viewModel: PhoneViewModel) {
    val deletion = viewModel.accountDeletion
    // Like the sign-in form, the password stays out of saved instance state.
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
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
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth().maestro("delete_account_password"),
                singleLine = true,
                enabled = !deletion.running,
                label = { Text(stringResource(R.string.delete_account_password_label)) },
                supportingText = { Text(stringResource(R.string.delete_account_password_hint)) },
                isError = deletion.error == AccountDeletionError.WrongPassword,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        val label = stringResource(if (passwordVisible) R.string.delete_account_hide_password else R.string.delete_account_show_password)
                        Icon(if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, label)
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (password.isNotEmpty() && !deletion.running) confirming = true }),
            )
            deletion.error?.let { StatusNote(stringResource(it.messageRes())) }
            Button(
                onClick = { confirming = true },
                enabled = password.isNotEmpty() && !deletion.running,
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
                onClick = { confirming = false; viewModel.deleteAccount(password) },
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
    AccountDeletionError.WrongPassword -> R.string.delete_account_error_wrong_password
    AccountDeletionError.RateLimited -> R.string.delete_account_error_rate_limited
    AccountDeletionError.SessionExpired -> R.string.delete_account_error_session_expired
    AccountDeletionError.Offline -> R.string.delete_account_error_offline
    AccountDeletionError.Failed -> R.string.delete_account_error_failed
}
