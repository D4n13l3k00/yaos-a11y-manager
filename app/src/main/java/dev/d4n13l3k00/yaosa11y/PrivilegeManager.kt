package dev.d4n13l3k00.yaosa11y

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import dadb.Dadb
import dadb.RootResult
import dadb.SyncResult
import java.io.File
import java.io.FileOutputStream
import okio.source

/**
 * Selects the strongest privilege mechanism available on the current firmware.
 *
 * CVTE is deliberately only one backend. The accessibility guard can work with
 * WRITE_SECURE_SETTINGS granted by an ordinary ADB shell and does not require
 * root or any CVTE component.
 */
class PrivilegeManager(private val context: Context) {
    enum class RootBackend(val displayName: String) {
        ADB_ROOT("ADB root"),
        SU("Magisk / su"),
        CVTE_AT_SUDO("CVTE at_sudo"),
    }

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

    private val preferences =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun ensureAdb(timeoutMillis: Long = 15_000): Result {
        val initial = probeLocalAdbShell()
        if (initial.success) {
            return Result(true, "Локальный ADB shell уже работает")
        }

        val cvte = CvteAdbBootstrap(context).enableThroughFactoryService()
        if (!cvte.success) {
            return Result(
                false,
                "Локальный ADB недоступен. ${cvte.message}",
            )
        }

        val deadline = System.currentTimeMillis() + timeoutMillis
        var lastProbe = initial
        while (System.currentTimeMillis() < deadline) {
            lastProbe = probeLocalAdbShell()
            if (lastProbe.success) {
                return Result(true, "ADB включён через CVTE Factory API")
            }
            Thread.sleep(250)
        }
        return Result(
            false,
            "CVTE включил ADB, но shell не появился: ${lastProbe.message}",
        )
    }

    fun ensureSecureSettings(): Result {
        if (hasSecureSettingsPermission()) {
            return Result(
                true,
                "WRITE_SECURE_SETTINGS уже выдано",
                storedRootBackend(),
            )
        }

        val adbResult = ensureAdb()
        if (!adbResult.success) return adbResult

        val ordinaryGrant = runCatching {
            openAdb().use { adb ->
                shellOk(
                    adb,
                    "pm grant ${context.packageName} " +
                        Manifest.permission.WRITE_SECURE_SETTINGS,
                )
            }
            check(hasSecureSettingsPermission()) {
                "ADB shell выполнил pm grant, но разрешение не появилось"
            }
        }
        if (ordinaryGrant.isSuccess) {
            return Result(
                true,
                "WRITE_SECURE_SETTINGS выдано обычным ADB shell",
            )
        }

        val root = ensureRootBackend()
        if (!root.success || root.rootBackend == null) {
            return Result(
                false,
                "Обычный ADB не смог выдать WRITE_SECURE_SETTINGS: " +
                    "${ordinaryGrant.exceptionOrNull()?.message}. ${root.message}",
            )
        }

        openAdb().use { adb ->
            shellAsRoot(
                adb,
                root.rootBackend,
                "pm grant ${context.packageName} " +
                    Manifest.permission.WRITE_SECURE_SETTINGS,
            )
        }
        if (!hasSecureSettingsPermission()) {
            return Result(
                false,
                "${root.rootBackend.displayName} выполнил pm grant, " +
                    "но WRITE_SECURE_SETTINGS не появилось",
                root.rootBackend,
            )
        }
        return Result(
            true,
            "WRITE_SECURE_SETTINGS выдано через ${root.rootBackend.displayName}",
            root.rootBackend,
        )
    }

    fun ensureRootBackend(allowAdbRestart: Boolean = true): Result {
        val adbResult = ensureAdb()
        if (!adbResult.success) return adbResult

        val directRoot = runCatching {
            openAdb().use { adb -> isRoot(adb.shell("id").allOutput) }
        }.getOrDefault(false)
        if (directRoot) return remember(RootBackend.ADB_ROOT)

        val stored = storedRootBackend()
        if (stored != null && validateBackend(stored)) return remember(stored)

        if (validateBackend(RootBackend.SU)) return remember(RootBackend.SU)

        var adbRootFailure = "adb root не проверялся"
        if (allowAdbRestart) {
            val rootRequest = runCatching {
                openAdb().use { adb -> adb.root() }
            }
            adbRootFailure = rootRequest.fold(
                onSuccess = { result ->
                    when (result) {
                        is RootResult.Success -> "adbd перезапущен"
                        is RootResult.Failure -> result.reason
                    }
                },
                onFailure = { it.message ?: it.javaClass.simpleName },
            )
            if (waitForAdb() && validateBackend(RootBackend.ADB_ROOT)) {
                return remember(RootBackend.ADB_ROOT)
            }
        }

        if (validateBackend(RootBackend.CVTE_AT_SUDO)) {
            return remember(RootBackend.CVTE_AT_SUDO)
        }

        preferences.edit().remove(KEY_ROOT_BACKEND).apply()
        return Result(
            false,
            "Root-бэкенд не найден: su и CVTE at_sudo недоступны; " +
                "adb root: $adbRootFailure",
        )
    }

    fun snapshot(): Snapshot {
        val probe = probeLocalAdbShell()
        return Snapshot(
            adbAvailable = probe.success,
            secureSettingsGranted = hasSecureSettingsPermission(),
            rootBackend = storedRootBackend(),
            message = probe.message,
        )
    }

    fun probeLocalAdbShell(): Result =
        runCatching {
            openAdb(
                connectTimeout = ADB_PROBE_CONNECT_TIMEOUT_MILLIS,
                socketTimeout = ADB_PROBE_SOCKET_TIMEOUT_MILLIS,
            ).use { adb ->
                val response = adb.shell("echo $ADB_PROBE_TOKEN")
                check(response.exitCode == 0) {
                    "shell завершился с кодом ${response.exitCode}: " +
                        response.allOutput.trim()
                }
                check(
                    response.allOutput.lineSequence().any { it.trim() == ADB_PROBE_TOKEN },
                ) {
                    "shell не вернул контрольную строку"
                }
            }
            Result(true, "ADB shell доступен")
        }.getOrElse { error ->
            Result(
                false,
                error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName,
            )
        }

    fun shellAsRoot(adb: Dadb, backend: RootBackend, command: String): String {
        check('\'' !in command) { "Root-команда содержит неподдерживаемую одинарную кавычку" }
        val wrapped = when (backend) {
            RootBackend.ADB_ROOT -> command
            RootBackend.SU -> "su -c '$command'"
            RootBackend.CVTE_AT_SUDO -> {
                val dex = prepareCvteDex()
                push(adb, dex, CVTE_REMOTE_DEX, MODE_FILE)
                "CLASSPATH=$CVTE_REMOTE_DEX app_process /system/bin " +
                    "--nice-name=com.cvte.tv.api.impl AtSudoClient '$command'"
            }
        }
        return shellOk(adb, wrapped)
    }

    fun openAdb(
        connectTimeout: Int = 5_000,
        socketTimeout: Int = 30_000,
    ): Dadb =
        Dadb.create(
            host = LOCAL_ADB_HOST,
            port = LOCAL_ADB_PORT,
            keyPair = null,
            connectTimeout = connectTimeout,
            socketTimeout = socketTimeout,
        )

    private fun validateBackend(backend: RootBackend): Boolean =
        runCatching {
            openAdb().use { adb ->
                val output = when (backend) {
                    RootBackend.ADB_ROOT -> shellOk(adb, "id")
                    RootBackend.SU -> shellOk(adb, "su -c 'id'")
                    RootBackend.CVTE_AT_SUDO ->
                        shellAsRoot(adb, RootBackend.CVTE_AT_SUDO, "id")
                }
                isRoot(output)
            }
        }.getOrDefault(false)

    private fun waitForAdb(): Boolean {
        repeat(ADB_RECONNECT_ATTEMPTS) {
            if (probeLocalAdbShell().success) return true
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
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    private fun isRoot(output: String): Boolean =
        ROOT_ID.containsMatchIn(output)

    private fun shellOk(adb: Dadb, command: String): String {
        val response = adb.shell(command)
        check(response.exitCode == 0) {
            "ADB shell (${response.exitCode}): ${response.allOutput.trim()}"
        }
        return response.allOutput
    }

    private fun prepareCvteDex(): File {
        val directory = File(context.filesDir, "privilege").apply { mkdirs() }
        val dex = File(directory, "atsudo.dex")
        context.assets.open("root/atsudo.dex").use { input ->
            FileOutputStream(dex).use { output -> input.copyTo(output) }
        }
        return dex
    }

    private fun push(adb: Dadb, file: File, remotePath: String, mode: Int) {
        val result = file.source().use { source ->
            adb.push(source, remotePath, mode, file.lastModified())
        }
        check(result is SyncResult.Success) {
            val reason = (result as? SyncResult.Failure)?.reason ?: "unknown"
            "Не удалось передать ${file.name}: $reason"
        }
    }

    companion object {
        private const val PREFERENCES = "privilege_manager"
        private const val KEY_ROOT_BACKEND = "root_backend"
        private const val LOCAL_ADB_HOST = "127.0.0.1"
        private const val LOCAL_ADB_PORT = 5555
        private const val ADB_PROBE_TOKEN = "YAOS_ADB_SHELL_OK"
        private const val ADB_PROBE_CONNECT_TIMEOUT_MILLIS = 1_000
        private const val ADB_PROBE_SOCKET_TIMEOUT_MILLIS = 2_000
        private const val ADB_RECONNECT_ATTEMPTS = 20
        private const val ADB_RECONNECT_DELAY_MILLIS = 250L
        private const val CVTE_REMOTE_DEX = "/data/local/tmp/yaos-privilege-atsudo.dex"
        private const val MODE_FILE = 420
        private val ROOT_ID = Regex("""(?:^|\s)uid=0\(root\)""")
    }
}
