package app.cablegram.data

import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import okhttp3.mockwebserver.MockWebServer

/** A self-signed certificate like a phone's LAN one (CAB-48), from `src/test/resources/lan-tls-test.p12`. */
object TestLanCert {
    private val store: KeyStore = KeyStore.getInstance("PKCS12").apply {
        TestLanCert::class.java.getResourceAsStream("/lan-tls-test.p12")!!.use { load(it, "testpass".toCharArray()) }
    }

    val certificate = store.getCertificate("lan") as X509Certificate
    val sha256: String = LanTls.sha256Hex(certificate)

    val tlsSocketFactory: SSLSocketFactory by lazy {
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "testpass".toCharArray()) }
        SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }.socketFactory
    }

    /** A phone LAN server on 127.0.0.1 that speaks TLS with this certificate. */
    fun phone(): MockWebServer = MockWebServer().apply {
        useHttps(tlsSocketFactory, false)
        start(java.net.InetAddress.getByName("127.0.0.1"), 0)
    }
}
