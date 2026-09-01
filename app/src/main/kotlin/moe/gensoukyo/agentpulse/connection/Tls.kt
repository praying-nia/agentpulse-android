package moe.gensoukyo.agentpulse.connection

import android.annotation.SuppressLint
import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.SocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Dns
import okhttp3.OkHttpClient

internal fun pinnedClient(
    serverName: String,
    address: String,
    expectedSha256: String,
    socketFactory: SocketFactory? = null,
): OkHttpClient {
    val trust = FingerprintTrustManager(expectedSha256)
    return tlsClient(serverName, address, trust, socketFactory)
}

internal fun caClient(
    serverName: String,
    address: String,
    caBase64: String,
    socketFactory: SocketFactory? = null,
): OkHttpClient {
    val certificate = CertificateFactory.getInstance("X.509")
        .generateCertificate(android.util.Base64.decode(caBase64, android.util.Base64.DEFAULT).inputStream())
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null)
        setCertificateEntry("agentpulse-ca", certificate)
    }
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keyStore) }
    val trust = factory.trustManagers.filterIsInstance<X509TrustManager>().single()
    return tlsClient(serverName, address, trust, socketFactory)
}

private fun tlsClient(
    serverName: String,
    address: String,
    trust: X509TrustManager,
    socketFactory: SocketFactory? = null,
): OkHttpClient {
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
    val builder = OkHttpClient.Builder()
        .sslSocketFactory(context.socketFactory, trust)
        .dns(Dns { hostname ->
            if (hostname.equals(serverName, ignoreCase = true)) listOf(InetAddress.getByName(address))
            else Dns.SYSTEM.lookup(hostname)
        })
        .pingInterval(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
    if (socketFactory != null) builder.socketFactory(socketFactory)
    return builder.build()
}

@SuppressLint("CustomX509TrustManager")
private class FingerprintTrustManager(expected: String) : X509TrustManager {
    private val expected = expected.lowercase()

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("client certificates are not accepted")

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("server did not provide a certificate")
        leaf.checkValidity()
        val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded).joinToString("") { "%02x".format(it) }
        if (!MessageDigest.isEqual(actual.encodeToByteArray(), expected.encodeToByteArray())) {
            throw CertificateException("AgentPulse Host certificate fingerprint mismatch")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
