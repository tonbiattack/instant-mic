package com.tonbiattack.instantmic.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * Returns a launcher that scans a QR code.
 *
 * Google Code Scanner is preferred: it is a system-provided camera UI, starts
 * instantly, needs no CAMERA permission, and handles focus/zoom itself. Devices
 * without Play services, or where the module fails to start, fall back to the
 * bundled ZXing scanner so pairing still works.
 */
@Composable
fun rememberQrScanner(onScanned: (String) -> Unit): () -> Unit {
    val context = LocalContext.current

    val fallback = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (!contents.isNullOrBlank()) onScanned(contents)
    }

    val launchFallback = {
        fallback.launch(
            ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("PCに表示されたQRコードを読み取ります")
                setBeepEnabled(false)
                setOrientationLocked(false)
            },
        )
    }

    val hasPlayServices = remember(context) {
        try {
            GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        } catch (e: Exception) {
            false
        }
    }

    return {
        if (!hasPlayServices) {
            launchFallback()
        } else {
            try {
                val options = GmsBarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .enableAutoZoom()
                    .build()
                GmsBarcodeScanning.getClient(context, options)
                    .startScan()
                    .addOnSuccessListener { barcode ->
                        barcode.rawValue?.takeIf { it.isNotBlank() }?.let(onScanned)
                    }
                    .addOnFailureListener {
                        // Module unavailable or user-level failure: use ZXing.
                        launchFallback()
                    }
            } catch (e: Exception) {
                launchFallback()
            }
        }
    }
}
