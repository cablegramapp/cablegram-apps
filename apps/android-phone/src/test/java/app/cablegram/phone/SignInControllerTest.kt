package app.cablegram.phone

import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
class SignInControllerTest {
    private val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    private lateinit var server: MockWebServer
    private val signedIn = mutableListOf<PhonePairResponse>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun controller() = SignInController(
        client = { CatalogClient(server.url("/").toString(), null) },
        signedIn = { signedIn += it },
        resources = { res },
        deviceName = { "Phone" },
    )

    private fun json(status: Int, body: String) = MockResponse().setResponseCode(status).setBody(body)

    private val legal = """{"terms":{"version":"2026-10-15","url":"t"},"privacy":{"version":"2026-10-16","url":"p"}}"""

    @Test
    fun `a known email signs in with the code`() = runTest {
        server.enqueue(json(202, """{"sent":true}"""))
        server.enqueue(json(200, """{"access_token":"a1","refresh_token":"r1","household_id":"h1","display_name":"Home"}"""))
        val c = controller()
        c.requestCode(" ana@b.co ")
        assertEquals(SignInStep.Code("ana@b.co"), c.step)
        assertEquals("""{"email":"ana@b.co"}""", server.takeRequest().body.readUtf8())
        c.verifyCode("123456")
        val verify = server.takeRequest()
        assertEquals("/api/auth/email-code/verify", verify.path)
        assertEquals("""{"email":"ana@b.co","code":"123456"}""", verify.body.readUtf8())
        assertEquals("a1", signedIn.single().token)
        assertEquals("h1", signedIn.single().householdId)
        assertEquals(SignInStep.Start, c.step)
    }

    @Test
    fun `a new person creates the account with the Terms versions and the age confirmed`() = runTest {
        server.enqueue(json(202, """{"sent":true}"""))
        server.enqueue(json(200, """{"status":"signup_required","signup_ticket":"t-1","email":"new@b.co"}"""))
        server.enqueue(json(200, legal))
        server.enqueue(json(201, """{"access_token":"a1","refresh_token":"r1","display_name":"Sam"}"""))
        val c = controller()
        c.requestCode("new@b.co")
        c.verifyCode("123456")
        assertEquals(SignInStep.CreateAccount("t-1", "new@b.co", ""), c.step)
        assertTrue("nobody is signed in before Create account", signedIn.isEmpty())
        c.createAccount(" Sam ")
        repeat(3) { server.takeRequest() }
        val signup = server.takeRequest()
        assertEquals("/api/auth/signup", signup.path)
        assertEquals(
            """{"signup_ticket":"t-1","name":"Sam","accepted":{"terms_version":"2026-10-15","privacy_version":"2026-10-16"},"age_confirmed":true}""",
            signup.body.readUtf8(),
        )
        assertEquals("Sam", signedIn.single().displayName)
    }

    @Test
    fun `Google sends the token and nonce, and a new Google person keeps their name`() = runTest {
        server.enqueue(json(200, """{"status":"signup_required","signup_ticket":"t-2","email":"g@gmail.com","name":"Ana Lima"}"""))
        val c = controller()
        c.google("id-token", "nonce-1")
        assertEquals("""{"id_token":"id-token","nonce":"nonce-1"}""", server.takeRequest().body.readUtf8())
        assertEquals(SignInStep.CreateAccount("t-2", "g@gmail.com", "Ana Lima"), c.step)
    }

    @Test
    fun `a wrong code stays on the code screen with a clear message`() = runTest {
        server.enqueue(json(202, """{"sent":true}"""))
        server.enqueue(json(400, """{"error":"invalid_code"}"""))
        val c = controller()
        c.requestCode("ana@b.co")
        c.verifyCode("000000")
        assertEquals(SignInStep.Code("ana@b.co"), c.step)
        assertEquals("That code isn't right. Check the email and try again.", c.message)
    }

    @Test
    fun `an expired ticket goes back to the start`() = runTest {
        server.enqueue(json(200, """{"status":"signup_required","signup_ticket":"t-3","email":"x@b.co"}"""))
        server.enqueue(json(200, legal))
        server.enqueue(json(401, """{"error":"invalid_signup_ticket"}"""))
        val c = controller()
        c.google("id", "nonce-2")
        c.createAccount("X")
        assertEquals(SignInStep.Start, c.step)
        assertEquals("That took too long. Start again.", c.message)
    }

    @Test
    fun `a setup code signs the first phone in`() = runTest {
        server.enqueue(json(201, """{"access_token":"a1","refresh_token":"r1","household_id":"h1","device_id":"d1","display_name":"Home"}"""))
        val c = controller()
        c.claimSetupCode(" 123456 ")
        val claim = server.takeRequest()
        assertEquals("/api/auth/setup/claim", claim.path)
        assertEquals("""{"code":"123456","device_name":"Phone"}""", claim.body.readUtf8())
        assertEquals("d1", signedIn.single().phoneDeviceId)
    }

    @Test
    fun `a join request waits, then signs in once approved`() = runTest {
        server.enqueue(json(201, """{"id":"j1","short_code":"654321","poll_secret":"s","expires_at":"x"}"""))
        server.enqueue(json(200, """{"id":"j1","status":"pending"}"""))
        server.enqueue(json(200, """{"id":"j1","status":"approved","access_token":"a2","refresh_token":"r2","household_id":"h1","device_id":"d2"}"""))
        val c = controller()
        c.requestJoin()
        assertEquals(SignInStep.JoinWait("j1", "s", "654321"), c.step)
        assertTrue(c.pollJoin())
        assertTrue(!c.pollJoin())
        assertEquals("a2", signedIn.single().token)
        assertEquals(SignInStep.Start, c.step)
    }

    @Test
    fun `methods without Google offer only the email code`() = runTest {
        server.enqueue(json(404, """{"error":"not_found"}"""))
        server.enqueue(json(200, """{"google":null,"email_code":true}"""))
        server.enqueue(json(200, legal))
        val c = controller()
        c.load()
        assertNull(c.methods?.google)
        assertEquals("p", c.documents.privacy.url)
    }
}
