package dev.d4n13l3k00.yaosa11y.feature.apps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageCommandPolicyTest {
    @Test
    fun acceptsKnownPackageManagerResponses() {
        assertTrue(PackageCommandPolicy.isSuccess("Success"))
        assertTrue(PackageCommandPolicy.isSuccess("Package com.example installed for user: 0"))
        assertTrue(PackageCommandPolicy.isSuccess(""))
    }

    @Test
    fun rejectsFailureText() {
        assertFalse(PackageCommandPolicy.isSuccess("Failure [INSTALL_FAILED_INVALID_APK]"))
        assertFalse(PackageCommandPolicy.isSuccess("Error: package not found"))
    }
}
