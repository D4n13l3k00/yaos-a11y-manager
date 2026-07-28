package dev.d4n13l3k00.yaosa11y

import android.Manifest
import android.content.Context
import android.provider.Settings
import dadb.Dadb
import dadb.SyncResult
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import org.tukaani.xz.XZInputStream
import okio.source

class RootHookManager(private val context: Context) {
    enum class State {
        ENABLED,
        DISABLED,
        STARTING,
        UNAVAILABLE,
    }

    data class Result(val success: Boolean, val message: String)

    private val preferences =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun shouldBeEnabled(): Boolean =
        preferences.getBoolean(KEY_ENABLED, false)

    fun hasStoredChoice(): Boolean =
        preferences.contains(KEY_ENABLED)

    fun setDesiredState(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun rememberCurrentAccessibility() {
        val services = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val enabled = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            if (services.isEmpty()) 0 else 1,
        )
        if (runCatching { validateSettingsValue(services) }.isFailure) return
        rememberAccessibility(services, enabled)
    }

    fun runAsync(enabled: Boolean, callback: (Result) -> Unit) {
        EXECUTOR.execute {
            val result = runCatching {
                if (enabled) enable() else disable()
            }.fold(
                onSuccess = { Result(true, it) },
                onFailure = { Result(false, it.message ?: it.javaClass.simpleName) },
            )
            callback(result)
        }
    }

    fun queryStateAsync(callback: (State) -> Unit) {
        EXECUTOR.execute { callback(queryState()) }
    }

    fun enableWithRetries(attempts: Int = 8): Result {
        var lastError = "Локальный ADB пока недоступен"
        repeat(attempts) { attempt ->
            val result = runCatching { enable() }
            if (result.isSuccess) {
                return Result(true, result.getOrThrow())
            }
            lastError = result.exceptionOrNull()?.message ?: lastError
            if (attempt + 1 < attempts) Thread.sleep(3_000)
        }
        return Result(false, lastError)
    }

    fun queryState(): State =
        runCatching {
            openAdb().use { adb ->
                val response = adb.shell(
                    "if [ -f $RUNTIME/disabled ]; then echo disabled; " +
                        "elif [ -f $RUNTIME/injected.pid ] && " +
                        "[ \"\$(cat $RUNTIME/injected.pid 2>/dev/null)\" = " +
                        "\"\$(pidof $TARGET_PACKAGE 2>/dev/null | awk '{print \$1}')\" ]; " +
                        "then echo enabled; elif [ -f $RUNTIME/daemon.pid ]; " +
                        "then echo starting; else echo unavailable; fi",
                )
                when (response.output.trim()) {
                    "enabled" -> State.ENABLED
                    "disabled" -> State.DISABLED
                    "starting" -> State.STARTING
                    else -> State.UNAVAILABLE
                }
            }
        }.getOrDefault(State.UNAVAILABLE)

    private fun enable(): String {
        val adbBootstrap = CvteAdbBootstrap(context).enableAndWait()
        check(adbBootstrap.success) { adbBootstrap.message }

        val currentServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val currentEnabled = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            if (currentServices.isEmpty()) 0 else 1,
        )
        val restoreRemembered =
            shouldBeEnabled() && preferences.contains(KEY_ACCESSIBILITY_SERVICES)
        val enabledServices = if (restoreRemembered) {
            preferences.getString(KEY_ACCESSIBILITY_SERVICES, currentServices).orEmpty()
        } else {
            currentServices
        }
        val accessibilityEnabled = if (restoreRemembered) {
            preferences.getInt(KEY_ACCESSIBILITY_ENABLED, currentEnabled)
        } else {
            currentEnabled
        }
        validateSettingsValue(enabledServices)

        val payload = preparePayload()
        openAdb().use { adb ->
            shellOk(adb, "rm -rf $STAGE && mkdir -p $STAGE")
            push(adb, payload.atSudoDex, "$STAGE/atsudo.dex", MODE_FILE)
            val installedFridaHash = adb.shell(
                "sha256sum $RUNTIME/frida-inject 2>/dev/null | awk '{print \$1}'",
            ).output.trim()
            if (installedFridaHash != FRIDA_SHA256) {
                push(adb, payload.fridaInject, "$STAGE/frida-inject", MODE_EXECUTABLE)
            }
            push(adb, payload.hookScript, "$STAGE/watchdog-hook.js", MODE_FILE)
            push(adb, payload.daemonScript, "$STAGE/watchdog-hook-daemon.sh", MODE_EXECUTABLE)
            push(adb, payload.installScript, "$STAGE/install-hook.sh", MODE_EXECUTABLE)
            push(adb, payload.disableScript, "$STAGE/disable-hook.sh", MODE_EXECUTABLE)

            shellOk(
                adb,
                atSudo(
                    "pm grant ${context.packageName} " +
                        Manifest.permission.WRITE_SECURE_SETTINGS,
                ),
            )
            val installOutput = shellOk(adb, atSudo("sh $STAGE/install-hook.sh"))
            check("INSTALL_OK" in installOutput) {
                "Встроенный root не подтвердил установку: ${installOutput.trim()}"
            }

            waitForInjection(adb)
            check(
                context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED,
            ) { "Root не выдал приложению WRITE_SECURE_SETTINGS" }
            restoreAccessibility(adb, enabledServices, accessibilityEnabled)
            shellOk(adb, "rm -rf $STAGE")
        }

        setDesiredState(true)
        rememberAccessibility(enabledServices, accessibilityEnabled)
        return "Защита YAOS включена"
    }

    private fun disable(): String {
        val payload = preparePayload()
        openAdb().use { adb ->
            shellOk(adb, "mkdir -p $STAGE")
            push(adb, payload.atSudoDex, "$STAGE/atsudo.dex", MODE_FILE)
            push(adb, payload.disableScript, "$STAGE/disable-hook.sh", MODE_EXECUTABLE)
            val output = shellOk(adb, atSudo("sh $STAGE/disable-hook.sh"))
            check("DISABLE_OK" in output) {
                "Встроенный root не подтвердил отключение: ${output.trim()}"
            }
            shellOk(adb, "rm -rf $STAGE")
        }

        setDesiredState(false)
        return "Защита YAOS отключена"
    }

    private fun waitForInjection(adb: Dadb) {
        repeat(30) {
            val response = adb.shell(
                "target=\$(pidof $TARGET_PACKAGE 2>/dev/null | awk '{print \$1}'); " +
                    "injected=\$(cat $RUNTIME/injected.pid 2>/dev/null); " +
                    "[ -n \"\$target\" ] && [ \"\$target\" = \"\$injected\" ]",
            )
            if (response.exitCode == 0) return
            Thread.sleep(500)
        }
        val log = adb.shell("tail -n 12 $RUNTIME/hook.log 2>/dev/null").allOutput.trim()
        error("Хук YAOS не запустился${if (log.isEmpty()) "" else ": $log"}")
    }

    private fun restoreAccessibility(adb: Dadb, services: String, enabled: Int) {
        if (services.isEmpty()) {
            shellOk(adb, "settings delete secure enabled_accessibility_services")
        } else {
            shellOk(adb, "settings put secure enabled_accessibility_services '$services'")
        }
        shellOk(adb, "settings put secure accessibility_enabled ${if (enabled == 0) 0 else 1}")
    }

    private fun validateSettingsValue(value: String) {
        check(value.matches(Regex("[A-Za-z0-9_.$/:]*"))) {
            "Список служб содержит неподдерживаемые символы"
        }
    }

    private fun rememberAccessibility(services: String, enabled: Int) {
        preferences.edit()
            .putString(KEY_ACCESSIBILITY_SERVICES, services)
            .putInt(KEY_ACCESSIBILITY_ENABLED, if (enabled == 0) 0 else 1)
            .apply()
    }

    private fun shellOk(adb: Dadb, command: String): String {
        val response = adb.shell(command)
        check(response.exitCode == 0) {
            "ADB shell завершился с кодом ${response.exitCode}: ${response.allOutput.trim()}"
        }
        return response.allOutput
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

    private fun openAdb(): Dadb =
        Dadb.create(
            host = "127.0.0.1",
            port = 5555,
            keyPair = null,
            connectTimeout = 5_000,
            socketTimeout = 20_000,
        )

    private fun atSudo(command: String): String =
        "CLASSPATH=$STAGE/atsudo.dex app_process /system/bin " +
            "--nice-name=com.cvte.tv.api.impl AtSudoClient $command"

    private fun preparePayload(): Payload {
        val directory = File(context.filesDir, "root-payload").apply { mkdirs() }
        val atSudoDex = extract("root/atsudo.dex", File(directory, "atsudo.dex"))
        val hook = extract("root/watchdog-hook.js", File(directory, "watchdog-hook.js"))
        val daemon = extract(
            "root/watchdog-hook-daemon.sh",
            File(directory, "watchdog-hook-daemon.sh"),
        )
        val install = extract("root/install-hook.sh", File(directory, "install-hook.sh"))
        val disable = extract("root/disable-hook.sh", File(directory, "disable-hook.sh"))
        val frida = File(directory, "frida-inject")
        if (!frida.isFile || sha256(frida) != FRIDA_SHA256) {
            context.assets.open("root/frida-inject.xz").use { compressed ->
                XZInputStream(compressed).use { input ->
                    FileOutputStream(frida).use { output -> input.copyTo(output) }
                }
            }
        }
        check(sha256(frida) == FRIDA_SHA256) { "Контрольная сумма frida-inject не совпала" }
        return Payload(atSudoDex, frida, hook, daemon, install, disable)
    }

    private fun extract(assetPath: String, destination: File): File {
        context.assets.open(assetPath).use { input ->
            FileOutputStream(destination).use { output -> input.copyTo(output) }
        }
        return destination
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class Payload(
        val atSudoDex: File,
        val fridaInject: File,
        val hookScript: File,
        val daemonScript: File,
        val installScript: File,
        val disableScript: File,
    )

    companion object {
        private const val PREFERENCES = "root_hook"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ACCESSIBILITY_SERVICES = "accessibility_services"
        private const val KEY_ACCESSIBILITY_ENABLED = "accessibility_enabled"
        private const val TARGET_PACKAGE = "com.yandex.tv.services.platform"
        private const val STAGE = "/data/local/tmp/yaos-a11y-stage"
        private const val RUNTIME = "/data/local/yaos-a11y/runtime"
        private const val MODE_FILE = 420
        private const val MODE_EXECUTABLE = 493
        private const val FRIDA_SHA256 =
            "e865f8746cee97761af50a31528315baf14cc047eedd35242f30a744b91d25ea"
        private val EXECUTOR = Executors.newSingleThreadExecutor()
    }
}
