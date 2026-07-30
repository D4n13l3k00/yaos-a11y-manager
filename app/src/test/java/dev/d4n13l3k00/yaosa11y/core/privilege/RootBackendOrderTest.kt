package dev.d4n13l3k00.yaosa11y.core.privilege

import org.junit.Assert.assertEquals
import org.junit.Test

class RootBackendOrderTest {
    @Test
    fun storedBackendIsTriedFirstWithoutDuplicates() {
        assertEquals(
            listOf(
                RootBackend.SU,
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
            listOf(RootBackend.SU),
            RootBackendOrder.candidates(
                stored = null,
                allowAdbRestart = false,
                supportsCvte = false,
            ),
        )
    }
}
