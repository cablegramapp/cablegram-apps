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
            if (args.getString("provision") == "true") {
                val pairing = PairingStore(targetContext); pairing.apiBaseUrl = "http://127.0.0.1:3288/"
                val payload = JSONObject().put("email", "cab28-${System.currentTimeMillis()}@example.invalid").put("password", "Cab28LocalPass1").put("household_name", "CAB28 QA").put("test_confirm_key", "cab28-local-only")
                OkHttpClient().newCall(Request.Builder().url(pairing.apiBaseUrl + "api/auth/register").post(payload.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { response ->
                    check(response.isSuccessful); val data = JSONObject(response.body!!.string())
                    pairing.accountToken = data.getString("access_token"); pairing.refreshToken = data.getString("refresh_token"); pairing.displayName = "CAB28 QA"
                }
            }
            result.putString("result", "PASS: Keystore encryption, restart, replace/remove, both providers, user isolation, AAD restore rejection and cleanup")
            finish(android.app.Activity.RESULT_OK, result)
        } catch (e: Throwable) { result.putString("result", "FAIL: " + e.javaClass.simpleName); finish(android.app.Activity.RESULT_CANCELED, result) }
    }
}
