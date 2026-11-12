package com.tonbiattack.instantmic.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tonbiattack.instantmic.domain.CustomCommands
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "instant_mic")

/** The paired receiver and this phone's identity. */
data class Pairing(
    val deviceId: String,
    val token: String,
    val host: String,
    val port: Int,
    val serverName: String,
    val serverId: String,
    val certificateFingerprint: String,
    val commandPrefix: String,
) {
    val isComplete: Boolean
        get() = token.isNotEmpty() && host.isNotEmpty() && port in 1..65535
}

data class Settings(
    val pairing: Pairing?,
    val lastText: String,
    val customCommands: List<com.tonbiattack.instantmic.domain.CustomCommand> = emptyList(),
    val continuousListening: Boolean = true,
    val hapticsEnabled: Boolean = true,
) {
    companion object {
        val Empty = Settings(pairing = null, lastText = "")
    }
}

/** Persists pairing material. The token is stored in app-private storage. */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val deviceId = stringPreferencesKey("device_id")
        val token = stringPreferencesKey("token")
        val host = stringPreferencesKey("host")
        val port = stringPreferencesKey("port")
        val serverName = stringPreferencesKey("server_name")
        val serverId = stringPreferencesKey("server_id")
        val fingerprint = stringPreferencesKey("certificate_fingerprint")
        val commandPrefix = stringPreferencesKey("command_prefix")
        val lastText = stringPreferencesKey("last_text")
        val customCommands = stringPreferencesKey("custom_commands")
        val continuousListening = stringPreferencesKey("continuous_listening")
        val haptics = stringPreferencesKey("haptics_enabled")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        val deviceId = prefs[Keys.deviceId].orEmpty()
        val token = prefs[Keys.token].orEmpty()
        val host = prefs[Keys.host].orEmpty()
        val extras = Settings(
            pairing = null,
            lastText = prefs[Keys.lastText].orEmpty(),
            customCommands = CustomCommands.decode(prefs[Keys.customCommands]),
            continuousListening = prefs[Keys.continuousListening]?.toBooleanStrictOrNull() ?: true,
            hapticsEnabled = prefs[Keys.haptics]?.toBooleanStrictOrNull() ?: true,
        )
        if (token.isEmpty() || host.isEmpty()) {
            extras
        } else {
            extras.copy(
                pairing = Pairing(
                    deviceId = deviceId,
                    token = token,
                    host = host,
                    port = prefs[Keys.port]?.toIntOrNull() ?: DEFAULT_PORT,
                    serverName = prefs[Keys.serverName].orEmpty(),
                    serverId = prefs[Keys.serverId].orEmpty(),
                    certificateFingerprint = prefs[Keys.fingerprint].orEmpty(),
                    commandPrefix = prefs[Keys.commandPrefix].orEmpty().ifEmpty { "コマンド" },
                ),
            )
        }
    }

    suspend fun current(): Settings = settings.first()

    suspend fun savePairing(pairing: Pairing) {
        context.dataStore.edit { prefs ->
            prefs[Keys.deviceId] = pairing.deviceId
            prefs[Keys.token] = pairing.token
            prefs[Keys.host] = pairing.host
            prefs[Keys.port] = pairing.port.toString()
            prefs[Keys.serverName] = pairing.serverName
            prefs[Keys.serverId] = pairing.serverId
            prefs[Keys.fingerprint] = pairing.certificateFingerprint
            prefs[Keys.commandPrefix] = pairing.commandPrefix
        }
    }

    suspend fun saveLastText(text: String) {
        context.dataStore.edit { it[Keys.lastText] = text }
    }

    suspend fun saveCustomCommands(commands: List<com.tonbiattack.instantmic.domain.CustomCommand>) {
        context.dataStore.edit { it[Keys.customCommands] = CustomCommands.encode(commands) }
    }

    suspend fun setContinuousListening(enabled: Boolean) {
        context.dataStore.edit { it[Keys.continuousListening] = enabled.toString() }
    }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.haptics] = enabled.toString() }
    }

    suspend fun clearPairing() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.token)
            prefs.remove(Keys.host)
            prefs.remove(Keys.port)
            prefs.remove(Keys.serverName)
            prefs.remove(Keys.serverId)
            prefs.remove(Keys.fingerprint)
        }
    }

    companion object {
        const val DEFAULT_PORT = 8765
    }
}
