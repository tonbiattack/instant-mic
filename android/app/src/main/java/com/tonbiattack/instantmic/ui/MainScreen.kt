package com.tonbiattack.instantmic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tonbiattack.instantmic.data.Pairing
import com.tonbiattack.instantmic.domain.CustomCommand
import com.tonbiattack.instantmic.domain.CustomCommands
import com.tonbiattack.instantmic.domain.KeySequence
import com.tonbiattack.instantmic.net.ConnectionState
import com.tonbiattack.instantmic.speech.SpeechState
import com.tonbiattack.instantmic.speech.isActive

object TestTags {
    const val MIC_BUTTON = "mic_button"
    const val PAIR_HOST = "pair_host"
    const val PAIR_PORT = "pair_port"
    const val PAIR_CODE = "pair_code"
    const val PAIR_SUBMIT = "pair_submit"
    const val CONNECTION_STATUS = "connection_status"
    const val ACTION_STATUS = "action_status"
    const val SPEECH_STATUS = "speech_status"
    const val COMMAND_PHRASE = "command_phrase"
    const val COMMAND_SEQUENCE = "command_sequence"
    const val COMMAND_SAVE = "command_save"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onRequestMicrophone: () -> Unit,
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    var showCommands by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("instant-mic") },
                actions = {
                    if (state.pairing != null) {
                        TextButton(
                            onClick = {
                                // Editing settings while the microphone is open would
                                // keep recording behind the panel, so stop first.
                                if (!showCommands && state.speech.isActive) viewModel.toggleListening()
                                showCommands = !showCommands
                            },
                        ) {
                            Text(if (showCommands) "戻る" else "コマンド")
                        }
                        TextButton(onClick = viewModel::unpair) { Text("解除") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val pairing = state.pairing
            if (pairing == null) {
                PairingPanel(
                    state = state,
                    onPair = viewModel::pair,
                    onDismissError = viewModel::clearPairingError,
                )
            } else if (showCommands) {
                CommandsPanel(
                    state = state,
                    onSave = viewModel::saveCustomCommand,
                    onDelete = viewModel::deleteCustomCommand,
                    onClearError = viewModel::clearCommandEditorError,
                    onContinuousChange = viewModel::setContinuousListening,
                    onHapticsChange = viewModel::setHapticsEnabled,
                )
            } else {
                ConnectionCard(
                    connection = state.connection,
                    pairing = pairing,
                    onReconnect = viewModel::reconnect,
                )
                MicrophonePanel(
                    state = state,
                    onToggle = {
                        if (!state.microphoneGranted) onRequestMicrophone() else viewModel.toggleListening()
                    },
                )
                ActionStatus(state, onDismiss = viewModel::dismissAction)
                CapabilityNotice(state)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    connection: ConnectionState,
    pairing: Pairing,
    onReconnect: () -> Unit,
) {
    val (label, color) = when (connection) {
        is ConnectionState.Connected -> "接続中: ${pairing.serverName}" to MaterialTheme.colorScheme.primary
        ConnectionState.Connecting -> "接続しています…" to MaterialTheme.colorScheme.tertiary
        is ConnectionState.Failed -> connection.message to MaterialTheme.colorScheme.error
        ConnectionState.Disconnected -> "未接続" to MaterialTheme.colorScheme.error
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                label,
                color = color,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag(TestTags.CONNECTION_STATUS),
            )
            Text(
                "${pairing.host}:${pairing.port} / ${pairing.deviceId.take(8)}…",
                style = MaterialTheme.typography.bodySmall,
            )
            val latency = (connection as? ConnectionState.Connected)?.latencyMs
            if (latency != null && latency >= 0) {
                Text("遅延: 約 ${latency}ms", style = MaterialTheme.typography.bodySmall)
            }
            if (connection !is ConnectionState.Connected) {
                OutlinedButton(onClick = onReconnect) { Text("再接続") }
            }
        }
    }
}

/** Everything the microphone button shows, derived from the real recognizer state. */
private data class MicVisual(
    val label: String,
    val detail: String,
    val active: Boolean,
    val column: Color,
    val container: Color,
)

@Composable
private fun micVisual(state: UiState): MicVisual {
    val scheme = MaterialTheme.colorScheme
    val speech = state.speech
    return when {
        speech is SpeechState.Listening -> MicVisual(
            label = "話してください",
            detail = "聞き取り中 — タップで停止",
            active = true,
            column = scheme.onError,
            container = scheme.error,
        )

        speech is SpeechState.Preparing -> MicVisual(
            label = "準備しています…",
            detail = "まもなく話せます",
            active = true,
            column = scheme.onTertiaryContainer,
            container = scheme.tertiaryContainer,
        )

        speech is SpeechState.Processing -> MicVisual(
            label = "認識しています…",
            detail = "結果を確定しています",
            active = true,
            column = scheme.onSecondaryContainer,
            container = scheme.secondaryContainer,
        )

        speech is SpeechState.Resuming -> MicVisual(
            label = "もう一度話せます",
            detail = "再開待ち（${speech.delayMs}ms）",
            active = true,
            column = scheme.onTertiaryContainer,
            container = scheme.tertiaryContainer,
        )

        speech is SpeechState.Failed -> MicVisual(
            label = if (state.microphoneGranted) "タップして話す" else "マイクを許可して開始",
            detail = speech.message,
            active = false,
            column = scheme.onErrorContainer,
            container = scheme.errorContainer,
        )

        !state.microphoneGranted -> MicVisual(
            label = "マイクを許可して開始",
            detail = "タップすると権限の確認が出ます",
            active = false,
            column = scheme.onPrimary,
            container = scheme.primary,
        )

        else -> MicVisual(
            label = "タップして話す",
            detail = if (state.continuousListening) {
                "停止するまで続けて入力できます"
            } else {
                "1回の発話ごとに停止します"
            },
            active = false,
            column = scheme.onPrimary,
            container = scheme.primary,
        )
    }
}

@Composable
private fun MicrophonePanel(
    state: UiState,
    onToggle: () -> Unit,
) {
    val visual = micVisual(state)
    val speech = state.speech
    val active = speech.isActive

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The whole area is the target, not just the label: one-handed use must
        // never require aiming at small text.
        Button(
            onClick = onToggle,
            shape = MaterialTheme.shapes.extraLarge,
            colors = ButtonDefaults.buttonColors(
                containerColor = visual.container,
                contentColor = visual.column,
            ),
            contentPadding = PaddingValues(vertical = 28.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 148.dp)
                .semantics {
                    contentDescription = if (active) {
                        "音声入力を停止"
                    } else {
                        "音声入力を開始"
                    }
                }
                .testTag(TestTags.MIC_BUTTON),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (speech is SpeechState.Processing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(26.dp),
                        strokeWidth = 2.dp,
                        color = visual.column,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .background(visual.column, CircleShape),
                    )
                }
                Text(
                    visual.label,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    visual.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag(TestTags.SPEECH_STATUS),
                )
            }
        }
        Text(
            state.commandHint,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        if (active) {
            Text(
                "認識結果は確定後のみ送信されます",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!active && state.lastAction is LastAction.None && state.lastText.isNotEmpty()) {
            Text(
                "前回の送信: ${state.lastText.take(40)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ActionStatus(state: UiState, onDismiss: () -> Unit) {
    val action = state.lastAction
    if (action is LastAction.None) return
    val (text, isError) = when (action) {
        LastAction.None -> return
        is LastAction.Sending -> "送信中… ${action.preview}" to false
        is LastAction.Typed -> "送信しました（${action.elapsedMs}ms）: ${action.preview}" to false
        is LastAction.KeySent -> {
            val count = if (action.command.count > 1) "（${action.command.count}回）" else ""
            "実行: ${action.command.label}$count（${action.elapsedMs}ms）" to false
        }
        is LastAction.Duplicate -> "送信済みです（二重実行を防止）: ${action.preview}" to false
        is LastAction.Failed -> "失敗: ${action.message}" to true
        is LastAction.NoCommand -> "音声が空でした。もう一度話してください。" to true
        is LastAction.UnknownCommand ->
            "未対応のコマンドです。キー操作は行いませんでした: ${action.raw}" to true
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(start = 14.dp, top = 8.dp, end = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(TestTags.ACTION_STATUS),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onDismiss) { Text("閉じる") }
            }
            if (state.lastTranscript.isNotEmpty()) {
                Text(
                    "認識: ${state.lastTranscript.take(60)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CapabilityNotice(state: UiState) {
    val capability = state.capability
    if (!capability.available) {
        Text(
            "この端末には音声認識サービスがありません。Google アプリなどをインストールしてください。",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    val message = if (capability.onDeviceAvailable) {
        "端末内の音声認識を優先します。利用できない場合のみクラウド認識になります（音声が送信されます）。"
    } else {
        "この端末では音声認識がクラウドで処理される可能性があります。音声ファイルはアプリ内に保存しません。"
    }
    Text(message, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun CommandsPanel(
    state: UiState,
    onSave: (String, String, String?) -> Boolean,
    onDelete: (String) -> Unit,
    onClearError: () -> Unit,
    onContinuousChange: (Boolean) -> Unit,
    onHapticsChange: (Boolean) -> Unit,
) {
    // The editor state lives here so a list row can load itself into the fields.
    var phrase by rememberSaveable { mutableStateOf("") }
    var sequence by rememberSaveable { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("音声入力の設定", style = MaterialTheme.typography.titleLarge)
        SettingSwitch(
            title = "連続音声入力",
            description = "停止するまで、発話ごとに自動で聞き取りを再開します。",
            checked = state.continuousListening,
            onChange = onContinuousChange,
        )
        SettingSwitch(
            title = "開始・終了の振動",
            description = if (state.hapticsAvailable) {
                "準備完了で1回、聞き取り終了で2回振動します。"
            } else {
                "この端末は振動に対応していません（画面表示で確認できます）。"
            },
            checked = state.hapticsEnabled,
            onChange = onHapticsChange,
            enabled = state.hapticsAvailable,
        )

        Spacer(Modifier.height(4.dp))
        Text("カスタム音声コマンド", style = MaterialTheme.typography.titleLarge)
        Text(
            "「コマンド、フレーズ」で登録したキー操作を実行します。キーは ctrl+enter、home>shift+end のように指定します。",
            style = MaterialTheme.typography.bodySmall,
        )
        if (state.commandEditorError != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        state.commandEditorError,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onClearError) { Text("閉じる") }
                }
            }
        }
        CommandEditor(
            phrase = phrase,
            sequence = sequence,
            editing = editing,
            onPhraseChange = { phrase = it },
            onSequenceChange = { sequence = it },
            onSave = {
                if (onSave(phrase, sequence, editing)) {
                    phrase = ""
                    sequence = ""
                    editing = null
                }
            },
            onCancel = {
                phrase = ""
                sequence = ""
                editing = null
            },
        )

        if (state.customCommands.isEmpty()) {
            Text("登録されたコマンドはありません。", style = MaterialTheme.typography.bodySmall)
        } else {
            for (command in state.customCommands) {
                CustomCommandRow(
                    command,
                    onEdit = {
                        phrase = command.phrase
                        sequence = command.sequence
                        editing = command.phrase
                    },
                    onDelete = {
                        onDelete(it)
                        if (editing == it) {
                            phrase = ""
                            sequence = ""
                            editing = null
                        }
                    },
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        Text("標準コマンド", style = MaterialTheme.typography.titleMedium)
        for (line in BUILTIN_HINTS) {
            Text(line, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
        }
    }
}

@Composable
private fun CommandEditor(
    phrase: String,
    sequence: String,
    editing: String?,
    onPhraseChange: (String) -> Unit,
    onSequenceChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = phrase,
            onValueChange = onPhraseChange,
            label = { Text("呼び出しフレーズ") },
            placeholder = { Text("AIに送信") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.COMMAND_PHRASE),
        )
        OutlinedTextField(
            value = sequence,
            onValueChange = onSequenceChange,
            label = { Text("キー操作") },
            placeholder = { Text("ctrl+enter") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
            supportingText = {
                val preview = sequence.takeIf { it.isNotBlank() }?.let { KeySequence.label(it) }
                Text(preview ?: "例: ctrl+enter / home>shift+end>delete")
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.COMMAND_SEQUENCE),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onSave,
                modifier = Modifier.testTag(TestTags.COMMAND_SAVE),
            ) {
                Text(if (editing == null) "登録" else "更新")
            }
            if (editing != null) {
                TextButton(onClick = onCancel) { Text("キャンセル") }
            }
        }
        if (editing == null) {
            Text(
                "登録済みの行をタップすると、ここに読み込んで編集できます。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CustomCommandRow(
    command: CustomCommand,
    onEdit: () -> Unit,
    onDelete: (String) -> Unit,
) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .clickable(onClick = onEdit),
            ) {
                Text(command.phrase, style = MaterialTheme.typography.bodyLarge)
                Text(
                    CustomCommands.describe(command),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = { onDelete(command.phrase) }) { Text("削除") }
        }
    }
}

private val BUILTIN_HINTS = listOf(
    "送信 → Enter / 改行 → Shift+Enter",
    "削除 → Backspace / 前の単語削除 → Ctrl+Backspace / 行削除",
    "デリート / 上 / 下 / 左 / 右（「3回削除」で繰り返し）",
    "コピー / 貼り付け / 切り取り / やり直し / タブ切り替え",
    "取り消し → Ctrl+Z / 全選択 → Ctrl+A",
)

@Composable
private fun PairingPanel(
    state: UiState,
    onPair: (String, String, String, String?) -> Unit,
    onDismissError: () -> Unit,
) {
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("8765") }
    var code by rememberSaveable { mutableStateOf("") }
    var fingerprint by rememberSaveable { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("PCとペアリング", style = MaterialTheme.typography.titleLarge)
        Text(
            "PCで受信アプリを起動し、[p] を押して表示された6桁のコードを入力してください。",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("PCのIPアドレス") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.PAIR_HOST),
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter(Char::isDigit).take(5) },
            label = { Text("ポート") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.PAIR_PORT),
        )
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter(Char::isDigit).take(6) },
            label = { Text("6桁のコード") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.PAIR_CODE),
        )
        OutlinedTextField(
            value = fingerprint,
            onValueChange = { fingerprint = it },
            label = { Text("証明書の指紋（任意）") },
            supportingText = { Text("PC画面に表示された値を貼り付けると改ざん検出が強くなります") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.pairingError != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Text(state.pairingError, color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(onClick = onDismissError) { Text("閉じる") }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onPair(host, port, code, fingerprint.ifBlank { null }) },
                enabled = !state.pairingInProgress,
                modifier = Modifier.testTag(TestTags.PAIR_SUBMIT),
            ) {
                if (state.pairingInProgress) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text(if (state.pairingInProgress) "接続中…" else "ペアリング")
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
