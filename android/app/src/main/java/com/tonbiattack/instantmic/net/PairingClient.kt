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

/** Result of a pairing request. */
data class PairingResult(
    val token: String,
    val serverId: String,
    val serverName: String,
    val commandPrefix: String,
    val certificateFingerprint: String,
)

class PairingException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Performs the one-time pairing handshake over HTTPS.
 *
 * The receiver uses a self-signed certificate, so the pairing request accepts it
 * once and records its SHA-256 fingerprint. Every later connection pins that
 * fingerprint, which is what protects the link from a LAN man-in-the-middle
 * after pairing.
 *
 * When the user has already read the fingerprint off the PC, it is enforced
 * during the TLS handshake, so a wrong peer never receives the pairing code.
 */
class PairingClient {

    /**
     * Pairs using a scanned QR payload. The fingerprint from the QR is enforced
     * during the TLS handshake, so a replayed or tampered code cannot redirect
     * the pairing to another machine.
     */
    fun pairWithQR(
        payload: com.tonbiattack.instantmic.protocol.PairingPayload,
        deviceId: String,
        deviceName: String,
    ): PairingResult = pair(
        host = payload.host,
        port = payload.port,
        code = "",
        deviceId = deviceId,
        deviceName = deviceName,
        fingerprintHint = payload.fingerprint,
        qrSecret = payload.secret,
    )

    fun pair(
        host: String,
        port: Int,
        code: String,
        deviceId: String,
        deviceName: String,
        fingerprintHint: String? = null,
        qrSecret: String? = null,
    ): PairingResult {
        val expected = fingerprintHint
            ?.let { Fingerprints.normalize(it) }
            ?.takeIf { it.length == 64 }

        val captures = CapturingTrustManager(expected)
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(captures), java.security.SecureRandom())
        }
        val client = OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, captures)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()

        val payload = JSONObject().apply {
            if (qrSecret != null) put("qrSecret", qrSecret) else put("code", code.trim())
            put("deviceId", deviceId)
            put("deviceName", deviceName)
        }.toString()

        val request = Request.Builder()
            .url("https://$host:$port/pair")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()

        val body = try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw PairingException(pairingErrorMessage(response.code, text))
                }
                text
            }
        } catch (e: PairingException) {
            throw e
        } catch (e: SSLException) {
            if (e.message?.contains("fingerprint", ignoreCase = true) == true) {
                throw PairingException("証明書の指紋がPCと一致しません。LAN内の別端末の可能性があります。", e)
            }
            throw PairingException("TLS接続に失敗しました。PCの受信アプリが起動しているか確認してください。", e)
        } catch (e: IOException) {
            throw PairingException(connectErrorMessage(e), e)
        }

        val fingerprint = captures.fingerprint
            ?: throw PairingException("サーバー証明書を取得できませんでした")

        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw PairingException("サーバーの応答を解析できませんでした", e)
        }
        val token = json.optString("token")
        if (token.isEmpty()) {
            throw PairingException("ペアリングトークンを受け取れませんでした")
        }
        return PairingResult(
            token = token,
            serverId = json.optString("serverId"),
            serverName = json.optString("serverName").ifEmpty { host },
            commandPrefix = json.optString("commandPrefix").ifEmpty { "コマンド" },
            certificateFingerprint = fingerprint,
        )
    }

    private fun pairingErrorMessage(status: Int, body: String): String = when {
        status == 403 -> "コードが正しくありません。PCに表示された6桁を入力してください。"
        status == 410 -> "ペアリングの有効期限が切れています。PCで [p] を押してやり直してください。"
        status == 429 -> "試行回数が多すぎます。1分ほど待ってから再試行してください。"
        body.contains("not open") -> "PC側でペアリングが開始されていません。"
        else -> "ペアリングに失敗しました (HTTP $status)"
    }

    private fun connectErrorMessage(e: IOException): String =
        "PCに接続できません。同じWi-Fiか、IPアドレスとポートを確認してください。(${e.message ?: "接続失敗"})"
}

/**
 * Trusts the peer's certificate while recording its fingerprint. When an
 * expected fingerprint is supplied, a different certificate aborts the
 * handshake instead of being recorded.
 */
@SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
internal class CapturingTrustManager(private val expected: String? = null) : X509TrustManager {
    @Volatile
    var fingerprint: String? = null
        private set

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val cert = chain?.firstOrNull() ?: throw CertificateException("no certificate")
        val actual = Tls.fingerprintOf(cert)
        if (expected != null && actual != expected) {
            throw CertificateException("certificate fingerprint mismatch")
        }
        fingerprint = actual
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Shared JSON media type for both pairing and tests. */
val JSON_MEDIA: okhttp3.MediaType = "application/json; charset=utf-8".toMediaType()
