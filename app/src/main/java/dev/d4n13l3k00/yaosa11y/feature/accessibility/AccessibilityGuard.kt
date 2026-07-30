package dev.d4n13l3k00.yaosa11y.feature.accessibility

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import dadb.Dadb
import dev.d4n13l3k00.yaosa11y.core.adb.AdbGateway
import dev.d4n13l3k00.yaosa11y.core.adb.ShellPolicy

class AccessibilityGuard(
    context: Context,
    private val stateRepository: ProtectionStateRepository,
) {
    data class ReconcileResult(
        val success: Boolean,
        val changed: Boolean,
        val message: String,
    )

    private val appContext = context.applicationContext

    fun reconcile(enabled: Boolean): ReconcileResult {
        if (!enabled) {
            return ReconcileResult(true, false, "Защита отключена пользователем")
        }
        if (!hasSecureSettingsPermission()) {
            val message = "Нет разрешения WRITE_SECURE_SETTINGS"
            stateRepository.recordFailure(message)
            return ReconcileResult(false, false, message)
        }

        val observed = readEnabledComponents()
        val snapshot = stateRepository.snapshot()
        if (snapshot.protectedComponents.isEmpty()) {
            val message = "Нет закреплённых служб"
            stateRepository.recordReconcile(observed, message)
            return ReconcileResult(true, false, message)
        }

        val target = stateRepository.reconcileTarget(observed)
        val globalEnabled = Settings.Secure.getInt(
            appContext.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            if (observed.isEmpty()) 0 else 1,
        )
        val expectedGlobal = if (target.isEmpty()) 0 else 1
        if (target == observed && globalEnabled == expectedGlobal) {
            val message = "Закреплённые службы в норме"
            stateRepository.recordReconcile(target, message)
            return ReconcileResult(true, false, message)
        }

        val previousRaw = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        val written = writeAccessibilitySettings(target)
        val verified = readEnabledComponents() == target &&
            Settings.Secure.getInt(
                appContext.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                expectedGlobal,
            ) == expectedGlobal
        if (!written || !verified) {
            Settings.Secure.putString(
                appContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                previousRaw,
            )
            Settings.Secure.putInt(
                appContext.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                globalEnabled,
            )
            val message = "Не удалось подтвердить восстановление служб; выполнен откат"
            stateRepository.recordFailure(message)
            return ReconcileResult(false, false, message)
        }

        val restored = target.filter { it !in observed }
            .joinToString { it.flattenToShortString() }
        val suppressed = observed.filter { it !in target }
            .joinToString { it.flattenToShortString() }
        val details = buildList {
            if (restored.isNotEmpty()) add("восстановлено: $restored")
            if (suppressed.isNotEmpty()) {
                add("возвращено в выключенное состояние: $suppressed")
            }
        }.joinToString("; ")
        val message = if (details.isEmpty()) {
            "Состояние закреплённых служб восстановлено"
        } else {
            "Обнаружено внешнее изменение — $details"
        }
        stateRepository.recordReconcile(target, message)
        return ReconcileResult(true, true, message)
    }

    fun readEnabledComponents(): LinkedHashSet<ComponentName> {
        val raw = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return parseComponents(raw)
    }

    fun restoreWithAdb(
        adbGateway: AdbGateway,
        adb: Dadb,
        services: Collection<ComponentName>,
    ) {
        val flattened = flattenComponents(services)
        if (flattened.isEmpty()) {
            adbGateway.shell(adb, "settings delete secure enabled_accessibility_services")
        } else {
            requireValidSettingsValue(flattened)
            adbGateway.shell(
                adb,
                "settings put secure enabled_accessibility_services " +
                    ShellPolicy.quote(flattened),
            )
        }
        adbGateway.shell(
            adb,
            "settings put secure accessibility_enabled ${if (services.isEmpty()) 0 else 1}",
        )
    }

    fun hasSecureSettingsPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun parseComponents(raw: String): LinkedHashSet<ComponentName> =
        raw.split(':')
            .mapNotNull(ComponentName::unflattenFromString)
            .toCollection(LinkedHashSet())

    fun flattenComponents(components: Collection<ComponentName>): String =
        components.joinToString(":") { it.flattenToString() }

    private fun writeAccessibilitySettings(components: Collection<ComponentName>): Boolean {
        val servicesWritten = Settings.Secure.putString(
            appContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            flattenComponents(components),
        )
        val globalWritten = Settings.Secure.putInt(
            appContext.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            if (components.isEmpty()) 0 else 1,
        )
        return servicesWritten && globalWritten
    }

    private fun requireValidSettingsValue(value: String) {
        check(value.matches(Regex("[A-Za-z0-9_.$/:]*"))) {
            "Список служб содержит неподдерживаемые символы"
        }
    }
}
