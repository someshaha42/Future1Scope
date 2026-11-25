package com.example.framescope

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.framescope.ui.navigation.FrameScopeNavHost
import com.example.framescope.ui.theme.FrameScopeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        val app = application as FrameScopeApplication
        setContent {
            FrameScopeTheme {
                FrameScopeNavHost(application = app)
            }
        }
    }
}
