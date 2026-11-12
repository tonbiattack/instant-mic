package com.tonbiattack.instantmic

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import com.tonbiattack.instantmic.ui.MainScreen
import com.tonbiattack.instantmic.ui.MainViewModel
import com.tonbiattack.instantmic.ui.theme.InstantMicTheme
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val requestMicrophone = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.setMicrophoneGranted(granted)
        if (granted) viewModel.toggleListening()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        viewModel.setMicrophoneGranted(granted)

        // A tap on the Quick Settings tile, or the "start talking on launch"
        // setting, begins listening as soon as the screen is up.
        val fromTile = intent?.action == ACTION_START_LISTENING
        val autoStart = fromTile || viewModel.shouldAutoStart()

        setContent {
            InstantMicTheme {
                LaunchedEffect(Unit) {
                    viewModel.refreshCapability()
                    if (autoStart) {
                        // onCreate may be a configuration change; only start once
                        // per tap so a rotation does not restart the microphone.
                        if (savedInstanceState == null) {
                            if (viewModel.canStartListening()) {
                                viewModel.toggleListening()
                            } else if (!granted) {
                                // Ask for the permission, then start on grant.
                                requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    }
                }
                DisposableEffect(Unit) {
                    onDispose { viewModel.cancelListening() }
                }
                MainScreen(
                    viewModel = viewModel,
                    onRequestMicrophone = { requestMicrophone.launch(Manifest.permission.RECORD_AUDIO) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Tapping the tile while the app is already open must start listening
        // again rather than just bringing the old screen forward.
        if (intent.action == ACTION_START_LISTENING) {
            if (viewModel.canStartListening()) {
                viewModel.toggleListening()
            } else {
                requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Background recording is out of scope: stop cleanly so the loop cannot
        // keep the microphone (or the battery) busy behind another app.
        viewModel.stopListening()
    }

    companion object {
        /** Sent by the Quick Settings tile. */
        const val ACTION_START_LISTENING = "com.tonbiattack.instantmic.action.START_LISTENING"
    }
}
