package app.cablegram.phone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

/** Optional guidance only: opening this page never starts a Telegram session. */
@Composable
internal fun TelegramAccountHelp(onBack: () -> Unit) {
    val ui = LocalContext.current.resources
    val uriHandler = LocalUriHandler.current
    BackHandler(onBack = onBack)
    LazyColumn(
        Modifier.fillMaxSize().background(VlcBlack).safeDrawingPadding().maestroRoot(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            TextButton(onClick = onBack, modifier = Modifier.maestro(MaestroIds.TELEGRAM_ACCOUNT_HELP_BACK)) {
                Text(ui.getString(R.string.ui_back_76900f), color = VlcOrange)
            }
        }
        item { Text(ui.getString(R.string.telegram_separate_title), color = Color.White, style = MaterialTheme.typography.headlineSmall) }
        item { Text(ui.getString(R.string.telegram_separate_intro), color = VlcMuted) }
        items(listOf(
            R.string.telegram_separate_number_title to R.string.telegram_separate_number_body,
            R.string.telegram_separate_super_title to R.string.telegram_separate_super_body,
            R.string.telegram_separate_temporary_title to R.string.telegram_separate_temporary_body,
            R.string.telegram_separate_security_title to R.string.telegram_separate_security_body,
            R.string.ui_you_stay_in_control_faa171 to R.string.ui_you_ll_see_cablegram_in_telegram_settings_devices_and__e6a486,
        )) { (title, body) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(ui.getString(title), color = Color.White, style = MaterialTheme.typography.titleMedium)
                if (title == R.string.telegram_separate_temporary_title) {
                    Text(ui.getString(R.string.telegram_separate_temporary_price), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
                }
                Text(ui.getString(body), color = VlcMuted, style = MaterialTheme.typography.bodyMedium)
                if (title == R.string.telegram_separate_temporary_title) {
                    TextButton(onClick = { uriHandler.openUri("https://grizzlysms.com/telegram?utm_source=cablegram.app") }) {
                        Text("Grizzly SMS", color = VlcOrange)
                    }
                    TextButton(onClick = { uriHandler.openUri("https://5sim.net/?utm_source=cablegram.app") }) {
                        Text("5SIM", color = VlcOrange)
                    }
                    Text(ui.getString(R.string.telegram_separate_provider_disclaimer), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            TextButton(onClick = { uriHandler.openUri("https://www.super.ee/en/esim") }) {
                Text(ui.getString(R.string.telegram_separate_super_link), color = VlcOrange)
            }
        }
        item {
            TextButton(onClick = { uriHandler.openUri("https://telegram.org/faq") }) {
                Text(ui.getString(R.string.telegram_separate_telegram_link), color = VlcOrange)
            }
        }
    }
}
