package dev.promethe.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/**
 * Android entry point for Promethe.
 * Acts as a "light client" — connects to a remote gateway via HTTP/WS.
 * All UI is shared via Compose Multiplatform (commonMain).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            App()
        }
    }
}
