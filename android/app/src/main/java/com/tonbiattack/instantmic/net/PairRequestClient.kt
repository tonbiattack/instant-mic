package com.tonbiattack.instantmic.net

import android.annotation.SuppressLint
import com.tonbiattack.instantmic.protocol.Fingerprints
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.X509TrustManager

/** A receiver found on the LAN that might be asked to pair. */
data class PairCandidate(
    val host: String,
    val port: Int,
    val serverName: String,
    val fingerprint: String,
)

/** Result of asking to pair. */
sealed interface PairAskResult {
    /** The PC approved and returned a one-time secret. */
    data class Approved(val secret: String) : PairAskResult

    /** The user on the PC said no. */
    data object Denied : PairAskResult

    /** Nobody answered in time (the PC window was left open). */
    data object Expired : PairAskResult

    /** The PC is reachable but does not support requests (older build). */
    data object Unsupported : PairAskResult

    /** The PC could not be reached or refused the request. */
    data class Failed(val message: String) : PairAskResult
}

/**
 * Asks a PC to pair, instead of the user typing a code or scanning a QR.
 *
 * The request grants nothing by itself: the receiver shows a prompt on the PC
 * and only a person there can approve it. That is why this can be offered as a
 * one-tap action without weakening pairing — an attacker on the LAN can make the
 * prompt appear, but cannot answer it.
 *
 * The response is polled because approval takes as long as the person needs.
 * Nothing is cached on the phone: the one-time secret arrives once and is used
 * immediately.
 */
class PairRequestClient(
    private val pollIntervalMs: Long = 1_000L,
    private val timeoutMs: Long = 130_000L,
) {

    /**
     * Sends a request and polls until the PC decides.
     *
     * [onWaiting] is called once, so the UI can explain that someone must
     * confirm on the PC. Blocking work runs on the calling thread; call it from
     * Dispatchers.IO.
     */
    fun request(
        candidate: PairCandidate,
        deviceId: String,
        deviceName: String,
        onWaiting: () -> Unit = {},
    ): PairAskResult {
        val client = insecureClient(candidate.fingerprint)
        val base = "https://${candidate.host}:${candidate.port}"

        val created = try {
            postCreate(client, base, deviceId, deviceName)
        } catch (e: UnsupportedException) {
            return PairAskResult.Unsupported
        } catch (e: SSLException) {
            return PairAskResult.Failed(
                "証明書が想定と異なります。LAN内の別のPCの可能性があります。",
            )
        } catch (e: IOException) {
            return PairAskResult.Failed(
                "PCに接続できません。同じWi-Fiか確認してください。(${e.message ?: "接続失敗"})",
            )
        }
        onWaiting()

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val status = try {
                getStatus(client, base, created)
            } catch (e: IOException) {
                // A single failed poll is not fatal: the PC may be busy showing
                // the prompt. Keep trying until the deadline.
                sleep(pollIntervalMs)
                continue
            }
            when (status.state) {
                "approved" -> {
                    val secret = status.secret
                    if (secret.isNullOrEmpty()) {
                        return PairAskResult.Expired
                    }
                    return PairAskResult.Approved(secret)
                }
                "denied" -> return PairAskResult.Denied
                "expired" -> return PairAskResult.Expired
            }
            sleep(pollIntervalMs)
        }
        return PairAskResult.Expired
    }

    private class UnsupportedException : IOException("pair requests unsupported")

    private fun postCreate(
        client: OkHttpClient,
        base: String,
        deviceId: String,
        deviceName: String,
    ): String {
        val payload = JSONObject().apply {
            put("deviceId", deviceId)
            put("deviceName", deviceName)
        }.toString()
        val request = Request.Builder()
            .url("$base/pair/request")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 501) throw UnsupportedException()
            if (!response.isSuccessful) {
                throw IOException("request rejected (HTTP ${response.code})")
            }
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw IOException("invalid response", e)
            }
            val id = json.optString("id")
            if (id.isEmpty()) throw IOException("missing request id")
            return id
        }
    }

    private data class Status(val state: String, val secret: String?)

    private fun getStatus(client: OkHttpClient, base: String, id: String): Status {
        val request = Request.Builder().url("$base/pair/request/$id").get().build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("poll failed (HTTP ${response.code})")
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw IOException("invalid poll response", e)
            }
            return Status(
                state = json.optString("state"),
                secret = json.optString("secret").ifEmpty { null },
            )
        }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Trusts the certificate only while its fingerprint matches, exactly like
     * pairing does. A discovered PC's fingerprint comes from mDNS, which is not
     * authenticated, so this still relies on the human confirming the prompt.
     */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun insecureClient(fingerprint: String): OkHttpClient {
        val expected = Fingerprints.normalize(fingerprint).takeIf { it.length == 64 }
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val cert = chain?.firstOrNull() ?: throw CertificateException("no certificate")
                if (expected != null && Tls.fingerprintOf(cert) != expected) {
                    throw CertificateException("certificate fingerprint mismatch")
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trust), java.security.SecureRandom())
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(context.socketFactory, trust)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }
}
