package app.cablegram.ui

import app.cablegram.R
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.tv.material3.Button
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import app.cablegram.ScreenState

private val PrepareBg = InkDeep
private val DarkSlate = PanelRaised
private val SkyBlue = Cyan

@Composable
fun PrepareStatusScreen(screen: ScreenState.Resolving, onCancel: () -> Unit, onClipStarted: () -> Unit = {}, onStartNow: () -> Unit = {}) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    BackHandler(onBack = onCancel)
    if (screen.awaitingApproval || screen.signatureAttemptId != null || screen.prepareStage == app.cablegram.data.PHONE_OFFLINE_STAGE) {
        SignaturePreparingScreen(screen, onClipStarted, onStartNow)
        return
    }
    val progress = screen.prepareProgress?.coerceIn(0, 100) ?: 0
    val statuses = remember(screen.prepareProgress, screen.prepareStage, screen.converting) {
        prepareStepStatuses(screen.prepareProgress, screen.prepareStage, screen.converting)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PrepareBg)
            .padding(horizontal = 28.dp, vertical = 18.dp),
    ) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.weight(0.6f).fillMaxHeight()) {
            Text("CABLEGRAM", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                if (screen.converting) ui.getString(R.string.ui_converting_your_movie_3a5267) else ui.getString(R.string.ui_preparing_your_movie_f22b39),
                color = Paper,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                ui.getString(R.string.ui_this_usually_takes_a_minute_playback_starts_when_it_s__fb7bed),
                color = Muted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
            // T075: approval waits and other backend labels surface here so
            // the wait is never silent on the TV.
            screen.prepareLabel?.takeIf { it.isNotBlank() }?.let {
                Text(
                    localizedStageLabel(screen, ui),
                    color = SkyBlue,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val poster = screen.video.posterModel()
                if (poster != null) {
                    AsyncImage(
                        model = poster,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(36.dp)
                            .height(54.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(DarkSlate),
                    )
                }
                Text(
                    screen.video.displayTitle(),
                    color = Paper.copy(alpha = 0.72f),
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            PreparePercentBar(progress)
            Spacer(Modifier.height(10.dp))
            PrepareTimelineList(statuses, Modifier.weight(1f))
            Text(ui.getString(R.string.ui_press_back_to_browse_library_1b206d), color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
        }
        Column(
            Modifier.weight(0.4f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LoopingPrepareVideoFrame(loadingVideoUrl = screen.loadingVideoUrl)
            }
            PrepareTipCard()
        }
    }
}
}

/** Keep the signature video in view while the phone asks for this one-time approval. */
@Composable
private fun SignaturePreparingScreen(screen: ScreenState.Resolving, onClipStarted: () -> Unit, onStartNow: () -> Unit) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val offline = screen.prepareStage == app.cablegram.data.PHONE_OFFLINE_STAGE
    val visibility by animateFloatAsState(if (screen.fadingOut) 0f else 1f, tween(400), label = "signature-fade")
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(PrepareBg)
            .alpha(visibility)
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(0.6f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(18.dp))
                .background(DarkSlate),
        ) {
            // While the phone is offline nothing can start, so the error animation has no clip timing to report.
            if (offline) ErrorAnimation(screen.signatureAttemptId ?: screen.video.id)
            else androidx.compose.runtime.key(screen.signatureAttemptId ?: screen.video.id) {
                SignatureVideo(screen.loadingVideoUrl, onStarted = onClipStarted)
            }
        }
        Column(
            modifier = Modifier.weight(0.4f).fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("CABLEGRAM", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            Spacer(Modifier.height(20.dp))
            Text(when { screen.awaitingApproval -> ui.getString(R.string.ui_waiting_for_approval_10c573); offline -> ui.getString(R.string.ui_looking_for_your_phone_d386a9); else -> ui.getString(R.string.ui_preparing_your_video_7daf7e) }, color = Paper, fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                screen.video.posterModel()?.let { poster ->
                    AsyncImage(
                        model = poster,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.width(40.dp).height(60.dp).clip(RoundedCornerShape(4.dp)).background(DarkSlate),
                    )
                }
                Text(screen.video.displayTitle(), color = Paper, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(20.dp))
            Text(
                when { screen.awaitingApproval -> ui.getString(R.string.ui_allow_this_play_on_your_phone_8a268c); offline -> ui.getString(R.string.ui_open_cablegram_on_your_phone_1bc6dd); else -> ui.getString(R.string.ui_getting_your_video_ready_09b497) },
                color = Cyan, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 25.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                when {
                    screen.awaitingApproval -> ui.getString(R.string.ui_this_title_is_private_open_cablegram_or_its_notificati_d68ff8)
                    offline -> ui.getString(R.string.ui_connect_the_phone_that_has_this_video_to_the_same_wi_f_5fe0b3)
                    else -> screen.prepareStage?.let { localizedStageLabel(screen, ui) } ?: ui.getString(R.string.ui_enjoy_a_little_cablegram_while_we_prepare_your_video_b47aa0)
                },
                color = Paper.copy(alpha = 0.88f), fontSize = 16.sp, lineHeight = 23.sp,
            )
            Spacer(Modifier.height(14.dp))
            if (screen.readyToStart) {
                val startFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { withFrameNanos { }; runCatching { startFocus.requestFocus() } }
                Button(onClick = onStartNow, modifier = Modifier.focusRequester(startFocus)) { Text(ui.getString(R.string.ui_start_now_9ff3a6)) }
            } else {
                Text(if (screen.awaitingApproval) ui.getString(R.string.ui_your_title_starts_automatically_after_approval_1cc12a) else ui.getString(R.string.ui_playback_starts_automatically_when_your_video_is_ready_6d9609), color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
            }
            Spacer(Modifier.height(24.dp))
            Text(ui.getString(R.string.ui_press_back_to_cancel_and_return_to_your_library_35e34a), color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun PreparePercentBar(progress: Int) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val animatedPercent by animateIntAsState(progress.coerceIn(0, 100), label = "prepare-percent")
    val fraction by animateFloatAsState((progress / 100f).coerceIn(0f, 1f), label = "prepare-bar")
    Column(Modifier.fillMaxWidth()) {
        Text(
            ui.getString(R.string.ui_message_b6bb55, (animatedPercent).toString()),
            color = Cyan,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 24.sp,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .drawBehind {
                    drawRoundRect(
                        color = Cyan.copy(alpha = 0.12f),
                        cornerRadius = CornerRadius(11.dp.toPx()),
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceAtLeast(0.03f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(11.dp))
                    .background(Brush.horizontalGradient(listOf(SkyBlue, Cyan)))
                    .drawBehind {
                        drawRoundRect(
                            brush = Brush.horizontalGradient(listOf(Color.Transparent, Cyan.copy(alpha = 0.55f))),
                            cornerRadius = CornerRadius(11.dp.toPx()),
                        )
                    },
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceAtLeast(0.03f)),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    Modifier
                        .size(14.dp)
                        .offset(x = 6.dp)
                        .drawBehind {
                            drawCircle(Cyan.copy(alpha = 0.28f), radius = size.minDimension)
                            drawCircle(Cyan, radius = size.minDimension * 0.42f)
                        },
                )
            }
        }
    }
}

@Composable
private fun PrepareTimelineList(statuses: List<PrepareStepStatus>, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .padding(start = 6.dp, top = 6.dp, bottom = 6.dp)
                .width(2.dp)
                .fillMaxHeight()
                .background(DarkSlate),
        )
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            PREPARE_STEPS.forEachIndexed { index, step ->
                PrepareTimelineRow(step, statuses[index])
            }
        }
    }
}

@Composable
private fun PrepareTimelineRow(step: PrepareStep, status: PrepareStepStatus) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val active = status == PrepareStepStatus.ACTIVE
    val completed = status == PrepareStepStatus.COMPLETED
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (active) {
                    Modifier
                        .drawBehind {
                            drawRoundRect(
                                color = Cyan.copy(alpha = 0.16f),
                                cornerRadius = CornerRadius(8.dp.toPx()),
                                style = Stroke(width = 6.dp.toPx()),
                            )
                        }
                        .clip(shape)
                        .background(DarkSlate)
                        .border(1.dp, Cyan, shape)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                } else {
                    Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    when {
                        active -> Cyan
                        completed -> Cyan.copy(alpha = 0.7f)
                        else -> DarkSlate
                    },
                )
                .border(1.dp, if (status == PrepareStepStatus.PENDING) Slate else Cyan, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                step.titleRes?.let(ui::getString) ?: step.title,
                color = when {
                    active -> Paper
                    completed -> Paper.copy(alpha = 0.62f)
                    else -> Paper.copy(alpha = 0.32f)
                },
                fontSize = if (active) 11.sp else 10.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                step.rangeRes?.let(ui::getString) ?: step.rangeLabel,
                color = if (active) Cyan else Muted.copy(alpha = if (completed) 0.8f else 0.45f),
                fontSize = 9.sp,
            )
        }
    }
}

@Composable
private fun LoopingPrepareVideoFrame(loadingVideoUrl: String?) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .background(DarkSlate)
            .border(1.5.dp, Cyan.copy(alpha = 0.7f), shape),
    ) {
        LoopingPrepareVideo(loadingVideoUrl)
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, PrepareBg.copy(alpha = 0.88f))))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(ui.getString(R.string.ui_playing_until_your_stream_is_ready_906bd0), color = Paper.copy(alpha = 0.8f), fontSize = 12.sp)
        }
    }
}

@Composable
internal fun LoopingPrepareVideo(loadingVideoUrl: String?) {
    SignatureVideo(loadingVideoUrl)
}

@Composable
private fun PrepareTipCard() {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(DarkSlate)
            .border(1.dp, Cyan.copy(alpha = 0.32f), shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(ui.getString(R.string.ui_keep_your_phone_nearby_2dc5e4), color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Text(ui.getString(R.string.ui_cablegram_is_getting_the_best_available_stream_ready_52cd87), color = Paper, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(ui.getString(R.string.ui_you_can_leave_this_screen_with_back_and_return_to_the__848751), color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
    }
}

private fun localizedStageLabel(screen: ScreenState.Resolving, ui: android.content.res.Resources): String {
    val step = PREPARE_STEPS[resolvePrepareStepIndex(screen.prepareProgress, screen.prepareStage, screen.converting)]
    return step.titleRes?.let(ui::getString) ?: step.title
}
