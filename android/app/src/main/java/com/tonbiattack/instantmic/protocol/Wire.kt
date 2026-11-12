package com.tonbiattack.instantmic.protocol

import org.json.JSONObject

/**
 * Wire messages shared with the Windows receiver.
 *
 * The format is JSON over a WebSocket, documented in `docs/protocol.md`.
 */
object Wire {
    const val VERSION = 1

    const val TYPE_AUTH = "auth"
    const val TYPE_READY = "ready"
    const val TYPE_TEXT = "text"
    const val TYPE_KEY = "key"
    const val TYPE_PING = "ping"
    const val TYPE_PONG = "pong"
    const val TYPE_ACK = "ack"
    const val TYPE_ERROR = "error"
}

/** The first frame on a connection. Carries the per-device token, not the code. */
data class AuthMessage(
    val deviceId: String,
    val deviceName: String,
    val token: String,
    val clientVersion: String = "",
) {
    fun toJson(): String = JSONObject().apply {
        put("type", Wire.TYPE_AUTH)
        put("protocol", Wire.VERSION)
        put("deviceId", deviceId)
        put("deviceName", deviceName)
        put("token", token)
        if (clientVersion.isNotEmpty()) put("clientVersion", clientVersion)
    }.toString()
}

data class ReadyMessage(
    val protocol: Int,
    val serverName: String,
    val serverId: String,
    val commandPrefix: String,
) {
    companion object {
        fun parse(json: JSONObject): ReadyMessage? {
            if (json.optString("type") != Wire.TYPE_READY) return null
            val protocol = json.optInt("protocol", -1)
            if (protocol != Wire.VERSION) return null
            return ReadyMessage(
                protocol = protocol,
                serverName = json.optString("serverName"),
                serverId = json.optString("serverId"),
                commandPrefix = json.optString("commandPrefix").ifEmpty { "コマンド" },
            )
        }
    }
}

data class TextMessage(val id: String, val text: String) {
    fun toJson(): String = JSONObject().apply {
        put("type", Wire.TYPE_TEXT)
        put("id", id)
        put("text", text)
    }.toString()
}

data class KeyMessage(val id: String, val key: String, val count: Int = 1) {
    fun toJson(): String = JSONObject().apply {
        put("type", Wire.TYPE_KEY)
        put("id", id)
        put("key", key)
        if (count > 1) put("count", count)
    }.toString()
}

data class PingMessage(val id: String, val sentAt: Long) {
    fun toJson(): String = JSONObject().apply {
        put("type", Wire.TYPE_PING)
        put("id", id)
        put("sentAt", sentAt)
    }.toString()
}

data class AckMessage(
    val id: String,
    val ok: Boolean,
    val duplicate: Boolean,
    val error: String?,
    val applied: String?,
    val elapsedMs: Long,
) {
    companion object {
        fun parse(json: JSONObject): AckMessage? {
            if (json.optString("type") != Wire.TYPE_ACK) return null
            val id = json.optString("id")
            if (id.isEmpty()) return null
            return AckMessage(
                id = id,
                ok = json.optBoolean("ok", false),
                duplicate = json.optBoolean("duplicate", false),
                error = json.optString("error").ifEmpty { null },
                applied = json.optString("applied").ifEmpty { null },
                elapsedMs = json.optLong("elapsedMs", 0L),
            )
        }
    }
}

data class ErrorMessage(val id: String?, val code: String, val message: String) {
    companion object {
        fun parse(json: JSONObject): ErrorMessage? {
            if (json.optString("type") != Wire.TYPE_ERROR) return null
            return ErrorMessage(
                id = json.optString("id").ifEmpty { null },
                code = json.optString("code"),
                message = json.optString("message"),
            )
        }
    }
}

data class PongMessage(val id: String?, val sentAt: Long) {
    companion object {
        fun parse(json: JSONObject): PongMessage? {
            if (json.optString("type") != Wire.TYPE_PONG) return null
            return PongMessage(
                id = json.optString("id").ifEmpty { null },
                sentAt = json.optLong("sentAt", 0L),
            )
        }
    }
}
