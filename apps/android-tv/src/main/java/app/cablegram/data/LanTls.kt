package app.cablegram.data

import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/**
 * TLS to the household phones' LAN servers, trusted by certificate pin rather than a certificate authority (CAB-48).
 *
 * Each phone makes its own self-signed certificate and publishes its SHA-256 to the control plane with its LAN address;
 * the TV reads both from `/api/me` and [remember]s them. A connection to a pinned address succeeds only when the
 * server's certificate is exactly that one, and any other address is checked as usual against the system's
 * authorities, so one client can load the phone's posters and public artwork alike.
 */
object LanTls {
    private val pins = ConcurrentHashMap<String, String>()

    private fun key(host: String, port: Int) = "$host:$port"

    /** [host] is the IP literal the phone announced, as the TV connects to it. */
    fun remember(host: String, port: Int, sha256: String) {
        pins[key(host, port)] = sha256.lowercase()
    }

    fun isPinned(host: String, port: Int) = pins.containsKey(key(host, port))

    fun clear() = pins.clear()

    /** [base] with pinning for the phones' addresses and the system's trust for everything else. */
    fun pinned(base: OkHttpClient): OkHttpClient {
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
        val fallback = base.hostnameVerifier
        return base.newBuilder()
            .sslSocketFactory(context.socketFactory, trustManager)
            .hostnameVerifier(HostnameVerifier { hostname: String, session: SSLSession ->
                val pin = pins[key(hostname, session.peerPort)]
                // A self-signed phone certificate names no host; the pin, checked again here, stands in for the name.
                if (pin != null) runCatching { matches(session.peerCertificates.first() as X509Certificate, pin) }.getOrDefault(false)
                else fallback.verify(hostname, session)
            })
            .build()
    }

    fun sha256Hex(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }

    private fun matches(certificate: X509Certificate, pin: String) = MessageDigest.isEqual(
        sha256Hex(certificate).toByteArray(), pin.toByteArray(),
    )

    private val system: X509TrustManager by lazy {
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private val trustManager = object : X509ExtendedTrustManager() {
        private fun check(chain: Array<out X509Certificate>, host: String?, port: Int, systemCheck: () -> Unit) {
            val pin = host?.let { pins[key(it, port)] } ?: return systemCheck()
            if (chain.isEmpty() || !matches(chain[0], pin)) throw CertificateException("Not the paired phone's LAN certificate")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) =
            check(chain, socket?.inetAddress?.hostAddress, socket?.port ?: -1) {
                (system as? X509ExtendedTrustManager)?.checkServerTrusted(chain, authType, socket) ?: system.checkServerTrusted(chain, authType)
            }

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) =
            check(chain, engine?.peerHost, engine?.peerPort ?: -1) {
                (system as? X509ExtendedTrustManager)?.checkServerTrusted(chain, authType, engine) ?: system.checkServerTrusted(chain, authType)
            }

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = system.checkServerTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = throw CertificateException("No client certificates")
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) = throw CertificateException("No client certificates")
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) = throw CertificateException("No client certificates")

        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
    }
}
