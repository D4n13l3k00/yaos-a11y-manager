package dev.d4n13l3k00.yaosa11y

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

data class AccessibilityEntry(
    val info: AccessibilityServiceInfo,
    val component: ComponentName,
    val label: String,
    val enabled: Boolean,
)

class AccessibilityRepository(private val context: Context) {
    private val manager =
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager

    fun canWriteSecureSettings(): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun entries(): List<AccessibilityEntry> {
        val enabled = enabledComponents()
        return manager.installedAccessibilityServiceList
            .map { info ->
                val service = info.resolveInfo.serviceInfo
                val component = ComponentName(service.packageName, service.name)
                AccessibilityEntry(
                    info = info,
                    component = component,
                    label = info.resolveInfo.loadLabel(context.packageManager).toString(),
                    enabled = component in enabled,
                )
            }
            .sortedWith(
                compareByDescending<AccessibilityEntry> { it.enabled }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label },
            )
    }

    fun enabledComponents(): LinkedHashSet<ComponentName> {
        val raw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()

        return raw.split(':')
            .mapNotNull(ComponentName::unflattenFromString)
            .toCollection(LinkedHashSet())
    }

    fun setEnabled(component: ComponentName, enabled: Boolean): Boolean {
        if (!canWriteSecureSettings()) return false

        val installed = entries().mapTo(HashSet()) { it.component }
        if (component !in installed) return false

        val components = enabledComponents()
        if (enabled) {
            components.add(component)
        } else {
            components.remove(component)
        }

        val flattened = components.joinToString(":") { it.flattenToString() }
        val servicesWritten = Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            flattened,
        )
        val globalWritten = Settings.Secure.putInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            if (components.isEmpty()) 0 else 1,
        )

        return servicesWritten && globalWritten
    }
}
