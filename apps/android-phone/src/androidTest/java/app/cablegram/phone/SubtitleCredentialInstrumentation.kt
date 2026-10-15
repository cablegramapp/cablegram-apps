package app.cablegram.phone

import android.app.Instrumentation
import android.os.Bundle
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Explicitly invoked on an isolated .cab28 QA package; never runs on an owner's installation. */
class SubtitleCredentialInstrumentation : Instrumentation() {
    private lateinit var args: Bundle
    override fun onCreate(arguments: Bundle?) { args = arguments ?: Bundle(); super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            check(targetContext.packageName.endsWith(".cab28"))
            val store = SubtitleCredentialStore(targetContext)
            store.clear("qa-user-a"); store.clear("qa-user-b")
            store.save("qa-user-a", "subdl", PersonalSubtitleCredentials("canary-a"))
            check(SubtitleCredentialStore(targetContext).read("qa-user-a", "subdl")?.key == "canary-a")
            check(store.read("qa-user-b", "subdl") == null)
            check(store.read("qa-user-a", "opensubtitles") == null)
            val directory = java.io.File(targetContext.noBackupFilesDir, "subtitle-credentials")
            check(directory.listFiles().orEmpty().none { it.readBytes().toString(Charsets.ISO_8859_1).contains("canary-a") })
            store.save("qa-user-a", "subdl", PersonalSubtitleCredentials("canary-replacement"))
            check(store.read("qa-user-a", "subdl")?.key == "canary-replacement")
            store.save("qa-user-a", "opensubtitles", PersonalSubtitleCredentials("os-canary", "user", "password-canary"))
            store.remove("qa-user-a", "subdl"); check(store.read("qa-user-a", "subdl") == null)
            store.clear("qa-user-a"); check(store.read("qa-user-a", "opensubtitles") == null)
            // Restoring ciphertext into another user's namespace does not make it decryptable (AAD + scoped key).
            store.save("qa-user-a", "subdl", PersonalSubtitleCredentials("restore-canary"))
            store.save("qa-user-b", "subdl", PersonalSubtitleCredentials("other-canary"))
            val files = directory.listFiles().orEmpty(); check(files.size == 2)
            val a = files.first { it.readBytes().contentEquals(it.readBytes()) }
            val b = files.first { it != a }; a.copyTo(b, overwrite = true)
            check(store.read("qa-user-a", "subdl") == null || store.read("qa-user-b", "subdl") == null)
            store.clear("qa-user-a"); store.clear("qa-user-b")
            if (args.getString("provision") == "true") provisionAccount()
            result.putString("result", "PASS: Keystore encryption, restart, replace/remove, both providers, user isolation, AAD restore rejection and cleanup")
            finish(android.app.Activity.RESULT_OK, result)
        } catch (e: Throwable) { result.putString("result", "FAIL: " + e.javaClass.simpleName); finish(android.app.Activity.RESULT_CANCELED, result) }
    }

    /**
     * Signs a disposable account in on the isolated local API (spec 007: no passwords). The local server runs with
     * TEST_MAIL_KEY (instrumentation argument `mail_key`, default cab28-local-only), so codes for test-domain
     * addresses are read back from GET /api/test/sign-in-code instead of being mailed.
     */
    private fun provisionAccount() {
        val pairing = PairingStore(targetContext); pairing.apiBaseUrl = "http://127.0.0.1:3288/"
        val base = pairing.apiBaseUrl
        val key = args.getString("mail_key") ?: "cab28-local-only"
        val email = "cab28-${System.currentTimeMillis()}@test.cablegram.invalid"
        val http = OkHttpClient()
        val jsonType = "application/json".toMediaType()
        fun post(path: String, body: JSONObject): JSONObject =
            http.newCall(Request.Builder().url(base + path).post(body.toString().toRequestBody(jsonType)).build()).execute().use { response ->
                check(response.isSuccessful) { "$path HTTP ${response.code}" }
                JSONObject(response.body!!.string().ifBlank { "{}" })
            }
        fun get(path: String, headers: Map<String, String> = emptyMap()): JSONObject =
            http.newCall(Request.Builder().url(base + path).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build()).execute().use { response ->
                check(response.isSuccessful) { "$path HTTP ${response.code}" }
                JSONObject(response.body!!.string())
            }
        post("api/auth/email-code/request", JSONObject().put("email", email))
        val code = get("api/test/sign-in-code?email=" + java.net.URLEncoder.encode(email, "UTF-8"), mapOf("x-test-mail-key" to key)).getString("code")
        val verified = post("api/auth/email-code/verify", JSONObject().put("email", email).put("code", code))
        val legal = get("api/legal")
        val account = post("api/auth/signup", JSONObject()
            .put("signup_ticket", verified.getString("signup_ticket"))
            .put("name", "CAB28 QA")
            .put("accepted", JSONObject()
                .put("terms_version", legal.getJSONObject("terms").getString("version"))
                .put("privacy_version", legal.getJSONObject("privacy").getString("version")))
            .put("age_confirmed", true))
        pairing.accountToken = account.getString("access_token")
        pairing.refreshToken = account.getString("refresh_token")
        pairing.displayName = "CAB28 QA"
    }
}
