package dev.promethe.core

import kotlin.test.*
import java.io.File

class PrometheHomeTest {
    @Test
    fun `PrometheHome dir is under user home`() {
        val userHome = System.getProperty("user.home")
        assertTrue(PrometheHome.dir.path.contains(".promethe"), "dir path should contain .promethe")
        assertEquals(File(userHome), PrometheHome.dir.parentFile, "dir should be under user.home")
    }

    @Test
    fun `skillsDir is under promethe dir`() {
        assertEquals(PrometheHome.dir, PrometheHome.skillsDir.parentFile)
    }

    @Test
    fun `pluginsDir is under promethe dir`() {
        assertEquals(PrometheHome.dir, PrometheHome.pluginsDir.parentFile)
    }

    @Test
    fun `workspaceDir is isolated under promethe dir`() {
        assertEquals(PrometheHome.dir, PrometheHome.workspaceDir.parentFile)
        assertEquals("workspace", PrometheHome.workspaceDir.name)
    }

    @Test
    fun `dbFile is under promethe dir`() {
        assertEquals(PrometheHome.dir, PrometheHome.dbFile.parentFile)
        assertEquals("promethe.db", PrometheHome.dbFile.name)
    }

    @Test
    fun `absolutePath matches dir`() {
        assertEquals(PrometheHome.dir.absolutePath, PrometheHome.absolutePath)
    }

    @Test
    fun `ensureDirectories creates dirs`() {
        PrometheHome.ensureDirectories()
        assertTrue(PrometheHome.dir.exists(), "dir should exist after ensureDirectories")
        assertTrue(PrometheHome.skillsDir.exists(), "skillsDir should exist after ensureDirectories")
        assertTrue(PrometheHome.pluginsDir.exists(), "pluginsDir should exist after ensureDirectories")
        assertTrue(PrometheHome.workspaceDir.exists(), "workspaceDir should exist after ensureDirectories")
    }
}
