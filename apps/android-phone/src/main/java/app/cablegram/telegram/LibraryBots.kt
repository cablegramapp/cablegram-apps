package app.cablegram.telegram

import java.net.URI
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout

const val BOT_HUB_DESCRIPTION = "Discover useful Telegram bots that make managing, organizing, and enjoying your video library easier."
const val BOT_INDEPENDENCE = "These are independent third-party bots and are not developed, owned, or operated by Cablegram."
const val BOT_DISCLAIMER = "This recommendation is shared for informational purposes only. Using the bot is entirely optional and at the user's own discretion and responsibility.\n\nCablegram is not responsible for the bot's operation, content, privacy practices, or any consequences arising from its use. Please review its permissions before adding it to your channel."
const val BOT_PERMISSIONS = "The bot will be added to your Cablegram library channel as an administrator with permission to post messages. Telegram also gives administrators access to channel activity, statistics, and member information. The bot can receive new library posts, including videos and captions. Other optional permissions are off. If it is already an administrator, its existing permissions are kept. Some bots need additional setup in Telegram."

data class RecommendedLibraryBot(val id: Long, val username: String, val displayName: String) {
    val url: String get() = "https://t.me/$username"
}

/** Extract candidates only; Telegram must verify every candidate is a bot before showing it. */
object BotHubLinks {
    private val username = Regex("[A-Za-z][A-Za-z0-9_]{4,31}")
    private val tokens = Regex("https?://[^\\s<>]+|(?<![A-Za-z0-9_./])(?:t\\.me|telegram\\.me)/[^\\s<>]+|tg://[^\\s<>]+|(?<![A-Za-z0-9_./])@[A-Za-z][A-Za-z0-9_]{4,31}", RegexOption.IGNORE_CASE)

    fun usernames(text: String, embeddedUrls: List<String> = emptyList()): Set<String> = buildSet {
        val candidates = tokens.findAll(text).map { it.value }.toList() + embeddedUrls
        for (raw in candidates) {
            val token = raw.trimEnd('.', ',', ';', '!', ')', ']', '}', '\"', '\'')
            val candidate = if (token.startsWith("@")) token.drop(1) else {
                val uri = runCatching { URI(if (token.startsWith("t.me/", true) || token.startsWith("telegram.me/", true)) "https://$token" else token) }.getOrNull() ?: continue
                when {
                    uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") &&
                        uri.host?.lowercase(Locale.ROOT) in setOf("t.me", "telegram.me", "www.t.me", "www.telegram.me") && uri.userInfo == null && uri.port == -1 -> {
                        val parts = uri.path.orEmpty().trim('/').split('/')
                        if (parts.size != 1) continue
                        parts.single()
                    }
                    uri.scheme.equals("tg", true) && uri.host.equals("resolve", true) -> {
                        uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("domain=") }?.substringAfter('=') ?: continue
                    }
                    else -> continue
                }
            }
            if (username.matches(candidate)) add(candidate.lowercase(Locale.ROOT))
        }
    }
}

data class LibraryBotsState(
    val bots: List<RecommendedLibraryBot> = emptyList(),
    val index: Int = 0,
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val disclaimerAccepted: Boolean = false,
    val error: String? = null,
    val completed: Boolean = false,
    val added: Int = 0,
) {
    val current: RecommendedLibraryBot? get() = bots.getOrNull(index)
}

/** A separate opt-in per bot. Neither discovery nor skipping grants access to the library. */
class LibraryBotRecommendations(
    private val api: TelegramApi,
    private val libraryChatId: Long,
    private val handledBotIds: Set<Long> = emptySet(),
    private val onDecision: (Long) -> Unit,
    private val onCompleted: () -> Unit,
) {
    private val mutableState = MutableStateFlow(LibraryBotsState())
    val state = mutableState.asStateFlow()
    private val operation = Mutex()

    suspend fun load() {
        if (!operation.tryLock()) return
        try {
            if (mutableState.value.loaded || mutableState.value.completed) return
            mutableState.value = mutableState.value.copy(busy = true, error = null)
            try {
                val bots = withTimeout(60_000) { api.recommendedLibraryBots() }
                mutableState.value = mutableState.value.copy(bots = bots.distinctBy { it.id }.filter { it.id !in handledBotIds }, loaded = true)
            } catch (_: TimeoutCancellationException) {
                mutableState.value = mutableState.value.copy(error = "Loading bot recommendations took too long. Try again or skip bots.")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(error = "Couldn’t load recommendations from @CablegramBotHubs. Try again or skip bots.")
            }
        } finally {
            mutableState.value = mutableState.value.copy(busy = false)
            operation.unlock()
        }
    }

    fun acceptDisclaimer(accepted: Boolean) {
        if (mutableState.value.busy || mutableState.value.completed || mutableState.value.current == null) return
        mutableState.value = mutableState.value.copy(disclaimerAccepted = accepted)
    }

    suspend fun addCurrent() {
        if (!operation.tryLock()) return
        try {
            val current = mutableState.value
            val bot = current.current ?: return
            if (!current.disclaimerAccepted || current.completed) return
            mutableState.value = current.copy(busy = true, error = null)
            try {
                withTimeout(30_000) { api.addRecommendedBotToLibrary(libraryChatId, bot.id) }
                mutableState.value = mutableState.value.copy(added = mutableState.value.added + 1)
                advance(bot.id)
            } catch (_: TimeoutCancellationException) {
                mutableState.value = mutableState.value.copy(error = "Adding the bot took too long. Retry or skip this bot. A retry checks whether it is already an administrator.")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(error = "Couldn’t add this bot. Check your connection and that you can add administrators to the library channel, then retry or skip.")
            }
        } finally {
            mutableState.value = mutableState.value.copy(busy = false)
            operation.unlock()
        }
    }

    fun skipCurrent() {
        if (mutableState.value.busy || mutableState.value.completed) return
        mutableState.value.current?.let { advance(it.id) }
    }

    fun finish() {
        if (mutableState.value.busy || mutableState.value.completed) return
        onCompleted()
        mutableState.value = mutableState.value.copy(completed = true, disclaimerAccepted = false)
    }

    private fun advance(botId: Long) {
        onDecision(botId)
        val next = mutableState.value.index + 1
        if (next >= mutableState.value.bots.size) onCompleted()
        mutableState.value = mutableState.value.copy(index = next, disclaimerAccepted = false, error = null, completed = next >= mutableState.value.bots.size)
    }
}
