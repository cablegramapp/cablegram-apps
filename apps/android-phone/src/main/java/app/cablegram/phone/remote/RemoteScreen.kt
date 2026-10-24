package app.cablegram.phone.remote

import app.cablegram.phone.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.cablegram.phone.*

@Composable
fun RemoteScreen(viewModel: PhoneViewModel) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    var choosingTv by remember { mutableStateOf(false) }
    var volume by remember(viewModel.tvName) { mutableStateOf(0.5f) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ScreenHeading(ui.getString(R.string.ui_remote_ffa98e), ui.getString(R.string.ui_a_little_closer_to_your_big_screen_57b69f), Modifier.fillMaxWidth())
        if (!viewModel.paired) {
            EmptyState(Icons.Default.Tv, ui.getString(R.string.ui_your_tv_within_reach_2795e6), ui.getString(R.string.ui_connect_a_tv_to_browse_play_and_pause_from_your_phone_e98866), ui.getString(R.string.ui_connect_a_tv_0ff3f5), viewModel::startAddTv)
            return@Column
        }
        Box {
            Surface(onClick = { choosingTv = true }, color = VlcPanel, shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Default.Tv, null, tint = VlcOrange)
                    Column(Modifier.weight(1f)) {
                        Text(ui.getString(R.string.ui_controlling_2084c5), style = MaterialTheme.typography.labelSmall, color = VlcMuted)
                        Text(viewModel.tvName, style = MaterialTheme.typography.titleMedium, color = Color.White)
                    }
                    Icon(Icons.Default.ExpandMore, ui.getString(R.string.ui_choose_tv_07f8b4), tint = VlcMuted)
                }
            }
            DropdownMenu(expanded = choosingTv, onDismissRequest = { choosingTv = false }) {
                viewModel.tvs.forEach { tv ->
                    DropdownMenuItem(text = { Text(tv.name) }, onClick = { viewModel.selectTv(tv); choosingTv = false })
                }
                DropdownMenuItem(text = { Text(ui.getString(R.string.ui_connect_another_tv_c29c2b)) }, onClick = { choosingTv = false; viewModel.startAddTv() })
            }
        }
        Surface(color = VlcPanel, shape = CircleShape) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                RemoteKey(Icons.Default.KeyboardArrowUp, ui.getString(R.string.ui_navigate_up_5fa84b)) { viewModel.remoteNavigate("up") }
                app.cablegram.phone.LtrControls {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RemoteKey(Icons.Default.KeyboardArrowLeft, ui.getString(R.string.ui_navigate_left_ceb18f)) { viewModel.remoteNavigate("left") }
                        FilledIconButton(onClick = viewModel::remoteSelect, modifier = Modifier.size(68.dp)) {
                            Text(ui.getString(R.string.ui_ok_565339), style = MaterialTheme.typography.titleMedium)
                        }
                        RemoteKey(Icons.Default.KeyboardArrowRight, ui.getString(R.string.ui_navigate_right_668ca1)) { viewModel.remoteNavigate("right") }
                    }
                }
                RemoteKey(Icons.Default.KeyboardArrowDown, ui.getString(R.string.ui_navigate_down_b9d466)) { viewModel.remoteNavigate("down") }
            }
        }
        SectionCard(ui.getString(R.string.ui_playback_2db1db)) {
            viewModel.nowPlaying?.let { Text(it.title, color = Color.White, style = MaterialTheme.typography.titleMedium) }
            app.cablegram.phone.LtrControls {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    RemoteKey(Icons.Default.FastRewind, ui.getString(R.string.ui_back_seconds_fa7e12, (CastRemoteReceiver.SEEK_SECONDS).toString())) { viewModel.skipSeconds(-CastRemoteReceiver.SEEK_SECONDS) }
                    FilledIconButton(onClick = viewModel::togglePlayPause, modifier = Modifier.size(64.dp)) {
                        Icon(if (viewModel.paused) Icons.Default.PlayArrow else Icons.Default.Pause, if (viewModel.paused) ui.getString(R.string.ui_resume_playback_b19825) else ui.getString(R.string.ui_pause_playback_65a4a8), Modifier.size(32.dp))
                    }
                    RemoteKey(Icons.Default.FastForward, ui.getString(R.string.ui_forward_seconds_55855b, (CastRemoteReceiver.SEEK_SECONDS).toString())) { viewModel.skipSeconds(CastRemoteReceiver.SEEK_SECONDS) }
                }
            }
            Text(ui.getString(R.string.ui_seek_seconds_at_a_time_43ffed, (CastRemoteReceiver.SEEK_SECONDS).toString()), color = VlcMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.CenterHorizontally))
            OutlinedButton(onClick = viewModel::remoteStop, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Stop, null); Spacer(Modifier.width(8.dp)); Text(ui.getString(R.string.ui_stop_playback_19b81a)) }
        }
        SectionCard(ui.getString(R.string.ui_set_tv_volume_43ad09)) {
            Slider(value = volume, onValueChange = { volume = it }, onValueChangeFinished = { viewModel.remoteVolume((volume * 100).toInt()) })
            Text(ui.getString(R.string.ui_drag_to_set_the_volume_to_448bd2, ((volume * 100).toInt()).toString()), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
        }
        viewModel.remoteStatus?.let { StatusNote(it) }
        Text(ui.getString(R.string.ui_commands_use_your_household_connection_keep_the_tv_con_b044c4), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun RemoteKey(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(56.dp)) { Icon(icon, label, Modifier.size(28.dp), tint = Color.White) }
}
