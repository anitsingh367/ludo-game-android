package com.example.ludoduel

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.example.ludoduel.ui.LudoNavHost
import com.example.ludoduel.ui.theme.LudoBackground
import com.example.ludoduel.ui.theme.LudoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light status/navigation bar icons on the dark gradient background.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        setContent {
            LudoTheme {
                LudoBackground(Modifier.fillMaxSize()) { LudoNavHost() }
            }
        }
    }
}
