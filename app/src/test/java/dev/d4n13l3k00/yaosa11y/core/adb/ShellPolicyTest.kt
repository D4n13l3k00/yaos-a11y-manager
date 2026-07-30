package dev.d4n13l3k00.yaosa11y.core.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ShellPolicyTest {
    @Test
    fun acceptsAndroidPackageNames() {
        assertEquals(
            "com.yandex.tv.services.platform",
            ShellPolicy.requirePackageName("com.yandex.tv.services.platform"),
        )
    }

    @Test
    fun rejectsShellSyntaxInPackageName() {
        assertThrows(IllegalStateException::class.java) {
            ShellPolicy.requirePackageName("com.example.app;reboot")
        }
    }

    @Test
    fun quotesSingleQuotesForPosixShell() {
        assertEquals("'a'\"'\"'b'", ShellPolicy.quote("a'b"))
    }
}
