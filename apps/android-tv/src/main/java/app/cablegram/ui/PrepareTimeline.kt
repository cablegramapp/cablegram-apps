package app.cablegram.ui

enum class PrepareStepStatus { COMPLETED, ACTIVE, PENDING }

data class PrepareStep(
    val id: String,
    val title: String,
    val rangeLabel: String,
    val minProgress: Int,
    val stages: Set<String>,
)

val PREPARE_STEPS = listOf(
    PrepareStep("catalog", "Finding the movie", "Starting", 6, setOf("catalog")),
    PrepareStep("queued", "Waiting in line", "Queued", 11, setOf("queued")),
    PrepareStep("downloading", "Downloading your video", "Downloading", 16, setOf("downloading")),
    PrepareStep("probing", "Checking the file", "Checking", 72, setOf("probing", "processing")),
    PrepareStep("copying", "Saving the video", "Saving", 74, setOf("copying")),
    PrepareStep("remuxing", "Getting it ready for TV", "Tuning", 87, setOf("remuxing")),
    PrepareStep("frame", "Making a cover photo", "Cover", 90, setOf("frame", "poster")),
    PrepareStep("uploading", "Keeping a backup", "Backup", 92, setOf("uploading")),
    PrepareStep("cdn", "Sending it to your TV", "Almost there", 94, setOf("cdn")),
    PrepareStep("ready", "Ready to play", "Done", 100, setOf("ready")),
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
