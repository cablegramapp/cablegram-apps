package app.cablegram.phone

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** The welcome tour's pages, in order. Each feature page names where the setup lives and can open it. */
enum class TourStep { Welcome, Tv, Telegram, Drive, Subtitles, Ready }

private class TourPage(
    val step: TourStep,
    val icon: ImageVector,
    val title: String,
    val body: String,
    val points: List<String>,
    /** Where the setup lives, as the path a person taps through. */
    val where: String? = null,
    val action: String? = null,
    val recommended: Boolean = false,
)

private val TOUR_PAGES = listOf(
    TourPage(
        TourStep.Welcome, Icons.Default.Movie,
        "Your videos.\nYour big screen.",
        "Cablegram turns your phone into the library and remote for your TV. Here's a quick look at what you can set up. Everything is optional.",
        listOf(
            "Your phone keeps the library and does the setup.",
            "Your TV plays your videos.",
            "Your videos stay in storage you own. Cablegram doesn't provide movies.",
        ),
    ),
    TourPage(
        TourStep.Tv, Icons.Default.Tv,
        "Connect your TV",
        "Install Cablegram on your Android TV or Google TV and open it. Then enter the six-digit code it shows.",
        listOf(
            "Pick a title on your phone and it plays on the TV.",
            "Add more TVs whenever you like.",
            "Keep your phone and TV on the same Wi-Fi to stream from the phone.",
        ),
        where = "Settings › Your TVs › Connect a TV",
        action = "Connect a TV",
    ),
    TourPage(
        TourStep.Telegram, Icons.AutoMirrored.Filled.Send,
        "Watch without your phone",
        "Connect your own Telegram account. A private Cablegram channel becomes cloud storage for your videos, using the space that comes with your account.",
        listOf(
            "Your TV plays straight from Telegram, so your phone can be off or away from home.",
            "Share or forward a video into the private channel and it shows up in your library.",
            "Cablegram only uses its own private channel, never your chats or contacts. Your number and login code go only to Telegram.",
        ),
        where = "Import › Connected storage › Telegram",
        action = "Connect Telegram",
        recommended = true,
    ),
    TourPage(
        TourStep.Drive, Icons.Default.Cloud,
        "Keep a copy in Google Drive",
        "Save a cloud copy of any title to your own Google Drive. Your TV can play it when your phone isn't around, and you can free up space on the phone.",
        listOf(
            "Sign in with Google. It takes about a minute.",
            "Cablegram can only reach the files it creates, never the rest of your Drive.",
            "Have your own bucket? Cloudflare R2 works too.",
        ),
        where = "Settings › Storage › Manage cloud storage, or Save to cloud on a title",
        action = "Connect Google Drive",
    ),
    TourPage(
        TourStep.Subtitles, Icons.Default.Subtitles,
        "Subtitles in your language",
        "Open a title and tap Find subtitles. Cablegram finds subtitles that match your file and sends the one you choose to your TV.",
        listOf(
            "Uses your own free account at SubDL or OpenSubtitles. One is enough.",
            "Searches go straight from your phone to that site.",
            "Subtitle files saved next to your video are picked up too.",
        ),
        where = "Settings › Subtitle accounts, then a title › Find subtitles",
        action = "Add a subtitle account",
    ),
    TourPage(
        TourStep.Ready, Icons.Default.PlayCircle,
        "You're all set",
        "Start by importing a few videos from your phone. You can open this tour again from Settings.",
        emptyList(),
        action = "Import videos",
    ),
)

/** Already set up: the page shows that instead of its setup button. */
private fun connectedLabel(viewModel: PhoneViewModel, step: TourStep, subtitlesConnected: Boolean): String? = when (step) {
    TourStep.Tv -> "TV connected".takeIf { viewModel.paired }
    TourStep.Telegram -> "Telegram connected".takeIf { viewModel.telegramLink?.linked == true }
    TourStep.Drive -> "Cloud storage connected".takeIf { viewModel.cloudConnected }
    TourStep.Subtitles -> "Subtitle account connected".takeIf { subtitlesConnected }
    TourStep.Welcome, TourStep.Ready -> null
}

/** First-run introduction to the phone app: what each feature is for, why, and where to set it up later. */
@Composable
internal fun WelcomeTour(viewModel: PhoneViewModel) {
    val pager = rememberPagerState { TOUR_PAGES.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == TOUR_PAGES.lastIndex
    // Reading a saved account decrypts it: once per credential change, not on every recomposition.
    val subtitlesConnected = remember(viewModel.subtitleCredentialRevision) {
        viewModel.subtitleProviderConfigured("subdl") || viewModel.subtitleProviderConfigured("opensubtitles")
    }
    BackHandler {
        if (pager.currentPage > 0) scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
        else viewModel.closeWelcomeTour()
    }
    Column(Modifier.fillMaxSize().background(VlcBlack).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BrandMark(Modifier.weight(1f), compact = true)
            if (!last) TextButton(onClick = viewModel::closeWelcomeTour, modifier = Modifier.maestro(MaestroIds.TOUR_SKIP)) { Text("Skip") }
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.Top) { index ->
            TourPageContent(TOUR_PAGES[index], connectedLabel(viewModel, TOUR_PAGES[index].step, subtitlesConnected))
        }
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().align(Alignment.CenterHorizontally).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PageDots(pager.currentPage, TOUR_PAGES.size, Modifier.align(Alignment.CenterHorizontally))
            val page = TOUR_PAGES[pager.currentPage]
            if (last) {
                Button(onClick = { viewModel.startFromTour(TourStep.Ready) }, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).maestro(MaestroIds.TOUR_ACTION)) {
                    Text(page.action.orEmpty())
                }
                OutlinedButton(onClick = viewModel::closeWelcomeTour, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Go to my library") }
            } else {
                val done = connectedLabel(viewModel, page.step, subtitlesConnected)
                if (page.action != null && done == null) {
                    OutlinedButton(onClick = { viewModel.startFromTour(page.step) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).maestro(MaestroIds.TOUR_ACTION)) {
                        Text("${page.action} now")
                    }
                }
                Button(
                    onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).maestro(MaestroIds.TOUR_NEXT),
                ) { Text(if (pager.currentPage == 0) "Show me around" else "Next") }
            }
        }
    }
}

@Composable
private fun TourPageContent(page: TourPage, done: String?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp).maestro("${MaestroIds.TOUR_PAGE}_${page.step.name.lowercase()}"),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(color = VlcPanel, shape = RoundedCornerShape(24.dp)) {
                    Icon(page.icon, null, Modifier.padding(20.dp).size(36.dp), tint = VlcOrange)
                }
                if (page.recommended) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(50)) {
                        Text("Recommended", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = VlcOrange, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            ScreenHeading(page.title)
            Text(page.body, color = VlcMuted, style = MaterialTheme.typography.bodyLarge)
            if (page.points.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                page.points.forEach { point ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.CheckCircle, null, Modifier.padding(top = 2.dp).size(18.dp), tint = PhoneSuccess)
                        Text(point, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            page.where?.let { where ->
                Surface(color = VlcPanel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Find it later", color = VlcMuted, style = MaterialTheme.typography.labelMedium)
                        Text(where, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            done?.let { done ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp), tint = PhoneSuccess)
                    Text(done, color = PhoneSuccess, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun PageDots(current: Int, count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier.semantics { contentDescription = "Page ${current + 1} of $count" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val width by animateDpAsState(if (index == current) 20.dp else 6.dp, label = "dot width")
            val color by animateColorAsState(if (index == current) VlcOrange else PhoneOutline, label = "dot color")
            Spacer(Modifier.width(width).height(6.dp).background(color, CircleShape))
        }
    }
}
