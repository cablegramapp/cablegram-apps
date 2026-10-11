package app.cablegram.phone

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

private fun Context.activity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.activity(); else -> null }

private const val SUBDL_KEY_PAGE = "https://subdl.com/panel/api"
private const val OPENSUBTITLES_KEY_PAGE = "https://www.opensubtitles.com/consumers"

/**
 * One free account is enough: SubDL (a key only) leads; OpenSubtitles is an optional extra because it also needs the
 * person's login. Inputs are never saveable/restored, prefilled, copied into diagnostics, or shown in screenshots.
 * [requireAccount] is false where the screen is opened from Settings, so Done always leaves it.
 */
@Composable internal fun SubtitleProviderSetup(viewModel: PhoneViewModel, onDone: () -> Unit, doneLabel: String = "Find subtitles", requireAccount: Boolean = true) {
    val credentialRevision = viewModel.subtitleCredentialRevision
    val window = LocalContext.current.activity()?.window
    DisposableEffect(window) {
        val alreadySecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!alreadySecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    val subdl = remember(credentialRevision) { viewModel.subtitleProviderConfigured("subdl") }
    val openSubtitles = remember(credentialRevision) { viewModel.subtitleProviderConfigured("opensubtitles") }

    Text("Subtitle accounts", color = Color.White, style = MaterialTheme.typography.titleLarge)
    Text("Cablegram finds subtitles through your own free account at a subtitle site. One account is enough.", color = VlcMuted)

    SectionCard("SubDL · recommended") {
        if (subdl) {
            ConnectedRow("SubDL is connected.")
            ProviderForm(viewModel, "subdl", connected = true)
        } else {
            Text("Quickest to set up: it only needs a key.", color = VlcMuted)
            Step(1, "Sign in or create a free account at SubDL.")
            Step(2, "Open the API page and copy your API key.")
            OpenLink("Open SubDL API page", SUBDL_KEY_PAGE)
            Step(3, "Paste the key here. Don't use your SubDL password.")
            ProviderForm(viewModel, "subdl", connected = false)
        }
    }

    var showOpenSubtitles by remember { mutableStateOf(false) }
    SectionCard("OpenSubtitles · optional") {
        if (openSubtitles) {
            ConnectedRow("OpenSubtitles is connected.")
            ProviderForm(viewModel, "opensubtitles", connected = true)
        } else {
            Text("The biggest catalogue, often better for older titles. It finds subtitles made for your exact file.", color = VlcMuted)
            TextButton(onClick = { showOpenSubtitles = !showOpenSubtitles }, contentPadding = PaddingValues(0.dp)) {
                Text(if (showOpenSubtitles) "Hide" else "Add OpenSubtitles too")
                Icon(if (showOpenSubtitles) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (showOpenSubtitles) {
                Text("OpenSubtitles needs two things: an API key for searching, and your OpenSubtitles username and password, " +
                    "because each download counts against your own account (about 5 to 20 a day on a free account). " +
                    "Your login stays on this phone.", color = VlcMuted)
                Step(1, "Create a free account at opensubtitles.com.")
                Step(2, "Open the API consumers page, create a consumer and copy its API key.")
                OpenLink("Open OpenSubtitles API page", OPENSUBTITLES_KEY_PAGE)
                Step(3, "Enter the key with your OpenSubtitles username and password.")
                ProviderForm(viewModel, "opensubtitles", connected = false)
            }
        }
    }

    PrivacyAndTerms()
    val anyConnected = subdl || openSubtitles
    Button(enabled = anyConnected || !requireAccount, onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(doneLabel) }
    if (!anyConnected && requireAccount) Text("Connect at least one account to search.", color = VlcMuted, style = MaterialTheme.typography.bodySmall)
}

@Composable private fun ConnectedRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.CheckCircle, null, tint = VlcOrange)
        Text(text, color = Color.White)
    }
}

@Composable private fun Step(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("$number.", color = VlcOrange)
        Text(text, color = Color.White)
    }
}

@Composable private fun OpenLink(label: String, url: String) {
    val uriHandler = LocalUriHandler.current
    OutlinedButton(onClick = { runCatching { uriHandler.openUri(url) } }) {
        Text(label); Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
    }
}

/** Entry for one provider; once connected it collapses to Replace / Remove. */
@Composable private fun ProviderForm(viewModel: PhoneViewModel, provider: String, connected: Boolean) {
    val clipboard = LocalClipboardManager.current
    var editing by remember(provider, connected) { mutableStateOf(!connected) }
    var key by remember(provider) { mutableStateOf("") }
    var username by remember(provider) { mutableStateOf("") }
    var password by remember(provider) { mutableStateOf("") }
    var passwordVisible by remember(provider) { mutableStateOf(false) }
    var note by remember(provider) { mutableStateOf<String?>(null) }
    val needsLogin = provider == "opensubtitles"
    val name = if (needsLogin) "OpenSubtitles" else "SubDL"

    if (editing) {
        OutlinedTextField(key, { key = it.trim() }, label = { Text("$name API key") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true,
            trailingIcon = {
                IconButton(onClick = { clipboard.getText()?.text?.trim()?.let { key = it } }) { Icon(Icons.Default.ContentPaste, "Paste key") }
            },
            modifier = Modifier.fillMaxWidth().maestro("subtitle_${provider}_key"))
        if (needsLogin) {
            OutlinedTextField(username, { username = it }, label = { Text("OpenSubtitles username") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().maestro("subtitle_opensubtitles_username"))
            OutlinedTextField(password, { password = it }, label = { Text("OpenSubtitles password") }, singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (passwordVisible) "Hide password" else "Show password")
                    }
                },
                modifier = Modifier.fillMaxWidth().maestro("subtitle_opensubtitles_password"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = key.isNotBlank() && (!needsLogin || username.isNotBlank() && password.isNotBlank()), onClick = {
                note = try {
                    viewModel.saveSubtitleProvider(provider, PersonalSubtitleCredentials(key.trim(), username.trim(), password))
                    key = ""; username = ""; password = ""; editing = false
                    "Saved on this phone. If $name refuses it, the search will tell you."
                } catch (_: Exception) { "Couldn't save it securely. Try again." }
            }, modifier = Modifier.maestro("subtitle_${provider}_save")) { Text(if (connected) "Save new details" else "Connect $name") }
            if (connected) TextButton(onClick = { editing = false; key = ""; username = ""; password = "" }) { Text("Cancel") }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { editing = true; note = null }) { Text(if (needsLogin) "Change details" else "Replace key") }
            TextButton(onClick = { viewModel.removeSubtitleProvider(provider); note = "$name removed. Subtitles you already chose stay." }) { Text("Remove") }
        }
    }
    note?.let { Text(it, color = VlcMuted, style = MaterialTheme.typography.bodySmall) }
}

@Composable private fun PrivacyAndTerms() {
    var open by remember { mutableStateOf(false) }
    Text("Searches go straight from this phone to the site you connect, using your own quota. " +
        "Only the subtitle you pick is saved for your TVs.", color = VlcMuted, style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { open = !open }, contentPadding = PaddingValues(0.dp)) {
        Text(if (open) "Hide privacy and terms" else "Privacy and terms")
        Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
    }
    if (open) {
        Text("The subtitle sites see your IP address, the title, file name, identifiers, hash and language choices. Video, audio and activity patterns stay on this phone. Requests and downloads use your personal quota.", color = VlcMuted, style = MaterialTheme.typography.bodySmall)
        Text("Only the subtitle you choose, its language, provider reference and timing are stored with your household for paired TVs. Remove a saved track from the title's subtitle controls; deleting its source or your account also deletes it. Removing an account here stops searching but keeps tracks you already chose.", color = VlcMuted, style = MaterialTheme.typography.bodySmall)
        Text("Personal keys do not grant copyright permission. Use tracks you have a lawful right to store and share with your household. Provider terms apply: subdl.com/terms and opensubtitles.com. For copyright complaints contact copyright@cablegram.app with the title and track details for removal.", color = VlcMuted, style = MaterialTheme.typography.bodySmall)
    }
}
