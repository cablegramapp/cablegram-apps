package app.cablegram.phone

import android.app.Application
import android.app.Instrumentation
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import app.cablegram.telegram.*
import fi.iki.elonen.NanoHTTPD
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Exercises the real ViewModel with an existing session, only in a disposable installation.
 * Build with -PCABLEGRAM_APP_ID_SUFFIX=.telegramqa and
 * -PCABLEGRAM_TEST_RUNNER=app.cablegram.phone.TelegramReconnectInstrumentation.
 * Install both APKs, then run am instrument -w
 * app.cablegram.phone.telegramqa.test/app.cablegram.phone.TelegramReconnectInstrumentation.
 */
class TelegramReconnectInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = Bundle()
        val stores = mutableListOf<ViewModelStore>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val puts = AtomicInteger()
        val failLibrary = AtomicBoolean()
        val auth = MutableStateFlow<TgAuthState>(TgAuthState.WaitCode)
        val current = PhoneTelegram::class.java.getDeclaredField("current").apply { isAccessible = true }
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(request: IHTTPSession): Response {
                if (request.uri == "/api/telegram/link" && request.method == Method.PUT) {
                    // Consume the body so the next HTTP request can reuse the connection.
                    request.parseBody(mutableMapOf())
                    puts.incrementAndGet()
                    return newFixedLengthResponse(Response.Status.OK, "application/json", """{"linked":true,"display_name":"QA","library_chat_id":"-1001","telegram_user_id":"42"}""")
                }
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", "{}")
            }
        }
        try {
            check(targetContext.packageName.endsWith(".telegramqa")) { "Use the isolated .telegramqa installation" }
            check(PhoneTelegram.existing() == null)
            server.start()
            val pairing = PairingStore(targetContext)
            pairing.apiBaseUrl = "http://127.0.0.1:${server.listeningPort}"
            pairing.clearTVs()
            PhoneTelegram.setLinked(targetContext, false)
            fun model(): PhoneViewModel {
                // Keep unrelated signed-in startup work out of the fixture.
                pairing.accountToken = null
                lateinit var vm: PhoneViewModel
                runOnMainSync {
                    val store = ViewModelStore().also(stores::add)
                    vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(targetContext.applicationContext as Application))[PhoneViewModel::class.java]
                }
                pairing.accountToken = "qa-telegram-account"
                return vm
            }
            fun await(message: String, condition: () -> Boolean) {
                val deadline = android.os.SystemClock.elapsedRealtime() + 8_000
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    var done = false
                    runOnMainSync { done = condition() }
                    if (done) return
                    Thread.sleep(50)
                }
                error(message)
            }

            // A first-time user still sees consent; opening the sheet must not start TDLib.
            val first = model()
            runOnMainSync { first.openTelegramSheet() }
            check(!first.telegramStarted && PhoneTelegram.existing() == null && puts.get() == 0)
            runOnMainSync { stores.last().clear() }

            // A process-wide session can already be ready when a NEW ViewModel opens setup.
            val api = Proxy.newProxyInstance(TelegramApi::class.java.classLoader, arrayOf(TelegramApi::class.java)) { _, method, _ ->
                when (method.name) {
                    "getAuthStates" -> auth
                    "me" -> TgUser(42, "QA")
                    "findChatByTitle" -> {
                        if (failLibrary.getAndSet(false)) throw TgException(500, "QA_LIBRARY_UNAVAILABLE")
                        -1001L
                    }
                    "chat" -> TgChat(-1001, TelegramSession.LIBRARY_TITLE, hasPhoto = true)
                    else -> error("Unexpected Telegram call: ${method.name}")
                }
            } as TelegramApi
            val session = TelegramSession(api, TelegramRole.Phone, { error("No native Telegram client in this test") }, scope)
            current.set(PhoneTelegram, session)
            session.start()
            runBlocking { withTimeout(5_000) { session.state.first { it is TelegramState.NeedsCode } } }
            val pending = model()
            runOnMainSync {
                pending.openTelegramSheet()
                pending.closeTelegramSheet()
                pending.openTelegramSheet()
            }
            auth.value = TgAuthState.Ready
            await("Reopened sign-in did not finish linking") { pending.telegramLink?.linked == true }
            check(puts.get() == 1 && PhoneTelegram.wasLinked(targetContext))
            runOnMainSync { pending.closeTelegramSheet(); pending.openTelegramSheet() }
            check(puts.get() == 1) { "An already completed link was submitted again" }
            runOnMainSync { stores.last().clear() }

            // The original failure: Ready survives, but the finishing job belonged to the old VM.
            PhoneTelegram.setLinked(targetContext, false)
            val restored = model()
            runOnMainSync { restored.openTelegramSheet() }
            await("An existing Ready session stayed on setup") { restored.telegramLink?.linked == true }
            check(puts.get() == 2)
            runOnMainSync { stores.last().clear() }

            // A failed attempt can be retried with the same authenticated Telegram session.
            PhoneTelegram.setLinked(targetContext, false)
            failLibrary.set(true)
            val retry = model()
            runOnMainSync { retry.openTelegramSheet() }
            await("Library failure was not surfaced") { retry.telegramMessage != null && !retry.telegramFinishing }
            check(puts.get() == 2)
            runOnMainSync { retry.connectTelegram() }
            await("Retry did not finish linking") { retry.telegramLink?.linked == true }
            check(puts.get() == 3 && retry.telegramMessage == null)
            result.putString("result", "PASS: first-use consent, pending reopen, single submission, completed reopen, Ready session after ViewModel recreation, failed setup retry")
            finish(android.app.Activity.RESULT_OK, result)
        } catch (e: Throwable) {
            result.putString("result", "FAIL: ${e.javaClass.simpleName}: ${e.message}")
            finish(android.app.Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { stores.forEach { it.clear() } }
            scope.cancel()
            current.set(PhoneTelegram, null)
            server.stop()
        }
    }
}
