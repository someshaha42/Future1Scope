package com.example.framescope

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.framescope.ui.navigation.FrameScopeNavHost
import com.example.framescope.ui.theme.FrameScopeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        splashScreen.setOnExitAnimationListener { splashScreenView ->
            splashScreenView.remove()
        }
        enableEdgeToEdge()
        val app = application as FrameScopeApplication
        setContent {
            FrameScopeTheme {
                FrameScopeNavHost(application = app)
            }
        }
    }
}
