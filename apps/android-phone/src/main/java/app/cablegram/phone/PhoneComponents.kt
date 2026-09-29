package app.cablegram.phone

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decode local artwork off the UI thread and discard stale results when a tile changes. */
@Composable
internal fun MediaArtwork(item: LibraryItem, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(null, item.posterPath, item.posterVersion) {
        value = null
        value = withContext(Dispatchers.IO) { decodePosterBitmap(item.posterPath) }
    }
    Box(
        modifier.clip(RoundedCornerShape(16.dp)).background(
            Brush.linearGradient(listOf(Color(0xFF343A47), Color(0xFF22252D), Color(0xFF3E342D))),
        ),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Icon(Icons.Default.Movie, null, Modifier.size(36.dp), tint = VlcMuted.copy(alpha = 0.65f))
    }
}

@Composable
internal fun ScreenHeading(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, color = Color.White)
        if (!subtitle.isNullOrBlank()) Text(subtitle, color = VlcMuted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White)
        Surface(color = VlcPanel, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, PhoneOutline.copy(alpha = 0.55f))) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

@Composable
internal fun StatusNote(message: String, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), color = VlcPanel, shape = RoundedCornerShape(12.dp)) {
        Text(message, Modifier.padding(14.dp), color = VlcMuted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun EmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    action: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(color = VlcPanel, shape = RoundedCornerShape(24.dp)) {
            Icon(icon, null, Modifier.padding(22.dp).size(36.dp), tint = VlcOrange)
        }
        Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(description, color = VlcMuted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Button(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) }
    }
}

/**
 * The Cablegram mark: the same glyph and wordmark the TV app shows, so both
 * apps read as one product. [compact] fits above a screen heading.
 */
@Composable
fun BrandMark(modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
    ) {
        Image(
            painter = androidx.compose.ui.res.painterResource(R.drawable.cablegram_brand_mark),
            contentDescription = null,
            modifier = Modifier.height(if (compact) 22.dp else 34.dp),
        )
        Text(
            "Cablegram",
            color = Color.White,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            fontSize = if (compact) 15.sp else 20.sp,
        )
    }
}
