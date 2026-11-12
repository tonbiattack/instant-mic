package com.tonbiattack.instantmic.net

import android.annotation.SuppressLint
import com.tonbiattack.instantmic.protocol.Fingerprints
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Builds an OkHttp client that trusts exactly one self-signed certificate.
 *
 * The receiver is a LAN appliance with a locally generated certificate, so
 * there is no CA to validate against. Pairing records the SHA-256 fingerprint of
 * the certificate and every later connection must present the same one; a
 * changed certificate is rejected instead of silently trusted.
 */
object Tls {
    const val DEFAULT_TIMEOUT_SECONDS = 10L

    fun pinnedClient(fingerprintHex: String, timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS): OkHttpClient {
        val expected = Fingerprints.normalize(fingerprintHex)
        require(expected.length == 64) { "fingerprint must be 64 hex characters" }

        val trustManager = PinnedTrustManager(expected)
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), java.security.SecureRandom())
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(context.socketFactory, trustManager)
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket keepalives govern liveness
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    internal fun fingerprintOf(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded)
            .joinToString("") { "%02x".format(it) }
}

/**
 * Enforces a pinned certificate fingerprint for every connection. The receiver
 * is self-signed, so this replaces CA validation with a single pinned identity.
 */
internal class PinnedTrustManager(private val expected: String) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    @SuppressLint("CustomX509TrustManager")
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val cert = chain?.firstOrNull() ?: throw CertificateException("no certificate")
        if (Tls.fingerprintOf(cert) != expected) {
            throw CertificateException("certificate fingerprint changed")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
