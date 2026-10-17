package app.cablegram.phone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** "Sat, 3 Oct, 12:00" in the phone's language and time zone. */
internal fun formatCheckout(instant: Instant): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(instant)

/**
 * "Is this your TV?" (spec 004 US8): a home TV behaves as before; a temporary TV (hotel, friend,
 * rental) is unpaired at its end time and holds no Telegram session unless the owner opts in.
 */
@Composable
internal fun PairTrustDialog(viewModel: PhoneViewModel) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val choices = remember { TvTrustRules.checkoutChoices() }
    var temporary by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(0) }
    var direct by remember { mutableStateOf(false) }
    val linked = viewModel.telegramLink?.linked == true
    val linkedAt = viewModel.telegramLink?.linkedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
    val directAllowed = TvTrustRules.directAllowed(linkedAt, Instant.now())
    AlertDialog(
        modifier = Modifier.maestroRoot(),
        onDismissRequest = viewModel::cancelPendingPair,
        title = { Text(ui.getString(R.string.ui_is_this_your_tv_525588)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ChoiceRow(ui.getString(R.string.ui_my_tv_home_4a4daa), ui.getString(R.string.ui_it_signs_in_to_your_telegram_library_and_stays_paired_7153cf), !temporary) { temporary = false }
                ChoiceRow(ui.getString(R.string.ui_temporary_tv_2b77cf), ui.getString(R.string.ui_a_hotel_a_friend_s_or_a_rental_it_is_unpaired_at_the_t_312310), temporary) { temporary = true }
                if (temporary) {
                    Text(ui.getString(R.string.ui_unpair_it_at_e679be), color = VlcMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    choices.forEachIndexed { index, (label, instant) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { selected = index },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected == index, onClick = { selected = index })
                            Column {
                                Text(label)
                                Text(formatCheckout(instant), color = VlcMuted, fontSize = 12.sp)
                            }
                        }
                    }
                    if (linked) {
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = directAllowed) { direct = !direct },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = direct && directAllowed, onCheckedChange = { direct = it }, enabled = directAllowed)
                            Text(ui.getString(R.string.ui_let_this_tv_use_telegram_directly_until_then_3cd46b), color = if (directAllowed) Color.Unspecified else VlcMuted)
                        }
                        Text(
                            if (directAllowed) ui.getString(R.string.ui_otherwise_it_plays_your_telegram_videos_through_this_p_72fc1d)
                            else ui.getString(R.string.ui_not_available_yet_telegram_only_lets_cablegram_end_a_t_cc8d89),
                            color = VlcMuted, fontSize = 12.sp,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                viewModel.confirmPair(
                    if (temporary) TvTrust(temporary = true, expiresAt = choices[selected].second, telegramDirect = direct && directAllowed && linked)
                    else TvTrust.Home,
                )
            }) { Text(ui.getString(R.string.ui_connect_tv_c9f94c)) }
        },
        dismissButton = { TextButton(onClick = viewModel::cancelPendingPair) { Text(ui.getString(R.string.ui_cancel_19766e)) } },
    )
}

@Composable
private fun ChoiceRow(title: String, detail: String, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onSelect), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Column {
            Text(title)
            Text(detail, color = VlcMuted, fontSize = 12.sp)
        }
    }
}

/** Trust line and Telegram actions under a TV in Settings → Your TVs. */
@Composable
internal fun TvTrustActions(viewModel: PhoneViewModel, tv: PairedTv) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val telegramLinked = viewModel.telegramLink?.linked == true && PhoneTelegram.configured
    if (tv.trust.temporary) {
        Text(
            ui.getString(R.string.ui_temporary_unpaired_d52b66, tv.trust.expiresAt?.let(::formatCheckout) ?: "soon") + if (tv.trust.telegramDirect) ui.getString(R.string.ui_uses_telegram_directly_f552f4) else "",
            color = VlcOrange, style = MaterialTheme.typography.bodySmall,
        )
    }
    if (tv.deviceId == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (tv.trust.temporary && tv.trust.expiresAt != null) {
            TextButton(onClick = { viewModel.updateTvTrust(tv, tv.trust.copy(expiresAt = tv.trust.expiresAt.plus(TvTrustRules.EXTEND_BY))) }) { Text(ui.getString(R.string.ui_extend_a_day_c30370)) }
        }
        if (telegramLinked) {
            TextButton(onClick = { viewModel.signOutTvTelegram(tv) }) { Text(ui.getString(R.string.ui_sign_out_of_telegram_1f1dc3)) }
        }
    }
}
