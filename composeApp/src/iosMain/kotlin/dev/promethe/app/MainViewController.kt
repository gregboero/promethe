package dev.promethe.app

import androidx.compose.ui.window.ComposeUIViewController

/**
 * iOS entry point for Promethe.
 * Called from Swift's ContentView to embed the shared Compose UI.
 * Acts as a "light client" — connects to a remote gateway via HTTP/WS.
 */
fun MainViewController() = ComposeUIViewController { App() }
