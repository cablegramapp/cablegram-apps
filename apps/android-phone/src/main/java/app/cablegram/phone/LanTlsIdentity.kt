package app.cablegram.phone

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

/**
 * This phone's TLS identity for the LAN library server (CAB-48).
 *
 * An EC P-256 key made once in the Android Keystore, which also signs the self-signed certificate; the private key never
 * leaves the keystore. TVs don't trust the certificate through a CA: the phone publishes [fingerprint] to the control
 * plane with its LAN address, and a TV accepts only a server whose certificate has exactly that SHA-256.
 *
 * Clearing the app's data or reinstalling makes a new key, and so a new fingerprint, which the next announce publishes.
 */
class LanTlsIdentity private constructor(private val key: PrivateKey, val certificate: X509Certificate) {
    /** Lowercase hex SHA-256 of the DER certificate: what a TV pins. */
    val fingerprint: String = sha256Hex(certificate.encoded)

    fun serverSocketFactory(): SSLServerSocketFactory {
        val context = SSLContext.getInstance("TLS")
        context.init(arrayOf(KeyManager()), null, null)
        return context.serverSocketFactory
    }

    /** Always this one key and certificate, whatever the client asks for. */
    private inner class KeyManager : X509ExtendedKeyManager() {
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun getCertificateChain(alias: String?) = arrayOf(certificate)
        override fun getPrivateKey(alias: String?) = key
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = null
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = null
    }

    companion object {
        private const val ALIAS = "cablegram_lan_tls"
        private const val VALID_YEARS = 20

        /** The identity in the keystore, made on first use. */
        @Synchronized
        fun load(): LanTlsIdentity {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = store.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry
            if (existing != null) return LanTlsIdentity(existing.privateKey, existing.certificate as X509Certificate)
            val now = System.currentTimeMillis()
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            generator.initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    // TLS signs a digest it computed itself (NONE), as well as whole messages.
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setCertificateSubject(X500Principal("CN=Cablegram phone"))
                    .setCertificateSerialNumber(BigInteger.valueOf(now))
                    .setCertificateNotBefore(Date(now - 24 * 3_600_000L))
                    .setCertificateNotAfter(Date(now + VALID_YEARS * 365L * 24 * 3_600_000L))
                    .build(),
            )
            generator.generateKeyPair()
            val entry = store.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
            return LanTlsIdentity(entry.privateKey, entry.certificate as X509Certificate)
        }

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
