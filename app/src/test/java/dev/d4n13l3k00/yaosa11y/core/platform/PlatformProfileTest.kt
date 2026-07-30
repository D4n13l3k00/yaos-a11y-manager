package dev.d4n13l3k00.yaosa11y.core.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformProfileTest {
    @Test
    fun cvteProfileCanBootstrapAdbWithoutYaosHook() {
        assertTrue(Profiles.CVTE_GENERIC.supportsCvteFactoryApi)
        assertNull(Profiles.CVTE_GENERIC.nativeHookTargetPackage)
    }

    @Test
    fun genericProfileHasNoVendorPrivilegePath() {
        assertFalse(Profiles.GENERIC_ANDROID_TV.supportsCvteFactoryApi)
        assertNull(Profiles.GENERIC_ANDROID_TV.nativeHookTargetPackage)
    }
}
