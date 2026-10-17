package app.cablegram.phone

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

class ShareReceiverActivity : ComponentActivity() {
    private val viewModel: PhoneViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val uri = shareUri()
        val webUrl = if (uri == null) shareWebUrl() else null
        val name = uri?.let { displayName(it) } ?: "video.mp4"
        val caption = listOfNotNull(intent.getStringExtra(Intent.EXTRA_TEXT), intent.getStringExtra(Intent.EXTRA_SUBJECT))
            .firstOrNull { !isWeakCatalogLabel(it) }
        if (savedInstanceState == null) {
            when {
                uri != null -> viewModel.importShared(uri, name, playNow = viewModel.paired, caption = caption)
                webUrl != null -> viewModel.importWeb(webUrl, caption)
            }
        }
        setContent {
            PhoneLanguage {
            val ui = androidx.compose.ui.platform.LocalContext.current.resources
            PhoneTheme {
                if (viewModel.pendingPrivacyItem != null) {
                    PrivacyPrompt(viewModel)

                } else {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // No Surface sets a content color here; without an explicit
                        // color this text rendered black on the dark background.
                        Text(ui.getString(R.string.ui_adding_to_library_7ca806), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onBackground)
                        Text(
                            viewModel.status
                                ?: when {
                                    uri != null -> ui.getString(R.string.ui_copying_26b256, name)
                                    webUrl != null -> ui.getString(R.string.ui_analyzing_link_c9a741)
                                    else -> ui.getString(R.string.ui_that_share_did_not_include_a_video_or_web_link_496fff)
                                },
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        if (viewModel.busy) {
                            CircularProgressIndicator()
                        } else {
                            Button(onClick = { goToLibrary() }) { Text(ui.getString(R.string.ui_open_library_24974a)) }
                        }
                    }
                }
                LaunchedEffect(viewModel.busy, viewModel.sharedImportSucceeded, viewModel.pendingPrivacyItem) {
                    if (
                        !viewModel.busy &&
                        viewModel.pendingPrivacyItem == null &&
                        viewModel.sharedImportSucceeded == true
                    ) {
                        delay(700)
                        goToLibrary()
                    }
                }
            }
            }
        }
    }

    private fun goToLibrary() {
        startActivity(
            Intent(this, PhoneActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
        finish()
    }

    private fun shareUri(): Uri? {
        val extra = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        return extra ?: intent.clipData?.getItemAt(0)?.uri
    }

    private fun shareWebUrl(): String? {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val urls = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE)
            .findAll(text)
            .map { it.value.trimEnd('.', ',', ')', ']', '}') }
            .distinct()
            .toList()
        return urls.singleOrNull()
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index) ?: "video.mp4"
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "video.mp4"
    }
}
