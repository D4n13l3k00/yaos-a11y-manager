package dev.d4n13l3k00.yaosa11y.core.privilege

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import dadb.Dadb
import dadb.RootResult
import dev.d4n13l3k00.yaosa11y.core.adb.AdbGateway
import dev.d4n13l3k00.yaosa11y.core.adb.ShellPolicy
import dev.d4n13l3k00.yaosa11y.core.platform.CvteAdbBootstrap
import dev.d4n13l3k00.yaosa11y.core.platform.PlatformProfileResolver

/**
 * Coordinates ADB availability, protected permission grants and root backends.
 *
 * Backend-specific command wrapping lives in [PrivilegeBackend]; connection
 * creation and serialization live in [AdbGateway].
 */
class PrivilegeManager(
    context: Context,
    private val gateway: AdbGateway = AdbGateway(),
) {
    data class Result(
        val success: Boolean,
        val message: String,
        val rootBackend: RootBackend? = null,
    )

    data class Snapshot(
        val adbAvailable: Boolean,
        val secureSettingsGranted: Boolean,
        val rootBackend: RootBackend?,
        val message: String,
    )

    private val appContext = context.applicationContext
    private val profile = PlatformProfileResolver(appContext).resolve()
    private val preferences =
        appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val backends: Map<RootBackend, PrivilegeBackend> = listOf(
        AdbRootBackend(gateway),
        SuPrivilegeBackend(gateway),
        CvteAtSudoBackend(appContext, gateway),
    ).associateBy(PrivilegeBackend::id)

    fun ensureAdb(timeoutMillis: Long = 15_000): Result = gateway.exclusive {
        val initial = gateway.probe()
        if (initial.available) return@exclusive Result(true, "Локальный ADB shell уже работает")

        if (!profile.supportsCvteFactoryApi) {
            return@exclusive Result(
                false,
                "Локальный ADB недоступен, а CVTE Factory API на платформе " +
                    "${profile.displayName} не найден. Включите ADB вручную в меню разработчика.",
            )
        }

        val cvte = CvteAdbBootstrap(appContext).enableThroughFactoryService()
        if (!cvte.success) {
            return@exclusive Result(
                false,
                "Локальный ADB недоступен. ${cvte.message}. " +
                    "Откройте меню разработчика и включите ADB вручную.",
            )
        }

        val deadline = System.currentTimeMillis() + timeoutMillis
        var lastProbe = initial
        while (System.currentTimeMillis() < deadline) {
            lastProbe = gateway.probe()
            if (lastProbe.available) {
                return@exclusive Result(true, "ADB включён через CVTE Factory API")
            }
            Thread.sleep(250)
        }
        Result(
            false,
            "CVTE принял команду, но shell не появился: ${lastProbe.message}. " +
                "Проверьте переключатель ADB вручную.",
        )
    }

    fun ensureSecureSettings(): Result = gateway.exclusive {
        if (hasSecureSettingsPermission()) {
            return@exclusive Result(
                true,
                "WRITE_SECURE_SETTINGS уже выдано",
                storedRootBackend(),
            )
        }

        val adb = ensureAdb()
        if (!adb.success) return@exclusive adb
        val packageName = ShellPolicy.requirePackageName(appContext.packageName)
        val grantCommand = "pm grant $packageName ${Manifest.permission.WRITE_SECURE_SETTINGS}"
        val ordinaryGrant = runCatching {
            gateway.withConnection { connection -> gateway.shell(connection, grantCommand) }
            check(hasSecureSettingsPermission()) {
                "ADB выполнил pm grant, но разрешение не появилось"
            }
        }
        if (ordinaryGrant.isSuccess) {
            return@exclusive Result(true, "WRITE_SECURE_SETTINGS выдано обычным ADB shell")
        }

        val root = ensureRootBackend()
        if (!root.success || root.rootBackend == null) {
            return@exclusive Result(
                false,
                "Обычный ADB не смог выдать WRITE_SECURE_SETTINGS: " +
                    "${ordinaryGrant.exceptionOrNull()?.message}. ${root.message}",
            )
        }
        gateway.withConnection { connection ->
            shellAsRoot(connection, root.rootBackend, grantCommand)
        }
        if (!hasSecureSettingsPermission()) {
            return@exclusive Result(
                false,
                "${root.rootBackend.displayName} выполнил pm grant, " +
                    "но WRITE_SECURE_SETTINGS не появилось",
                root.rootBackend,
            )
        }
        Result(
            true,
            "WRITE_SECURE_SETTINGS выдано через ${root.rootBackend.displayName}",
            root.rootBackend,
        )
    }

    fun ensureRootBackend(allowAdbRestart: Boolean = true): Result = gateway.exclusive {
        val adb = ensureAdb()
        if (!adb.success) return@exclusive adb

        if (validateBackend(RootBackend.ADB_ROOT)) {
            return@exclusive remember(RootBackend.ADB_ROOT)
        }

        val stored = storedRootBackend()
        if (stored != null && validateBackend(stored)) {
            return@exclusive remember(stored)
        }
        if (validateBackend(RootBackend.SU)) {
            return@exclusive remember(RootBackend.SU)
        }

        var adbRootFailure = "adb root не проверялся"
        if (allowAdbRestart) {
            val request = runCatching {
                gateway.withConnection { connection -> connection.root() }
            }
            adbRootFailure = request.fold(
                onSuccess = { result ->
                    when (result) {
                        is RootResult.Success -> "adbd перезапущен"
                        is RootResult.Failure -> result.reason
                    }
                },
                onFailure = { it.message ?: it.javaClass.simpleName },
            )
            if (waitForAdb() && validateBackend(RootBackend.ADB_ROOT)) {
                return@exclusive remember(RootBackend.ADB_ROOT)
            }
        }

        if (
            profile.supportsCvteFactoryApi &&
            validateBackend(RootBackend.CVTE_AT_SUDO)
        ) {
            return@exclusive remember(RootBackend.CVTE_AT_SUDO)
        }

        preferences.edit().remove(KEY_ROOT_BACKEND).apply()
        Result(
            false,
            "Root-бэкенд не найден: su недоступен" +
                if (profile.supportsCvteFactoryApi) {
                    ", CVTE at_sudo недоступен; adb root: $adbRootFailure"
                } else {
                    "; CVTE не поддерживается этой платформой; adb root: $adbRootFailure"
                },
        )
    }

    fun snapshot(): Snapshot {
        val probe = gateway.probe()
        return Snapshot(
            adbAvailable = probe.available,
            secureSettingsGranted = hasSecureSettingsPermission(),
            rootBackend = storedRootBackend(),
            message = probe.message,
        )
    }

    fun shellAsRoot(adb: Dadb, backend: RootBackend, command: String): String =
        requireNotNull(backends[backend]) { "Неизвестный root-бэкенд: $backend" }
            .execute(adb, command)

    fun <T> withAdb(
        connectTimeout: Int = 5_000,
        socketTimeout: Int = 30_000,
        block: (Dadb) -> T,
    ): T = gateway.withConnection(connectTimeout, socketTimeout, block)

    private fun validateBackend(backend: RootBackend): Boolean =
        runCatching {
            gateway.withConnection { connection ->
                isRoot(requireNotNull(backends[backend]).execute(connection, "id"))
            }
        }.getOrDefault(false)

    private fun waitForAdb(): Boolean {
        repeat(ADB_RECONNECT_ATTEMPTS) {
            if (gateway.probe().available) return true
            Thread.sleep(ADB_RECONNECT_DELAY_MILLIS)
        }
        return false
    }

    private fun remember(backend: RootBackend): Result {
        preferences.edit().putString(KEY_ROOT_BACKEND, backend.name).apply()
        return Result(
            true,
            "Root доступен через ${backend.displayName}",
            backend,
        )
    }

    private fun storedRootBackend(): RootBackend? =
        preferences.getString(KEY_ROOT_BACKEND, null)
            ?.let { name -> RootBackend.entries.firstOrNull { it.name == name } }

    private fun hasSecureSettingsPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    private fun isRoot(output: String): Boolean = ROOT_ID.containsMatchIn(output)

    companion object {
        private const val PREFERENCES = "privilege_manager"
        private const val KEY_ROOT_BACKEND = "root_backend"
        private const val ADB_RECONNECT_ATTEMPTS = 20
        private const val ADB_RECONNECT_DELAY_MILLIS = 250L
        private val ROOT_ID = Regex("""(?:^|\s)uid=0\(root\)""")
    }
}
