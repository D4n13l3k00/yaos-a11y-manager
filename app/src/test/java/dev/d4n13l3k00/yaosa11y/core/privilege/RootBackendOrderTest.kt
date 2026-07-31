package dev.d4n13l3k00.yaosa11y.core.privilege

import org.junit.Assert.assertEquals
import org.junit.Test

class RootBackendOrderTest {
    @Test
    fun storedBackendIsTriedFirstWithoutDuplicates() {
        assertEquals(
            listOf(
                RootBackend.SU,
                RootBackend.APP_SU,
                RootBackend.ADB_ROOT,
                RootBackend.CVTE_AT_SUDO,
            ),
            RootBackendOrder.candidates(
                stored = RootBackend.SU,
                allowAdbRestart = true,
                supportsCvte = true,
            ),
        )
    }

    @Test
    fun genericPlatformNeverTriesCvte() {
        assertEquals(
            listOf(RootBackend.APP_SU, RootBackend.SU),
            RootBackendOrder.candidates(
                stored = null,
                allowAdbRestart = false,
                supportsCvte = false,
            ),
        )
    }

    @Test
    fun directAppSuDoesNotRequireAdb() {
        assertEquals(false, RootBackend.APP_SU.requiresAdb)
        assertEquals(true, RootBackend.SU.requiresAdb)
    }

    @Test
    fun directSuPassesCommandAsOneArgument() {
        assertEquals(
            listOf("su", "-c", "pm grant dev.example android.permission.WRITE_SECURE_SETTINGS"),
            DirectSuCommand.arguments(
                "pm grant dev.example android.permission.WRITE_SECURE_SETTINGS",
            ),
        )
    }
}
