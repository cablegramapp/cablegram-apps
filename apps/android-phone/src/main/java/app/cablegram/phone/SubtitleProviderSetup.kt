package app.cablegram.phone

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

private fun Context.activity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.activity(); else -> null }

/** Inputs are never saveable/restored, prefilled, copied into diagnostics, or displayed in screenshots. */
@Composable internal fun SubtitleProviderSetup(viewModel: PhoneViewModel, onDone: () -> Unit, doneLabel: String = "Find subtitles") {
    val credentialRevision = viewModel.subtitleCredentialRevision
    val window = LocalContext.current.activity()?.window
    DisposableEffect(window) {
        val alreadySecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!alreadySecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    Text("Your subtitle accounts", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.titleLarge)
    Text("Searches go directly from this phone to the providers. They see your IP address, title, file name, identifiers, hash and language choices. Video, audio and activity patterns stay here. Requests and downloads use your personal quota.", color = VlcMuted)
    Text("Only the subtitle you choose, its language, provider reference and timing are stored with your household for paired TVs. Remove a saved track from the title's subtitle controls; deleting its source or your account also deletes it. Removing a provider key stops discovery but keeps selected tracks.", color = VlcMuted)
    Text("Personal keys do not grant copyright permission. Use tracks you have a lawful right to store and share with your household. Provider terms apply: subdl.com/terms and opensubtitles.com. For copyright complaints contact copyright@cablegram.app with the title and track details for removal.", color = VlcMuted)
    listOf("subdl", "opensubtitles").forEach { provider ->
        val configured = remember(credentialRevision, provider) { viewModel.subtitleProviderConfigured(provider) }
        var key by remember(provider) { mutableStateOf("") }
        var username by remember(provider) { mutableStateOf("") }
        var password by remember(provider) { mutableStateOf("") }
        var note by remember(provider) { mutableStateOf<String?>(null) }
        SectionCard(if (provider == "subdl") "SubDL" else "OpenSubtitles") {
            Text(if (configured) "Configured on this phone" else "Not configured", color = VlcMuted)
            Text(if (provider == "subdl") "Get your personal API key at subdl.com/panel. Do not enter your SubDL password." else "Use your OpenSubtitles.com API key and account login. Downloads require a personal session; login tokens stay in memory.", color = VlcMuted)
            OutlinedTextField(key, { key = it }, label = { Text("API key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            if (provider == "opensubtitles") {
                OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            Button(enabled = key.isNotBlank() && (provider == "subdl" || username.isNotBlank() && password.isNotBlank()), onClick = {
                try { viewModel.saveSubtitleProvider(provider, PersonalSubtitleCredentials(key.trim(), username.trim(), password)); key = ""; username = ""; password = ""; note = "Saved securely on this phone." }
                catch (_: Exception) { note = "Credentials couldn't be saved securely. Try again." }
            }) { Text(if (configured) "Replace" else "Save") }
            if (configured) TextButton(onClick = { viewModel.removeSubtitleProvider(provider); key = ""; username = ""; password = ""; note = "Removed." }) { Text("Remove") }
            note?.let { Text(it, color = VlcMuted) }
        }
    }
    Button(enabled = listOf("subdl", "opensubtitles").any(viewModel::subtitleProviderConfigured), onClick = onDone) { Text(doneLabel) }
}
