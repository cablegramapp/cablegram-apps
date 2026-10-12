package app.cablegram.phone

import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import java.security.KeyStore
import java.time.Instant

/** Storage acceptance test using real Android Keystore, only on a disposable QA installation. */
class PairingCredentialInstrumentation : Instrumentation() {
    private var restartProbe = false
    override fun onCreate(arguments: Bundle?) {
        restartProbe = arguments?.getString("phase") == "restart"
        super.onCreate(arguments); start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            check(targetContext.packageName.endsWith(".lanstoreqa"))
            val prefs = targetContext.getSharedPreferences("cablegram_phone_pair", Context.MODE_PRIVATE)
            val secrets = targetContext.getSharedPreferences("cablegram_phone_secrets", Context.MODE_PRIVATE)
            if (restartProbe) {
                check(PairingStore(targetContext).tvs.single() == PairedTv("789789", "Restart", "qa-lan-restart", "device-restart"))
                check(!prefs.contains("tvs_json") && !prefs.contains("lan_token"))
                PairingStore(targetContext).clearTVs()
                result.putString("result", "PASS: encrypted pairings survived a fresh process and were cleared")
                finish(android.app.Activity.RESULT_OK, result)
                return
            }
            check(prefs.edit().clear().commit()); check(secrets.edit().clear().commit())
            val legacy = """[{"pin":"111222","name":"Home","capability":"qa-lan-first","device_id":"device-a","cast_device_id":"cast-a","temporary":false},{"pin":"333444","name":"Guest","capability":"qa-lan-second","device_id":"device-b","temporary":true,"expires_at":"2030-01-01T00:00:00Z","telegram_direct":true}]"""
            check(prefs.edit().putString("tvs_json", legacy).putString("lan_token", "qa-lan-second").commit())
            val store = PairingStore(targetContext)
            val original = store.tvs
            check(original.size == 2)
            check(original[0].capability == "qa-lan-first" && original[0].castDeviceId == "cast-a")
            check(original[1].trust.temporary && original[1].trust.telegramDirect)
            check(original[1].trust.expiresAt == Instant.parse("2030-01-01T00:00:00Z"))
            check(!prefs.contains("tvs_json") && !prefs.contains("lan_token"))
            check(PairingStore(targetContext).tvs == original)
            val xml = java.io.File(targetContext.applicationInfo.dataDir, "shared_prefs")
                .listFiles().orEmpty().joinToString("\n") { it.readText() }
            check(listOf("qa-lan-first", "qa-lan-second", "111222", "333444").none { xml.contains(it) })

            store.attachCapability("111222", "qa-lan-replacement")
            store.attachDeviceId("111222", "device-updated")
            store.attachCastDeviceId("device-updated", "cast-updated")
            val updated = store.tvs.first()
            check(updated.capability == "qa-lan-replacement" && updated.deviceId == "device-updated")
            check(updated.castDeviceId == "cast-updated")
            store.removeTv("333444"); check(PairingStore(targetContext).tvs == listOf(updated))
            // A stale plaintext record left by an interrupted migration never overrides durable ciphertext.
            check(prefs.edit().putString("tvs_json", legacy).putString("lan_token", "qa-lan-second").commit())
            check(PairingStore(targetContext).tvs == listOf(updated))
            check(!prefs.contains("tvs_json") && !prefs.contains("lan_token"))
            store.clearTVs(); check(PairingStore(targetContext).tvs.isEmpty())
            store.addTv("555666", "New", "qa-lan-new", "device-new")
            check(PairingStore(targetContext).tvs.single().capability == "qa-lan-new")

            // Account ciphertext cannot be moved into the pairing record (different authenticated context).
            SecretStore(targetContext).accountToken = "qa-account-canary"
            check(secrets.edit().putString("paired_tvs_enc", secrets.getString("account_token_enc", null)).commit())
            check(PairingStore(targetContext).tvs.isEmpty())

            // Corrupted ciphertext fails closed even when stale plaintext is present.
            check(secrets.edit().putString("paired_tvs_enc", "invalid-ciphertext").commit())
            check(prefs.edit().putString("tvs_json", legacy).commit())
            check(PairingStore(targetContext).tvs.isEmpty())
            check(!prefs.contains("tvs_json"))
            store.addTv("777888", "Recovered", "qa-lan-recovered", "device-recovered")
            check(store.tvs.single().capability == "qa-lan-recovered")
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("cablegram.phone.account") }
            check(PairingStore(targetContext).tvs.isEmpty())
            store.addTv("999000", "Repaired", "qa-lan-repaired", "device-repaired")
            check(PairingStore(targetContext).tvs.single().capability == "qa-lan-repaired")

            // Old PIN-only installations keep their metadata, but gain no LAN capability.
            check(secrets.edit().clear().commit())
            check(prefs.edit().clear().putString("lan_token", "123123").putString("tv_name", "Old TV").commit())
            val old = PairingStore(targetContext)
            check(old.tvs.single() == PairedTv("123123", "Old TV"))
            check(!prefs.contains("lan_token"))
            old.clearTVs()
            old.addTv("789789", "Restart", "qa-lan-restart", "device-restart")
            result.putString("result", "PASS: encrypted migration, plaintext removal, metadata preservation, reopen, update/remove/clear, interrupted migration, corruption, key loss and PIN-only migration")
            finish(android.app.Activity.RESULT_OK, result)
        } catch (e: Throwable) {
            result.putString("result", "FAIL: " + e.javaClass.simpleName + " at " + e.stackTrace.firstOrNull { it.className == javaClass.name }?.lineNumber)
            finish(android.app.Activity.RESULT_CANCELED, result)
        }
    }
}
