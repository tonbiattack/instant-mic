package com.tonbiattack.instantmic

import android.Manifest
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

        setContent {
            InstantMicTheme {
                LaunchedEffect(Unit) { viewModel.refreshCapability() }
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

    override fun onPause() {
        super.onPause()
        // Background recording is out of scope: stop cleanly so the loop cannot
        // keep the microphone (or the battery) busy behind another app.
        viewModel.stopListening()
    }
}
