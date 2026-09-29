package app.cablegram.data

import androidx.compose.runtime.mutableStateListOf

/** Retain every command in a poll batch until the UI consumes it. */
internal class PendingCommands {
    private val commands = mutableStateListOf<TvCommand>()
    val first: TvCommand? get() = commands.firstOrNull()

    fun add(command: TvCommand) {
        if (commands.none { it.id == command.id }) commands.add(command)
    }

    fun consume(): TvCommand? = if (commands.isNotEmpty()) commands.removeAt(0) else null
    fun remove(id: String) { commands.removeAll { it.id == id } }
    fun clear() { commands.clear() }
    fun snapshot(): List<TvCommand> = commands.toList()
}
