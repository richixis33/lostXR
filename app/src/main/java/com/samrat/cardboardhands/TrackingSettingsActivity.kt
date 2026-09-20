package com.samrat.cardboardhands

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class TrackingSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LostXRTheme {
                TrackingSettingsScreen(onBack = { finish() })
            }
        }
    }
}
