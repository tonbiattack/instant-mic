package com.tonbiattack.instantmic.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tonbiattack.instantmic.domain.CustomCommands
import com.tonbiattack.instantmic.domain.TermDictionary
import com.tonbiattack.instantmic.speech.IncrementalMode
import com.tonbiattack.instantmic.speech.RecognitionEngine
import com.tonbiattack.instantmic.speech.SilenceMode
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
    val autoStartListening: Boolean = false,
    /** Which speech service to ask Android for. */
    val recognitionEngine: RecognitionEngine = RecognitionEngine.OnDevice,
    /** How long to wait for silence before ending an utterance. */
    val silenceMode: SilenceMode = SilenceMode.Standard,
    /** Whether settled phrases are typed while the user is still speaking. */
    val incrementalMode: IncrementalMode = IncrementalMode.Off,
    /** Whether the built-in development-term dictionary is applied. */
    val termDictionary: Boolean = true,
    /**
     * Write dictated text in hiragana instead of kanji.
     *
     * Useful when the text goes to a coding assistant: the recognizer's kanji
     * choice is the main source of errors, and an LLM reads hiragana fine.
     */
    val hiraganaOutput: Boolean = false,
    /** Terms the user added to the dictionary, on top of the built-in list. */
    val userTerms: List<TermDictionary.Term> = emptyList(),
    /** Ask before running a destructive command. */
    val confirmDestructive: Boolean = false,
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
        val autoStart = stringPreferencesKey("auto_start_listening")
        val recognitionEngine = stringPreferencesKey("recognition_engine")
        val silenceMode = stringPreferencesKey("silence_mode")
        val incrementalMode = stringPreferencesKey("incremental_mode")
        val termDictionary = stringPreferencesKey("term_dictionary")
        val hiraganaOutput = stringPreferencesKey("hiragana_output")
        val userTerms = stringPreferencesKey("user_terms")
        val confirmDestructive = stringPreferencesKey("confirm_destructive")
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
            autoStartListening = prefs[Keys.autoStart]?.toBooleanStrictOrNull() ?: false,
            recognitionEngine = RecognitionEngine.fromStorage(prefs[Keys.recognitionEngine]),
            silenceMode = SilenceMode.fromStorage(prefs[Keys.silenceMode]),
            incrementalMode = IncrementalMode.fromStorage(prefs[Keys.incrementalMode]),
            termDictionary = prefs[Keys.termDictionary]?.toBooleanStrictOrNull() ?: true,
            hiraganaOutput = prefs[Keys.hiraganaOutput]?.toBooleanStrictOrNull() ?: false,
            userTerms = decodeTerms(prefs[Keys.userTerms]),
            confirmDestructive = prefs[Keys.confirmDestructive]?.toBooleanStrictOrNull() ?: false,
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

    suspend fun setAutoStartListening(enabled: Boolean) {
        context.dataStore.edit { it[Keys.autoStart] = enabled.toString() }
    }

    suspend fun setRecognitionEngine(engine: RecognitionEngine) {
        context.dataStore.edit { it[Keys.recognitionEngine] = engine.name }
    }

    suspend fun setSilenceMode(mode: SilenceMode) {
        context.dataStore.edit { it[Keys.silenceMode] = mode.name }
    }

    suspend fun setIncrementalMode(mode: IncrementalMode) {
        context.dataStore.edit { it[Keys.incrementalMode] = mode.name }
    }

    suspend fun setTermDictionary(enabled: Boolean) {
        context.dataStore.edit { it[Keys.termDictionary] = enabled.toString() }
    }

    suspend fun setHiraganaOutput(enabled: Boolean) {
        context.dataStore.edit { it[Keys.hiraganaOutput] = enabled.toString() }
    }

    suspend fun setConfirmDestructive(enabled: Boolean) {
        context.dataStore.edit { it[Keys.confirmDestructive] = enabled.toString() }
    }

    suspend fun saveUserTerms(terms: List<TermDictionary.Term>) {
        context.dataStore.edit { it[Keys.userTerms] = encodeTerms(terms) }
    }

    /**
     * User terms are stored as `text<TAB>mishearing` lines.
     *
     * A simple format keeps the file readable and avoids pulling a JSON
     * dependency into the data layer for two strings.
     */
    private fun encodeTerms(terms: List<TermDictionary.Term>): String =
        terms.joinToString("\n") { "${it.text}\t${it.mishearings.joinToString(",")}" }

    private fun decodeTerms(raw: String?): List<TermDictionary.Term> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val parts = line.split('\t', limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val text = parts[0]
            val mishearings = parts[1].split(',').filter { it.isNotEmpty() }
            if (text.isEmpty() || mishearings.isEmpty()) return@mapNotNull null
            TermDictionary.Term(text, mishearings, fixCase = true, userDefined = true)
        }.toList()
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
