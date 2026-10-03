package app.cablegram.phone.remote

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
    var choosingTv by remember { mutableStateOf(false) }
    var volume by remember(viewModel.tvName) { mutableStateOf(0.5f) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ScreenHeading("Remote", "A little closer to your big screen.", Modifier.fillMaxWidth())
        if (!viewModel.paired) {
            EmptyState(Icons.Default.Tv, "Your TV, within reach", "Connect a TV to browse, play, and pause from your phone.", "Connect a TV", viewModel::startAddTv)
            return@Column
        }
        Box {
            Surface(onClick = { choosingTv = true }, color = VlcPanel, shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Default.Tv, null, tint = VlcOrange)
                    Column(Modifier.weight(1f)) {
                        Text("CONTROLLING", style = MaterialTheme.typography.labelSmall, color = VlcMuted)
                        Text(viewModel.tvName, style = MaterialTheme.typography.titleMedium, color = Color.White)
                    }
                    Icon(Icons.Default.ExpandMore, "Choose TV", tint = VlcMuted)
                }
            }
            DropdownMenu(expanded = choosingTv, onDismissRequest = { choosingTv = false }) {
                viewModel.tvs.forEach { tv ->
                    DropdownMenuItem(text = { Text(tv.name) }, onClick = { viewModel.selectTv(tv); choosingTv = false })
                }
                DropdownMenuItem(text = { Text("Connect another TV") }, onClick = { choosingTv = false; viewModel.startAddTv() })
            }
        }
        Surface(color = VlcPanel, shape = CircleShape) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                RemoteKey(Icons.Default.KeyboardArrowUp, "Navigate up") { viewModel.remoteNavigate("up") }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RemoteKey(Icons.Default.KeyboardArrowLeft, "Navigate left") { viewModel.remoteNavigate("left") }
                    FilledIconButton(onClick = viewModel::remoteSelect, modifier = Modifier.size(68.dp)) {
                        Text("OK", style = MaterialTheme.typography.titleMedium)
                    }
                    RemoteKey(Icons.Default.KeyboardArrowRight, "Navigate right") { viewModel.remoteNavigate("right") }
                }
                RemoteKey(Icons.Default.KeyboardArrowDown, "Navigate down") { viewModel.remoteNavigate("down") }
            }
        }
        SectionCard("Playback") {
            viewModel.nowPlaying?.let { Text(it.title, color = Color.White, style = MaterialTheme.typography.titleMedium) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                RemoteKey(Icons.Default.FastRewind, "Back ${CastRemoteReceiver.SEEK_SECONDS} seconds") { viewModel.skipSeconds(-CastRemoteReceiver.SEEK_SECONDS) }
                FilledIconButton(onClick = viewModel::togglePlayPause, modifier = Modifier.size(64.dp)) {
                    Icon(if (viewModel.paused) Icons.Default.PlayArrow else Icons.Default.Pause, if (viewModel.paused) "Resume playback" else "Pause playback", Modifier.size(32.dp))
                }
                RemoteKey(Icons.Default.FastForward, "Forward ${CastRemoteReceiver.SEEK_SECONDS} seconds") { viewModel.skipSeconds(CastRemoteReceiver.SEEK_SECONDS) }
            }
            Text("Seek ${CastRemoteReceiver.SEEK_SECONDS} seconds at a time", color = VlcMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.CenterHorizontally))
            OutlinedButton(onClick = viewModel::remoteStop, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Stop, null); Spacer(Modifier.width(8.dp)); Text("Stop playback") }
        }
        SectionCard("Set TV volume") {
            Slider(value = volume, onValueChange = { volume = it }, onValueChangeFinished = { viewModel.remoteVolume((volume * 100).toInt()) })
            Text("Drag to set the volume to ${(volume * 100).toInt()}%", color = VlcMuted, style = MaterialTheme.typography.bodySmall)
        }
        viewModel.remoteStatus?.let { StatusNote(it) }
        Text("Commands use your household connection. Keep the TV connected to the internet.", color = VlcMuted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RemoteKey(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(56.dp)) { Icon(icon, label, Modifier.size(28.dp), tint = Color.White) }
}
