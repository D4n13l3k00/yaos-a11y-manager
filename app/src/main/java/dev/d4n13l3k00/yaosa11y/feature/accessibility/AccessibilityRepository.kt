package dev.d4n13l3k00.yaosa11y.feature.accessibility

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

enum class AccessibilityAvailability {
    AVAILABLE,
    PACKAGE_DISABLED,
    COMPONENT_DISABLED,
    INVALID_DECLARATION,
}

data class AccessibilityEntry(
    val resolveInfo: ResolveInfo,
    val accessibilityInfo: AccessibilityServiceInfo?,
    val component: ComponentName,
    val label: String,
    val enabled: Boolean,
    val availability: AccessibilityAvailability,
)

class AccessibilityRepository(private val context: Context) {
    private val manager =
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager

    fun canWriteSecureSettings(): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun entries(): List<AccessibilityEntry> {
        val enabled = enabledComponents()
        val managerEntries = manager.installedAccessibilityServiceList.associateBy { info ->
            info.resolveInfo.serviceInfo.toComponent()
        }
        val discovered = LinkedHashMap<ComponentName, ResolveInfo>()

        managerEntries.forEach { (component, info) ->
            discovered[component] = info.resolveInfo
        }
        queryDeclaredServices().forEach { resolveInfo ->
            val component = resolveInfo.serviceInfo.toComponent()
            if (component !in discovered) discovered[component] = resolveInfo
        }
        scanDeclaredServices().forEach { resolveInfo ->
            val component = resolveInfo.serviceInfo.toComponent()
            if (component !in discovered) discovered[component] = resolveInfo
        }

        return discovered
            .map { (component, resolveInfo) ->
                val service = resolveInfo.serviceInfo
                AccessibilityEntry(
                    resolveInfo = resolveInfo,
                    accessibilityInfo = managerEntries[component],
                    component = component,
                    label = resolveInfo.loadLabel(context.packageManager).toString()
                        .ifBlank { component.className.substringAfterLast('.') },
                    enabled = component in enabled,
                    availability = availability(service),
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

        val installed = entries().associateBy { it.component }
        if (installed[component]?.availability != AccessibilityAvailability.AVAILABLE) return false

        val components = enabledComponents()
        val previousComponents = LinkedHashSet(components)
        val previousGlobal = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            if (components.isEmpty()) 0 else 1,
        )
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

        val verified = enabledComponents() == components &&
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                if (components.isEmpty()) 0 else 1,
            ) == if (components.isEmpty()) 0 else 1
        if (!servicesWritten || !globalWritten || !verified) {
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                previousComponents.joinToString(":") { it.flattenToString() },
            )
            Settings.Secure.putInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                previousGlobal,
            )
            return false
        }
        return true
    }

    @Suppress("DEPRECATION")
    private fun queryDeclaredServices(): List<ResolveInfo> {
        val flags = PackageManager.GET_META_DATA or PackageManager.GET_DISABLED_COMPONENTS
        return runCatching {
            context.packageManager.queryIntentServices(
                Intent(AccessibilityService.SERVICE_INTERFACE),
                flags,
            )
        }.getOrDefault(emptyList())
    }

    @Suppress("DEPRECATION")
    private fun scanDeclaredServices(): List<ResolveInfo> {
        val flags = PackageManager.GET_SERVICES or
            PackageManager.GET_META_DATA or
            PackageManager.GET_DISABLED_COMPONENTS
        return runCatching {
            context.packageManager.getInstalledPackages(flags)
                .asSequence()
                .flatMap { packageInfo -> packageInfo.services.orEmpty().asSequence() }
                .filter(::looksLikeAccessibilityService)
                .map { serviceInfo ->
                    ResolveInfo().apply { this.serviceInfo = serviceInfo }
                }
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun looksLikeAccessibilityService(service: ServiceInfo): Boolean =
        service.permission == Manifest.permission.BIND_ACCESSIBILITY_SERVICE ||
            service.metaData?.containsKey(AccessibilityService.SERVICE_META_DATA) == true

    private fun availability(service: ServiceInfo): AccessibilityAvailability {
        if (!service.applicationInfo.enabled) return AccessibilityAvailability.PACKAGE_DISABLED
        if (!service.enabled) return AccessibilityAvailability.COMPONENT_DISABLED
        if (!looksLikeAccessibilityService(service)) {
            return AccessibilityAvailability.INVALID_DECLARATION
        }
        return AccessibilityAvailability.AVAILABLE
    }

    private fun ServiceInfo.toComponent(): ComponentName =
        ComponentName(packageName, name)
}
