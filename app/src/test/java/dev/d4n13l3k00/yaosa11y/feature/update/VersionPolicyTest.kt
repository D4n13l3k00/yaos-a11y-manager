package dev.d4n13l3k00.yaosa11y.feature.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionPolicyTest {
    @Test
    fun comparesSemanticVersions() {
        assertTrue(VersionPolicy.isNewer("v1.2.1", "1.2.0"))
        assertTrue(VersionPolicy.isNewer("1.10.0", "1.9.9"))
        assertFalse(VersionPolicy.isNewer("1.2.0", "1.2.0"))
        assertFalse(VersionPolicy.isNewer("1.1.9", "1.2.0"))
    }

    @Test
    fun stableVersionIsNewerThanPreRelease() {
        assertTrue(VersionPolicy.isNewer("1.2.0", "1.2.0-rc.1"))
        assertFalse(VersionPolicy.isNewer("1.2.0-beta.2", "1.2.0"))
    }
}
