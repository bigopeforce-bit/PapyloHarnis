package com.androidharness.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashLogPendingTest {
    @JvmField @Rule val folder = TemporaryFolder()

    private fun report(name: String, modifiedAt: Long): File {
        val dir = File(folder.root, "crashes").apply { mkdirs() }
        return File(dir, name).apply {
            writeText("O2OAgent crash\nthread: main\njava.lang.RuntimeException: test\n")
            assertTrue(setLastModified(modifiedAt))
        }
    }

    @Test fun newCrashIsPendingUntilTheAgentReadsIt() {
        val first = report("crash-old.txt", 1_000L)
        assertEquals(first, CrashLog.pendingReport(folder.root))

        CrashLog.markHandled(folder.root)
        assertNull(CrashLog.pendingReport(folder.root))

        val second = report("crash-new.txt", System.currentTimeMillis() + 60_000L)
        assertEquals(second, CrashLog.pendingReport(folder.root))
    }

    @Test fun handledMarkerIsNotMistakenForACrashReport() {
        assertTrue(CrashLog.latest(folder.root).contains("No persisted"))
        CrashLog.markHandled(folder.root)
        assertTrue(CrashLog.pendingReport(folder.root) == null)
    }
}
