package dev.promethe.app.platform

import java.awt.EventQueue
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFileChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual suspend fun pickSandboxDirectory(): String? =
    withContext(Dispatchers.IO) {
        if (java.awt.GraphicsEnvironment.isHeadless()) return@withContext null

        val selected = AtomicReference<File?>(null)
        EventQueue.invokeAndWait {
            JFileChooser().apply {
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                isMultiSelectionEnabled = false
                if (showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    selected.set(selectedFile)
                }
            }
        }
        selected.get()?.toPath()?.toAbsolutePath()?.normalize()?.toString()
    }
