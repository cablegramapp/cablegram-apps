package app.cablegram.telegram

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout

/** A fixed allowlist: this feature cannot join arbitrary chats or read their messages. */
enum class CablegramChannel(val title: String, val username: String) {
    News("Cablegram News", "CablegramNews"),
    Community("Cablegram Community", "CablegramCommunity"),
    BotHubs("Cablegram Bot Hubs", "CablegramBotHubs");

    val url: String get() = "https://t.me/$username"
}

data class CommunityChannelsState(
    val selected: Set<CablegramChannel> = CablegramChannel.entries.toSet(),
    val joined: Set<CablegramChannel> = emptySet(),
    val failed: Set<CablegramChannel> = emptySet(),
    val saving: Boolean = false,
    val completed: Boolean = false,
)

/** No Telegram calls until Save. Retries keep successful joins and try every remaining selection. */
class CommunityChannels(
    private val api: TelegramApi,
    private val onCompleted: () -> Unit,
) {
    private val mutableState = MutableStateFlow(CommunityChannelsState())
    val state = mutableState.asStateFlow()
    private val saveLock = Mutex()

    fun select(channel: CablegramChannel, selected: Boolean) {
        val current = mutableState.value
        if (current.saving || current.completed || channel in current.joined) return
        mutableState.value = current.copy(
            selected = if (selected) current.selected + channel else current.selected - channel,
            failed = current.failed - channel,
        )
    }

    suspend fun save() {
        if (!saveLock.tryLock()) return
        try {
            if (mutableState.value.completed) return
            mutableState.value = mutableState.value.copy(saving = true, failed = emptySet())
            val pending = mutableState.value.selected - mutableState.value.joined
            for (channel in pending) {
                try {
                    withTimeout(20_000) { api.joinCommunityChannel(channel) }
                    mutableState.value = mutableState.value.let { it.copy(joined = it.joined + channel) }
                } catch (_: TimeoutCancellationException) {
                    mutableState.value = mutableState.value.let { it.copy(failed = it.failed + channel) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    mutableState.value = mutableState.value.let { it.copy(failed = it.failed + channel) }
                }
            }
            if (mutableState.value.failed.isEmpty()) {
                onCompleted()
                mutableState.value = mutableState.value.copy(completed = true)
            }
        } finally {
            mutableState.value = mutableState.value.copy(saving = false)
            saveLock.unlock()
        }
    }
}
