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

private fun tourPages(ui: android.content.res.Resources) = listOf(
    TourPage(
        TourStep.Welcome, Icons.Default.Movie,
        ui.getString(R.string.ui_your_videos_your_big_screen_b7a07b),
        ui.getString(R.string.ui_cablegram_turns_your_phone_into_the_library_and_remote_68c907),
        listOf(
            ui.getString(R.string.ui_your_phone_keeps_the_library_and_does_the_setup_e69ab1),
            ui.getString(R.string.ui_your_tv_plays_your_videos_759124),
            ui.getString(R.string.ui_your_videos_stay_in_storage_you_own_cablegram_doesn_t__0ccf72),
        ),
    ),
    TourPage(
        TourStep.Tv, Icons.Default.Tv,
        ui.getString(R.string.ui_connect_your_tv_4ad8a8),
        ui.getString(R.string.ui_install_cablegram_on_your_android_tv_or_google_tv_and__d27b55),
        listOf(
            ui.getString(R.string.ui_pick_a_title_on_your_phone_and_it_plays_on_the_tv_8e206a),
            ui.getString(R.string.ui_add_more_tvs_whenever_you_like_46dfbc),
            ui.getString(R.string.ui_keep_your_phone_and_tv_on_the_same_wi_fi_to_stream_fro_e080cd),
        ),
        where = ui.getString(R.string.ui_settings_your_tvs_connect_a_tv_231c90),
        action = ui.getString(R.string.ui_connect_a_tv_0ff3f5),
    ),
    TourPage(
        TourStep.Telegram, Icons.AutoMirrored.Filled.Send,
        ui.getString(R.string.ui_watch_without_your_phone_7d2fb5),
        ui.getString(R.string.ui_connect_your_own_telegram_account_a_private_cablegram__03df9a),
        listOf(
            ui.getString(R.string.ui_your_tv_plays_straight_from_telegram_so_your_phone_can_a79536),
            ui.getString(R.string.ui_share_or_forward_a_video_into_the_private_channel_and__97bd0c),
            ui.getString(R.string.ui_cablegram_only_uses_its_own_private_channel_never_your_dd2890),
        ),
        where = ui.getString(R.string.ui_import_connected_storage_telegram_f2fb3f),
        action = ui.getString(R.string.ui_connect_telegram_00c799),
        recommended = true,
    ),
    TourPage(
        TourStep.Drive, Icons.Default.Cloud,
        ui.getString(R.string.ui_keep_a_copy_in_google_drive_8b9c00),
        ui.getString(R.string.ui_save_a_cloud_copy_of_any_title_to_your_own_google_driv_3aac39),
        listOf(
            ui.getString(R.string.ui_sign_in_with_google_it_takes_about_a_minute_277553),
            ui.getString(R.string.ui_cablegram_can_only_reach_the_files_it_creates_never_th_69bc5f),
            ui.getString(R.string.ui_have_your_own_bucket_cloudflare_r2_works_too_431345),
        ),
        where = ui.getString(R.string.ui_settings_storage_manage_cloud_storage_or_save_to_cloud_80ea66),
        action = ui.getString(R.string.ui_connect_google_drive_4406cf),
    ),
    TourPage(
        TourStep.Subtitles, Icons.Default.Subtitles,
        ui.getString(R.string.ui_subtitles_in_your_language_6e5267),
        ui.getString(R.string.ui_open_a_title_and_tap_find_subtitles_cablegram_finds_su_892bc5),
        listOf(
            ui.getString(R.string.ui_uses_your_own_free_account_at_subdl_or_opensubtitles_o_35b12b),
            ui.getString(R.string.ui_searches_go_straight_from_your_phone_to_that_site_f137b0),
            ui.getString(R.string.ui_subtitle_files_saved_next_to_your_video_are_picked_up__665d7f),
        ),
        where = ui.getString(R.string.ui_settings_subtitle_accounts_then_a_title_find_subtitles_60e1db),
        action = ui.getString(R.string.ui_add_a_subtitle_account_ba3be8),
    ),
    TourPage(
        TourStep.Ready, Icons.Default.PlayCircle,
        ui.getString(R.string.ui_you_re_all_set_f08920),
        ui.getString(R.string.ui_start_by_importing_a_few_videos_from_your_phone_you_ca_1fb185),
        emptyList(),
        action = ui.getString(R.string.ui_import_videos_fec728),
    ),
)

/** Already set up: the page shows that instead of its setup button. */
private fun connectedLabel(viewModel: PhoneViewModel, step: TourStep, subtitlesConnected: Boolean, ui: android.content.res.Resources): String? = when (step) {
    TourStep.Tv -> ui.getString(R.string.ui_tv_connected_5710cb).takeIf { viewModel.paired }
    TourStep.Telegram -> ui.getString(R.string.ui_telegram_connected_815464).takeIf { viewModel.telegramSignedInHere }
    TourStep.Drive -> ui.getString(R.string.ui_cloud_storage_connected_fe282c).takeIf { viewModel.cloudConnected }
    TourStep.Subtitles -> ui.getString(R.string.ui_subtitle_account_connected_c16774).takeIf { subtitlesConnected }
    TourStep.Welcome, TourStep.Ready -> null
}

/** First-run introduction to the phone app: what each feature is for, why, and where to set it up later. */
@Composable
internal fun WelcomeTour(viewModel: PhoneViewModel) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val pages = remember(ui.configuration) { tourPages(ui) }
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == pages.lastIndex
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
            if (!last) TextButton(onClick = viewModel::closeWelcomeTour, modifier = Modifier.maestro(MaestroIds.TOUR_SKIP)) { Text(ui.getString(R.string.ui_skip_28d035)) }
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.Top) { index ->
            TourPageContent(pages[index], connectedLabel(viewModel, pages[index].step, subtitlesConnected, ui))
        }
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().align(Alignment.CenterHorizontally).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PageDots(pager.currentPage, pages.size, Modifier.align(Alignment.CenterHorizontally))
            val page = pages[pager.currentPage]
            if (last) {
                Button(onClick = { viewModel.startFromTour(TourStep.Ready) }, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).maestro(MaestroIds.TOUR_ACTION)) {
                    Text(page.action.orEmpty())
                }
                OutlinedButton(onClick = viewModel::closeWelcomeTour, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(ui.getString(R.string.ui_go_to_my_library_f23fc1)) }
            } else {
                val done = connectedLabel(viewModel, page.step, subtitlesConnected, ui)
                if (page.action != null && done == null) {
                    OutlinedButton(onClick = { viewModel.startFromTour(page.step) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).maestro(MaestroIds.TOUR_ACTION)) {
                        Text(ui.getString(R.string.ui_now_cecd79, page.action))
                    }
                }
                Button(
                    onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).maestro(MaestroIds.TOUR_NEXT),
                ) { Text(if (pager.currentPage == 0) ui.getString(R.string.ui_show_me_around_80afe5) else ui.getString(R.string.ui_next_1ff57a)) }
            }
        }
    }
}

@Composable
private fun TourPageContent(page: TourPage, done: String?) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
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
                        Text(ui.getString(R.string.ui_recommended_d70604), Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = VlcOrange, style = MaterialTheme.typography.labelMedium)
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
                        Text(ui.getString(R.string.ui_find_it_later_bcf629), color = VlcMuted, style = MaterialTheme.typography.labelMedium)
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
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    Row(
        modifier.semantics { contentDescription = ui.getString(R.string.ui_page_of_8b9ffa, (current + 1).toString(), (count).toString()) },
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
