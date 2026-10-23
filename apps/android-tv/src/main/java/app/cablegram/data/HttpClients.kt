package app.cablegram.data

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Every OkHttp client in the app starts here; HttpClientsTest fails on any other `OkHttpClient` construction.
 * Release builds permit cleartext so a self-hosted server on a home network works over plain HTTP. [PrivateCleartextOnly]
 * keeps everything else on HTTPS, which the network security config enforced before.
 */
fun cablegramHttpClient(): OkHttpClient.Builder = OkHttpClient.Builder().addNetworkInterceptor(PrivateCleartextOnly)

/** Plain HTTP only to loopback and private-network hosts. A network interceptor, so it also sees redirects. */
object PrivateCleartextOnly : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (!url.isHttps && !isPrivateLanHost(url.host)) throw IOException("Plain HTTP is allowed only to home-network addresses, not ${url.host}")
        return chain.proceed(chain.request())
    }
}
