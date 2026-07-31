package dev.promethe.core

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer

fun getFileSystem(): FileSystem = FileSystem.SYSTEM

fun getProfileDirectoryPath(config: AgentConfig): Path = config.profileDirectory.toPath()

class ProfileManager(
    private val honchoClient: HonchoClient? = null,
) {
    private val fs = getFileSystem()

    /**
     * Lit le contenu d'un fichier du profil utilisateur (ex: USER.md, MEMORY.md).
     */
    fun readFile(
        config: AgentConfig,
        fileName: String,
    ): String {
        val dir = getProfileDirectoryPath(config)
        val file = dir / fileName

        if (!fs.exists(file)) {
            return ""
        }

        return fs.source(file).buffer().use { it.readUtf8() }
    }

    /**
     * Écrit le contenu d'un fichier du profil utilisateur de façon atomique.
     * Utilise un fichier temporaire .tmp puis le renomme pour éviter la corruption.
     */
    fun writeFileAtomically(
        config: AgentConfig,
        fileName: String,
        content: String,
    ) {
        val dir = getProfileDirectoryPath(config)

        // Crée les répertoires si nécessaire
        if (!fs.exists(dir)) {
            fs.createDirectories(dir)
        }

        val targetFile = dir / fileName
        val tempFile = dir / "$fileName.tmp"

        fs.sink(tempFile).buffer().use { it.writeUtf8(content) }

        // Renommage atomique
        fs.atomicMove(tempFile, targetFile)
    }

    /**
     * Synchronise le profil utilisateur avec Honcho (si configuré).
     */
    suspend fun syncProfileToHoncho(
        config: AgentConfig,
        sessionId: String,
    ): Boolean {
        val client = honchoClient ?: return false
        val userContent = readFile(config, "USER.md")
        val memoryContent = readFile(config, "MEMORY.md")
        return client.syncProfile(sessionId, userContent, memoryContent)
    }
}
