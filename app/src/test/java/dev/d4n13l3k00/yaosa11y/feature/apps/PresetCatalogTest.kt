package dev.d4n13l3k00.yaosa11y.feature.apps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.d4n13l3k00.yaosa11y.core.adb.ShellPolicy

class PresetCatalogTest {
    @Test
    fun protectedPlatformServiceIsNeverInCatalog() {
        assertFalse(
            PresetCatalog.definitions.any {
                it.packageName == "com.yandex.tv.services.platform"
            },
        )
    }

    @Test
    fun recommendedItemsAreNotCritical() {
        assertTrue(
            PresetCatalog.definitions
                .filter(PresetDefinition::recommended)
                .none { it.risk == PresetRisk.CRITICAL },
        )
    }

    @Test
    fun packageNamesAreUniqueAndValid() {
        val names = PresetCatalog.definitions.map(PresetDefinition::packageName)
        assertTrue(names.size == names.distinct().size)
        names.forEach(ShellPolicy::requirePackageName)
    }
}
