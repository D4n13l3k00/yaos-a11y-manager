package dev.d4n13l3k00.yaosa11y.core.platform

import android.content.Context
import android.content.pm.PackageManager

data class PlatformProfile(
    val id: String,
    val displayName: String,
    val nativeHookTargetPackage: String?,
    val nativeHookStagePath: String,
    val nativeHookRuntimePath: String,
    val supportsCvteFactoryApi: Boolean,
    val engineeringEndpoints: List<EngineeringEndpoint>,
)

sealed interface EngineeringEndpoint {
    val label: String

    data class Activity(
        override val label: String,
        val className: String,
    ) : EngineeringEndpoint

    data class Action(
        override val label: String,
        val action: String,
    ) : EngineeringEndpoint

    data class Service(
        override val label: String,
        val packageName: String,
        val className: String,
        val extraKey: String,
        val extraValue: String,
    ) : EngineeringEndpoint
}

class PlatformProfileResolver(private val context: Context) {
    fun resolve(): PlatformProfile {
        val cvte = hasPackage(CVTE_FACTORY_API_PACKAGE)
        val yaos = hasPackage(YAOS_PLATFORM_PACKAGE)
        return when {
            cvte && yaos -> Profiles.YAOS_CVTE
            cvte -> Profiles.CVTE_GENERIC
            yaos -> Profiles.YAOS_GENERIC
            else -> Profiles.GENERIC_ANDROID_TV
        }
    }

    @Suppress("DEPRECATION")
    private fun hasPackage(packageName: String): Boolean =
        runCatching {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_SERVICES or PackageManager.GET_ACTIVITIES,
            )
        }.isSuccess

    companion object {
        const val CVTE_FACTORY_API_PACKAGE = "com.cvte.factory.service"
        const val YAOS_PLATFORM_PACKAGE = "com.yandex.tv.services.platform"
    }
}

object Profiles {
    private val commonEngineering = listOf(
        EngineeringEndpoint.Activity(
            "Design Menu",
            "mediatek.tvsetting.factory.ui.designmenu.DesignMenuActivity",
        ),
        EngineeringEndpoint.Action(
            "MStar Factory",
            "mstar.tvsetting.factory.intent.action.MainmenuActivity",
        ),
        EngineeringEndpoint.Action(
            "MediaTek Factory",
            "mediatek.intent.action.MainmenuActivity",
        ),
        EngineeringEndpoint.Action(
            "Cultraview Factory",
            "com.cultraview.ctvfactorymenu.ui.FactoryMenuActivity",
        ),
        EngineeringEndpoint.Service(
            label = "CVTE service",
            packageName = "com.cvte.fac.menu",
            className = "com.cvte.fac.menu.app.TvMenuWindowManagerService",
            extraKey = "com.cvte.fac.menu.commmand",
            extraValue = "com.cvte.fac.menu.commmand.factory_menu",
        ),
    )

    val YAOS_CVTE = PlatformProfile(
        id = "yaos-cvte",
        displayName = "YAOS / CVTE",
        nativeHookTargetPackage = PlatformProfileResolver.YAOS_PLATFORM_PACKAGE,
        nativeHookStagePath = "/data/local/tmp/yaos-a11y-stage",
        nativeHookRuntimePath = "/data/local/yaos-a11y/runtime",
        supportsCvteFactoryApi = true,
        engineeringEndpoints = commonEngineering,
    )

    val YAOS_GENERIC = YAOS_CVTE.copy(
        id = "yaos-generic",
        displayName = "YAOS / generic Android TV",
        supportsCvteFactoryApi = false,
    )

    val CVTE_GENERIC = YAOS_CVTE.copy(
        id = "cvte-generic",
        displayName = "CVTE / generic Android TV",
        nativeHookTargetPackage = null,
    )

    val GENERIC_ANDROID_TV = YAOS_GENERIC.copy(
        id = "generic-atv",
        displayName = "Generic Android TV",
        nativeHookTargetPackage = null,
    )
}
