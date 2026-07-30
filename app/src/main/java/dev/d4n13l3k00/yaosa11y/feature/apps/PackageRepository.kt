package dev.d4n13l3k00.yaosa11y.feature.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

data class ManagedApp(
    val applicationInfo: ApplicationInfo,
    val label: String,
    val packageName: String,
    val versionName: String,
    val system: Boolean,
    val installedForUser: Boolean,
    val enabled: Boolean,
)

class PackageRepository(context: Context) {
    private val packageManager = context.applicationContext.packageManager

    @Suppress("DEPRECATION")
    fun loadApps(): List<ManagedApp> {
        val applications = packageManager.getInstalledApplications(
            PackageManager.GET_DISABLED_COMPONENTS or
                PackageManager.GET_UNINSTALLED_PACKAGES,
        )
        return applications.mapNotNull { info ->
            runCatching {
                val packageInfo = packageManager.getPackageInfo(
                    info.packageName,
                    PackageManager.GET_UNINSTALLED_PACKAGES,
                )
                val installed = info.flags and ApplicationInfo.FLAG_INSTALLED != 0
                ManagedApp(
                    applicationInfo = info,
                    label = packageManager.getApplicationLabel(info).toString(),
                    packageName = info.packageName,
                    versionName = packageInfo.versionName.orEmpty(),
                    system = info.flags and
                        (ApplicationInfo.FLAG_SYSTEM or
                            ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                    installedForUser = installed,
                    enabled = installed && info.enabled,
                )
            }.getOrNull()
        }.sortedWith(
            compareByDescending<ManagedApp> { it.installedForUser }
                .thenByDescending { !it.system }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label },
        )
    }
}
