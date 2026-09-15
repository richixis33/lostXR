package com.samrat.cardboardhands

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/** Protected entry point used by PhoneXR-signed OpenXR games to start hand tracking. */
class TrackingBootstrapActivity : ComponentActivity() {
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startHands()
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startHands()
            finish()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startHands() {
        ContextCompat.startForegroundService(this, Intent(this, HandTrackingService::class.java))
    }
}
