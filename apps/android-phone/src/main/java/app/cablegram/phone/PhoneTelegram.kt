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

    /** Telegram identity is loaded only after Cablegram sign-in. */
    val configured: Boolean get() = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var current: TelegramSession? = null

    /** The session, creating (and starting) it on first use or after a sign-out closed the old one. */
    fun session(context: Context): TelegramSession = synchronized(this) {
        current?.takeUnless { it.state.value == TelegramState.SignedOut }?.also { it.retryStartup() } ?: create(context.applicationContext).also { current = it }
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
                val store = PairingStore(context)
                val token = store.accountToken ?: throw app.cablegram.telegram.TelegramClientUnavailable()
                val credentials = CatalogClient(store.apiBaseUrl, store).telegramClientCredentials(token)
                TgParameters(
                    databaseDirectory = keys.databaseDirectory.path,
                    filesDirectory = keys.filesDirectory.path,
                    databaseKey = keys.databaseKey(),
                    apiId = credentials.apiId,
                    apiHash = credentials.apiHash,
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

    /**
     * "Approve my TVs automatically" (spec 004 US2): off by default, so a notification asks first. The
     * login link comes from the server, and approving it gives that link's holder a session on the
     * user's Telegram account; the user should see and confirm it unless they opted in.
     */
    fun autoApproveTvs(context: Context): Boolean = prefs(context).getBoolean(PREF_AUTO_APPROVE, false)

    fun setAutoApproveTvs(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(PREF_AUTO_APPROVE, enabled).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("cablegram_telegram_link", Context.MODE_PRIVATE)
    private const val PREF_LINKED = "linked"
    private const val PREF_AUTO_APPROVE = "auto_approve_tvs"
}
