package app.cablegram.phone

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 004 T014: nothing that could sign someone in reaches the log. Login links, codes, passwords and
 * tokens must never be interpolated into a log line in the Telegram code of either app.
 */
class TelegramLogAuditTest {
    private val roots = listOf(
        "src/main/java/app/cablegram/telegram",
        "src/main/java/app/cablegram/phone/TelegramTvApprovals.kt",
        "src/main/java/app/cablegram/phone/TelegramRemoval.kt",
        "src/main/java/app/cablegram/phone/TelegramMediaBridge.kt",
        "src/main/java/app/cablegram/phone/PhoneTelegram.kt",
        "../android-tv/src/main/java/app/cablegram/telegram",
        "../android-tv/src/main/java/app/cablegram/TvTelegram.kt",
        "../android-tv/src/main/java/app/cablegram/TelegramStreamServer.kt",
    )
    private val logCall = Regex("""(PairLog\.[iwe]|Log\.[diwev]|println|Timber\.\w)\(""")
    private val secret = Regex("""(?i)\b(loginLink|login_link|link|code|password|token|secret|apiHash|databaseKey|capability|\bpass\b)\b""")

    private val literal = Regex(""""(?:[^"\\]|\\.)*"""")
    private val interpolation = Regex("""\$\{[^}]*}|\$\w+""")

    private fun kotlinFiles(): List<File> = roots.map(::File).filter { it.exists() }.flatMap { root ->
        if (root.isFile) listOf(root) else root.walkTopDown().filter { it.extension == "kt" }.toList()
    }

    @Test
    fun `no log line in the Telegram code mentions a secret`() {
        val files = kotlinFiles()
        assertTrue("audit found no files: run from the app module", files.isNotEmpty())
        val offenders = files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                // Words inside a string literal are just text; only interpolated values and arguments count.
                val logged = line.substringAfter("(", "").replace(literal) { m ->
                    interpolation.findAll(m.value).joinToString(" ") { it.value }
                }
                if (logCall.containsMatchIn(line) && secret.containsMatchIn(logged)) "${file.name}:${index + 1}: ${line.trim()}" else null
            }
        }
        assertTrue("Secrets in log lines:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
