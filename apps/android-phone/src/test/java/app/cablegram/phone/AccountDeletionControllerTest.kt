package app.cablegram.phone

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountDeletionControllerTest {
    private var wipes = 0
    private val sent = mutableListOf<Pair<String, String>>()

    private fun controller(
        token: String? = "access-1",
        result: AccountDeletionResult,
    ) = AccountDeletionController(
        token = { token },
        delete = { t, p -> sent += t to p; result },
        wipeLocal = { wipes++ },
    )

    @Test
    fun `success deletes on the server, then signs out locally`() = runTest {
        val controller = controller(result = AccountDeletionResult.Deleted)

        assertTrue(controller.submit("secret-pw"))

        assertEquals(listOf("access-1" to "secret-pw"), sent)
        assertEquals(1, wipes)
        assertNull(controller.error)
        assertFalse(controller.running)
    }

    @Test
    fun `wrong password keeps the account and the session and allows a retry`() = runTest {
        val controller = controller(result = AccountDeletionResult.WrongPassword)

        assertFalse(controller.submit("nope"))
        assertEquals(AccountDeletionError.WrongPassword, controller.error)
        assertEquals(0, wipes)
        assertFalse(controller.running)

        assertFalse(controller.submit("nope again"))
        assertEquals(2, sent.size)
    }

    @Test
    fun `rate limit keeps the account`() = runTest {
        val controller = controller(result = AccountDeletionResult.RateLimited)

        assertFalse(controller.submit("secret-pw"))
        assertEquals(AccountDeletionError.RateLimited, controller.error)
        assertEquals(0, wipes)
    }

    @Test
    fun `network failure keeps the account`() = runTest {
        val controller = controller(result = AccountDeletionResult.Offline)

        assertFalse(controller.submit("secret-pw"))
        assertEquals(AccountDeletionError.Offline, controller.error)
        assertEquals(0, wipes)
    }

    @Test
    fun `an unexpected answer is a generic error that keeps the account`() = runTest {
        val controller = controller(result = AccountDeletionResult.Failed)

        assertFalse(controller.submit("secret-pw"))
        assertEquals(AccountDeletionError.Failed, controller.error)
        assertEquals(0, wipes)
    }

    @Test
    fun `an invalid session is reported without calling the server when there is no token`() = runTest {
        val controller = controller(token = null, result = AccountDeletionResult.Deleted)

        assertFalse(controller.submit("secret-pw"))
        assertEquals(AccountDeletionError.SessionExpired, controller.error)
        assertTrue(sent.isEmpty())
        assertEquals(0, wipes)
    }

    @Test
    fun `an empty password is never sent`() = runTest {
        val controller = controller(result = AccountDeletionResult.Deleted)

        assertFalse(controller.submit(""))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a second tap while the request runs does nothing`() = runTest {
        val gate = CompletableDeferred<AccountDeletionResult>()
        val controller = AccountDeletionController({ "access-1" }, { t, p -> sent += t to p; gate.await() }, { wipes++ })

        val first = launch { controller.submit("secret-pw") }
        testScheduler.runCurrent()
        assertTrue(controller.running)

        assertFalse(controller.submit("secret-pw"))
        assertEquals(1, sent.size)

        gate.complete(AccountDeletionResult.Deleted)
        first.join()
        assertFalse(controller.running)
        assertEquals(1, wipes)
    }

    @Test
    fun `a new attempt clears the previous error`() = runTest {
        var next = AccountDeletionResult.WrongPassword
        val controller = AccountDeletionController({ "access-1" }, { _, _ -> next }, { wipes++ })

        controller.submit("bad")
        assertEquals(AccountDeletionError.WrongPassword, controller.error)

        next = AccountDeletionResult.Deleted
        assertTrue(controller.submit("good-pw"))
        assertNull(controller.error)
    }
}
