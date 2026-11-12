package com.tonbiattack.instantmic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tonbiattack.instantmic.data.Pairing
import com.tonbiattack.instantmic.net.ConnectionState
import com.tonbiattack.instantmic.speech.SpeechState

object TestTags {
    const val MIC_BUTTON = "mic_button"
    const val PAIR_HOST = "pair_host"
    const val PAIR_PORT = "pair_port"
    const val PAIR_CODE = "pair_code"
    const val PAIR_SUBMIT = "pair_submit"
    const val CONNECTION_STATUS = "connection_status"
    const val ACTION_STATUS = "action_status"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onRequestMicrophone: () -> Unit,
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("instant-mic") },
                actions = {
                    if (state.pairing != null) {
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

@Composable
private fun MicrophonePanel(
    state: UiState,
    onToggle: () -> Unit,
) {
    val speech = state.speech
    val listening = speech is SpeechState.Listening || speech is SpeechState.Processing
    val label = when {
        speech is SpeechState.Listening -> "聞き取り中… タップで停止"
        speech is SpeechState.Processing -> "認識しています…"
        !state.microphoneGranted -> "マイクを許可して開始"
        else -> "タップして話す"
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(
            onClick = onToggle,
            enabled = !state.busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .testTag(TestTags.MIC_BUTTON),
        ) {
            if (speech is SpeechState.Processing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.size(12.dp))
            }
            Text(label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
        Text(
            state.commandHint,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        if (listening) {
            Text(
                "認識結果は確定後のみ送信されます",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!listening && state.lastAction is LastAction.None && state.lastText.isNotEmpty()) {
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
        is LastAction.KeySent -> "${action.action.label} を送信しました（${action.elapsedMs}ms）" to false
        is LastAction.Duplicate -> "送信済みです（二重実行を防止）: ${action.preview}" to false
        is LastAction.Failed -> "失敗: ${action.message}" to true
        is LastAction.NoCommand -> "音声が空でした。もう一度話してください。" to true
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                modifier = Modifier
                    .weight(1f)
                    .testTag(TestTags.ACTION_STATUS),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onDismiss) { Text("閉じる") }
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
