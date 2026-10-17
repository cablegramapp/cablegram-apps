package app.cablegram.ui

import app.cablegram.R

enum class PrepareStepStatus { COMPLETED, ACTIVE, PENDING }

data class PrepareStep(
    val id: String,
    val title: String,
    val rangeLabel: String,
    val minProgress: Int,
    val stages: Set<String>,
    val titleRes: Int? = null,
    val rangeRes: Int? = null,
)

val PREPARE_STEPS = listOf(
    PrepareStep("catalog", "Finding the movie", "Starting", 6, setOf("catalog"), titleRes = R.string.ui_finding_the_movie_515229, rangeRes = R.string.ui_starting_aeed4d),
    PrepareStep("queued", "Waiting in line", "Queued", 11, setOf("queued"), titleRes = R.string.ui_waiting_in_line_28cc95, rangeRes = R.string.ui_queued_661ff4),
    PrepareStep("downloading", "Downloading your video", "Downloading", 16, setOf("downloading"), titleRes = R.string.ui_downloading_your_video_4a7a5a, rangeRes = R.string.ui_downloading_37b345),
    PrepareStep("probing", "Checking the file", "Checking", 72, setOf("probing", "processing"), titleRes = R.string.ui_checking_the_file_1c7df4, rangeRes = R.string.ui_checking_0dfe1d),
    PrepareStep("copying", "Saving the video", "Saving", 74, setOf("copying"), titleRes = R.string.ui_saving_the_video_829213, rangeRes = R.string.ui_saving_096b73),
    PrepareStep("remuxing", "Getting it ready for TV", "Tuning", 87, setOf("remuxing"), titleRes = R.string.ui_getting_it_ready_for_tv_f762f4, rangeRes = R.string.ui_tuning_2abeef),
    PrepareStep("frame", "Making a cover photo", "Cover", 90, setOf("frame", "poster"), titleRes = R.string.ui_making_a_cover_photo_b9f1ef, rangeRes = R.string.ui_cover_fa8d84),
    PrepareStep("uploading", "Keeping a backup", "Backup", 92, setOf("uploading"), titleRes = R.string.ui_keeping_a_backup_7c73a2, rangeRes = R.string.ui_backup_838557),
    PrepareStep("cdn", "Sending it to your TV", "Almost there", 94, setOf("cdn"), titleRes = R.string.ui_sending_it_to_your_tv_aad4d0, rangeRes = R.string.ui_almost_there_750a35),
    PrepareStep("ready", "Ready to play", "Done", 100, setOf("ready"), titleRes = R.string.ui_ready_to_play_6dbc08, rangeRes = R.string.ui_done_11a676),
)

fun resolvePrepareStepIndex(progress: Int?, stage: String?, converting: Boolean = false): Int {
    if (converting) {
        return when {
            (progress ?: 0) >= 45 || stage == "running" -> 5
            else -> 1
        }
    }
    val normalized = stage?.lowercase()
    val byStage = PREPARE_STEPS.indexOfFirst { normalized != null && normalized in it.stages }
    if (byStage >= 0) return byStage
    val pct = (progress ?: 0).coerceIn(0, 100)
    return PREPARE_STEPS.indexOfLast { pct >= it.minProgress }.coerceAtLeast(0)
}

fun prepareStepStatuses(progress: Int?, stage: String?, converting: Boolean = false): List<PrepareStepStatus> {
    val active = resolvePrepareStepIndex(progress, stage, converting)
    val pct = (progress ?: 0).coerceIn(0, 100)
    return PREPARE_STEPS.mapIndexed { index, step ->
        when {
            pct >= 100 || stage == "ready" -> if (index == PREPARE_STEPS.lastIndex) PrepareStepStatus.ACTIVE else PrepareStepStatus.COMPLETED
            index < active -> PrepareStepStatus.COMPLETED
            index == active -> PrepareStepStatus.ACTIVE
            else -> PrepareStepStatus.PENDING
        }
    }
}
