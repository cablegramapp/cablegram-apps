package app.cablegram.phone

import android.content.Context
import android.os.Build
import app.cablegram.telegram.TdlibTelegramApi
import app.cablegram.telegram.TelegramDatabaseKey
import app.cablegram.telegram.TelegramRole
import app.cablegram.telegram.TelegramSession
import app.cablegram.telegram.TelegramState
import app.cablegram.telegram.TgParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The phone's one Telegram session (spec 004 US1), shared by the Import screen and the background
 * service that approves TV logins. TDLib runs one client per process, so it lives here, not in a
 * ViewModel. It starts only when the user connects Telegram or this phone linked it before, so
 * people who never use Telegram never load it.
 */
object PhoneTelegram {
    /** How this phone appears in Telegram → Settings → Devices. */
    val deviceModel: String get() = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    /** False in builds without Telegram API credentials: the Telegram entry is hidden. */
    val configured: Boolean get() = BuildConfig.TELEGRAM_API_ID != 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var current: TelegramSession? = null

    /** The session, creating (and starting) it on first use or after a sign-out closed the old one. */
    fun session(context: Context): TelegramSession = synchronized(this) {
        current?.takeUnless { it.state.value == TelegramState.SignedOut } ?: create(context.applicationContext).also { current = it }
    }

    /** The running session if there is one; never starts TDLib. */
    fun existing(): TelegramSession? = current

    fun wasLinked(context: Context): Boolean = prefs(context).getBoolean(PREF_LINKED, false)

    fun setLinked(context: Context, linked: Boolean) {
        prefs(context).edit().putBoolean(PREF_LINKED, linked).apply()
    }

    /** After log-out: drop the session and delete its database, pieces and key. */
    fun forget(context: Context) = synchronized(this) {
        current = null
        setLinked(context, false)
        TelegramDatabaseKey(context.applicationContext).wipe()
    }

    private fun create(context: Context): TelegramSession {
        val keys = TelegramDatabaseKey(context)
        return TelegramSession(
            api = TdlibTelegramApi(),
            role = TelegramRole.Phone,
            parameters = {
                TgParameters(
                    databaseDirectory = keys.databaseDirectory.path,
                    filesDirectory = keys.filesDirectory.path,
                    databaseKey = keys.databaseKey(),
                    apiId = BuildConfig.TELEGRAM_API_ID,
                    apiHash = BuildConfig.TELEGRAM_API_HASH,
                    deviceModel = deviceModel,
                    systemVersion = "Android ${Build.VERSION.RELEASE}",
                    applicationVersion = "Cablegram ${BuildConfig.VERSION_NAME}",
                )
            },
            scope = scope,
        ).also { it.start() }
    }

    /** The Cablegram logo as a file TDLib can upload (channel photo); copied from assets once. */
    fun logoPath(context: Context): String? = runCatching {
        val file = java.io.File(context.filesDir, "cablegram_library_photo.jpg")
        if (!file.exists()) {
            context.assets.open("telegram/cablegram_library_photo.jpg").use { input -> file.outputStream().use(input::copyTo) }
        }
        file.path
    }.getOrNull()

    /** Opens a private channel in the Telegram app; Telegram's private links drop the -100 prefix. */
    fun channelLink(chatId: Long): String = "tg://privatepost?channel=${chatId.toString().removePrefix("-100")}&post=1"

    /** "Approve my TVs automatically" (spec 004 US2): on by default; off means a notification asks. */
    fun autoApproveTvs(context: Context): Boolean = prefs(context).getBoolean(PREF_AUTO_APPROVE, true)

    fun setAutoApproveTvs(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(PREF_AUTO_APPROVE, enabled).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("cablegram_telegram_link", Context.MODE_PRIVATE)
    private const val PREF_LINKED = "linked"
    private const val PREF_AUTO_APPROVE = "auto_approve_tvs"
}
