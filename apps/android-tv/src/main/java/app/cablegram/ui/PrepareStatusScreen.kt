package app.cablegram.ui

import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import app.cablegram.ScreenState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val PrepareBg = InkDeep
private val DarkSlate = PanelRaised
private val SkyBlue = Cyan

@Composable
fun PrepareStatusScreen(screen: ScreenState.Resolving, onCancel: () -> Unit) {
    BackHandler(onBack = onCancel)
    if (screen.prepareStage == app.cablegram.data.PHONE_OFFLINE_STAGE && !screen.awaitingApproval) {
        WaitingForPhoneScreen(screen)
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
                if (screen.converting) "Converting Your Movie..." else "Preparing Your Movie...",
                color = Paper,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "This usually takes a minute. Playback starts when it's ready.",
                color = Muted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
            // T075: approval waits and other backend labels surface here so
            // the wait is never silent on the TV.
            screen.prepareLabel?.takeIf { it.isNotBlank() }?.let { label ->
                Text(
                    label,
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
                val posterUrl = screen.video.posterUrl
                if (!posterUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = posterUrl,
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
            // T075 / R-5: during an approval wait the frozen "Finding" steps
            // converse nothing — swap them for the big permit-callout here.
            // The timeline stays for every other occasion.
            if (screen.awaitingApproval) {
                ApprovalPermitPanel(
                    title = screen.video.displayTitle(),
                    posterUrl = screen.video.posterUrl,
                    modifier = Modifier.weight(1f),
                )
            } else {
                PrepareTimelineList(statuses, Modifier.weight(1f))
            }
            Text("Press Back to browse library", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
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

/**
 * Phone-hosted titles stream straight from the phone; there is nothing to
 * download or convert, so a step timeline would sit at 0% forever. Say what is
 * actually missing and how to fix it while playback keeps retrying.
 */
@Composable
private fun WaitingForPhoneScreen(screen: ScreenState.Resolving) {
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "phone-wait")
    val alpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(900),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "phone-wait-alpha",
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Ink, InkDeep)))
            .padding(horizontal = 72.dp, vertical = 48.dp),
    ) {
        BrandMark()
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(168.dp)
                    .height(252.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(placeholderGradient(screen.video.showTitle()))),
                contentAlignment = Alignment.Center,
            ) {
                Text(monogram(screen.video.showTitle()), color = Color.White.copy(alpha = 0.9f), fontSize = 44.sp, fontWeight = FontWeight.Bold)
                screen.video.posterUrl?.let { url ->
                    AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Column(Modifier.width(520.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Cyan.copy(alpha = alpha)))
                    Text("Looking for your phone", color = Cyan, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    screen.video.displayTitle(),
                    color = Paper,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "This video lives on your phone. Playback starts by itself as soon as the phone is reachable.",
                    color = Muted,
                    fontSize = 16.sp,
                    lineHeight = 23.sp,
                )
                Spacer(Modifier.height(22.dp))
                listOf(
                    "Open Cablegram on the phone that has this video",
                    "Connect the phone to the same Wi‑Fi as this TV",
                    "Keep the phone awake until playback starts",
                ).forEachIndexed { index, step ->
                    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(26.dp).clip(CircleShape).background(PanelRaised),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${index + 1}", color = Paper, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(step, color = Paper.copy(alpha = 0.88f), fontSize = 16.sp, modifier = Modifier.padding(start = 12.dp))
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text("Press Back to return to your library", color = Muted, fontSize = 13.sp)
            }
        }
    }
}

/**
 * T075 / R-5: during an approval wait the playback steps are meaningless
 * ("Finding the movie" would sit frozen). Show a large, unmistakable panel
 * telling the viewer a phone must permit this play.
 */
@Composable
private fun ApprovalPermitPanel(
    title: String,
    posterUrl: String?,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PrepareBg.copy(alpha = 0.88f))
            .border(2.dp, SkyBlue, shape)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        posterUrl?.takeIf { it.isNotBlank() }?.let { poster ->
            AsyncImage(
                model = poster,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .height(120.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(DarkSlate),
            )
        }
        Text(
            "ALMOST THERE",
            color = SkyBlue,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
        )
        Text(
            "Permit this play on your phone",
            color = Paper,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 26.sp,
        )
        Text(
            "This title is private. Open the Cablegram notification on your phone and tap “Allow once”.",
            color = Muted,
            fontSize = 13.sp,
            lineHeight = 17.sp,
        )
    }
}

@Composable
private fun PreparePercentBar(progress: Int) {
    val animatedPercent by animateIntAsState(progress.coerceIn(0, 100), label = "prepare-percent")
    val fraction by animateFloatAsState((progress / 100f).coerceIn(0f, 1f), label = "prepare-bar")
    Column(Modifier.fillMaxWidth()) {
        Text(
            "$animatedPercent%",
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
                step.title,
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
                step.rangeLabel,
                color = if (active) Cyan else Muted.copy(alpha = if (completed) 0.8f else 0.45f),
                fontSize = 9.sp,
            )
        }
    }
}

@Composable
private fun LoopingPrepareVideoFrame(loadingVideoUrl: String?) {
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
            Text("Playing until your stream is ready", color = Paper.copy(alpha = 0.8f), fontSize = 12.sp)
        }
    }
}

@Composable
internal fun LoopingPrepareVideo(loadingVideoUrl: String?) {
    val scope = rememberCoroutineScope()
    var restartJob by remember { mutableStateOf<Job?>(null) }
    var videoError by remember(loadingVideoUrl) { mutableStateOf(false) }
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }

    DisposableEffect(loadingVideoUrl) {
        onDispose {
            restartJob?.cancel()
            restartJob = null
            videoViewRef?.stopPlayback()
            videoViewRef = null
        }
    }

    if (loadingVideoUrl != null && !videoError) {
        key(loadingVideoUrl) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    VideoView(context).apply {
                        videoViewRef = this
                        setVideoURI(Uri.parse(loadingVideoUrl))
                        setOnPreparedListener { start() }
                        setOnCompletionListener {
                            restartJob?.cancel()
                            restartJob = scope.launch {
                                delay(10_000L)
                                videoViewRef?.let { view ->
                                    try {
                                        view.seekTo(0)
                                        view.start()
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                        }
                        setOnErrorListener { _, _, _ ->
                            videoError = true
                            true
                        }
                    }
                },
            )
        }
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("CABLEGRAM", color = Cyan.copy(alpha = 0.5f), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PrepareTipCard() {
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
        Text("KEEP YOUR PHONE NEARBY", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Text("Cablegram is getting the best available stream ready.", color = Paper, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text("You can leave this screen with Back and return to the title later.", color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
    }
}
