package dev.d4n13l3k00.yaosa11y

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import dadb.Dadb
import dadb.SyncResult
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import okio.source
import org.tukaani.xz.XZInputStream

class RootHookManager(private val context: Context) {
    enum class State {
        ENABLED,
        GUARD_ONLY,
        DISABLED,
        STARTING,
        UNAVAILABLE,
    }

    data class Result(val success: Boolean, val message: String)

    data class ReconcileResult(
        val success: Boolean,
        val changed: Boolean,
        val message: String,
    )

    private val preferences =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val protectionStore = AccessibilityProtectionStore(context)
    private val privilegeManager = PrivilegeManager(context)

    fun shouldBeEnabled(): Boolean =
        preferences.getBoolean(KEY_ENABLED, false)

    fun hasStoredChoice(): Boolean =
        preferences.contains(KEY_ENABLED)

    fun setDesiredState(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun protectionSnapshot(): AccessibilityProtectionSnapshot =
        protectionStore.snapshot()

    fun recordUserState(component: ComponentName, enabled: Boolean) {
        ensureProtectionStoreInitialized(readEnabledComponents(), protectObserved = false)
        protectionStore.recordUserState(component, enabled)
    }

    fun setServiceProtected(
        component: ComponentName,
        protected: Boolean,
        observedEnabled: Boolean,
    ) {
        ensureProtectionStoreInitialized(readEnabledComponents(), protectObserved = false)
        protectionStore.setProtected(component, protected, observedEnabled)
        if (protected && shouldBeEnabled()) ensureGuardServiceRunning()
    }

    fun runAsync(enabled: Boolean, callback: (Result) -> Unit) {
        EXECUTOR.execute {
            val result = runCatching {
                synchronized(OPERATION_LOCK) {
                    if (enabled) enable() else disable()
                }
            }.fold(
                onSuccess = { Result(true, it) },
                onFailure = { Result(false, it.message ?: it.javaClass.simpleName) },
            )
            callback(result)
        }
    }

    fun reconcileAsync(callback: (ReconcileResult) -> Unit = {}) {
        EXECUTOR.execute { callback(reconcileProtectedServices()) }
    }

    fun queryStateAsync(callback: (State) -> Unit) {
        EXECUTOR.execute { callback(queryState()) }
    }

    fun ensureProtectionServiceRunning() {
        if (shouldBeEnabled()) ensureGuardServiceRunning()
    }

    fun enableWithRetries(attempts: Int = 8): Result {
        var lastError = "Локальный ADB пока недоступен"
        repeat(attempts) { attempt ->
            val result = runCatching {
                synchronized(OPERATION_LOCK) { enable() }
            }
            if (result.isSuccess) {
                return Result(true, result.getOrThrow())
            }
            lastError = result.exceptionOrNull()?.message ?: lastError
            if (attempt + 1 < attempts) Thread.sleep(3_000)
        }
        return Result(false, lastError)
    }

    fun queryState(): State {
        if (!shouldBeEnabled()) return State.DISABLED
        if (
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return State.UNAVAILABLE
        }
        return runCatching {
            privilegeManager.openAdb().use { adb ->
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
                    "disabled" -> State.GUARD_ONLY
                    "starting" -> State.STARTING
                    else -> State.GUARD_ONLY
                }
            }
        }.getOrDefault(State.GUARD_ONLY)
    }

    fun reconcileProtectedServices(): ReconcileResult =
        synchronized(OPERATION_LOCK) { reconcileProtectedServicesLocked() }

    private fun reconcileProtectedServicesLocked(): ReconcileResult {
        if (!shouldBeEnabled()) {
            return ReconcileResult(true, false, "Защита отключена пользователем")
        }
        if (
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            val message = "Нет разрешения WRITE_SECURE_SETTINGS"
            protectionStore.recordFailure(message)
            return ReconcileResult(false, false, message)
        }

        val observed = readEnabledComponents()
        ensureProtectionStoreInitialized(observed, protectObserved = true)
        val snapshot = protectionStore.snapshot()
        if (snapshot.protectedComponents.isEmpty()) {
            val message = "Нет закреплённых служб"
            protectionStore.recordReconcile(observed, message)
            return ReconcileResult(true, false, message)
        }

        val target = protectionStore.reconcileTarget(observed)
        val globalEnabled = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            if (observed.isEmpty()) 0 else 1,
        )
        val expectedGlobal = if (target.isEmpty()) 0 else 1
        if (target == observed && globalEnabled == expectedGlobal) {
            val message = "Закреплённые службы в норме"
            protectionStore.recordReconcile(target, message)
            return ReconcileResult(true, false, message)
        }

        val previousRaw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        val written = writeAccessibilitySettings(target)
        val verified = readEnabledComponents() == target &&
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                expectedGlobal,
            ) == expectedGlobal
        if (!written || !verified) {
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                previousRaw,
            )
            Settings.Secure.putInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                globalEnabled,
            )
            val message = "Не удалось подтвердить восстановление служб; выполнен откат"
            protectionStore.recordFailure(message)
            return ReconcileResult(false, false, message)
        }

        val restored = target
            .filter { it !in observed }
            .joinToString { it.flattenToShortString() }
        val suppressed = observed
            .filter { it !in target }
            .joinToString { it.flattenToShortString() }
        val details = buildList {
            if (restored.isNotEmpty()) add("восстановлено: $restored")
            if (suppressed.isNotEmpty()) add("возвращено в выключенное состояние: $suppressed")
        }.joinToString("; ")
        val message = if (details.isEmpty()) {
            "Состояние закреплённых служб восстановлено"
        } else {
            "Обнаружено внешнее изменение — $details"
        }
        protectionStore.recordReconcile(target, message)
        return ReconcileResult(true, true, message)
    }

    private fun enable(): String {
        val observed = readEnabledComponents()
        ensureProtectionStoreInitialized(observed, protectObserved = true)
        val target = protectionStore.reconcileTarget(observed)
        val enabledServices = flattenComponents(target)
        val accessibilityEnabled = if (target.isEmpty()) 0 else 1
        validateSettingsValue(enabledServices)

        val permission = privilegeManager.ensureSecureSettings()
        check(permission.success) { permission.message }
        setDesiredState(true)
        ensureGuardServiceRunning()

        val root = privilegeManager.ensureRootBackend()
        if (!root.success || root.rootBackend == null) {
            val reconciled = reconcileProtectedServicesLocked()
            check(reconciled.success) { reconciled.message }
            val message =
                "Guard-защита включена без native-hook: ${root.message}"
            protectionStore.recordReconcile(target, message)
            return message
        }

        val payload = preparePayload()
        privilegeManager.openAdb().use { adb ->
            shellOk(adb, "rm -rf $STAGE && mkdir -p $STAGE")
            val installedFridaHash = privilegeManager.shellAsRoot(
                adb,
                root.rootBackend,
                "sha256sum $RUNTIME/frida-inject 2>/dev/null | awk \"{print \\\$1}\"",
            ).trim()
            if (installedFridaHash != FRIDA_SHA256) {
                push(adb, payload.fridaInject, "$STAGE/frida-inject", MODE_EXECUTABLE)
            }
            push(adb, payload.hookScript, "$STAGE/watchdog-hook.js", MODE_FILE)
            push(adb, payload.daemonScript, "$STAGE/watchdog-hook-daemon.sh", MODE_EXECUTABLE)
            push(adb, payload.installScript, "$STAGE/install-hook.sh", MODE_EXECUTABLE)
            push(adb, payload.disableScript, "$STAGE/disable-hook.sh", MODE_EXECUTABLE)

            val installOutput = privilegeManager.shellAsRoot(
                adb,
                root.rootBackend,
                "sh $STAGE/install-hook.sh",
            )
            check("INSTALL_OK" in installOutput) {
                "${root.rootBackend.displayName} не подтвердил установку: " +
                    installOutput.trim()
            }

            waitForInjection(adb)
            check(
                context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                    PackageManager.PERMISSION_GRANTED,
            ) { "WRITE_SECURE_SETTINGS было потеряно после установки hook" }
            restoreAccessibility(adb, enabledServices, accessibilityEnabled)
            shellOk(adb, "rm -rf $STAGE")
        }

        protectionStore.recordReconcile(
            target,
            "Guard и native-hook запущены через ${root.rootBackend.displayName}",
        )
        return "Защита YAOS включена через ${root.rootBackend.displayName}"
    }

    private fun disable(): String {
        val nativeState = queryState()
        var nativeMessage = ""
        if (nativeState == State.ENABLED || nativeState == State.STARTING) {
            val root = privilegeManager.ensureRootBackend()
            check(root.success && root.rootBackend != null) {
                "Guard можно отключить, но для остановки активного native-hook нужен root: " +
                    root.message
            }
            val payload = preparePayload()
            privilegeManager.openAdb().use { adb ->
                shellOk(adb, "mkdir -p $STAGE")
                push(adb, payload.disableScript, "$STAGE/disable-hook.sh", MODE_EXECUTABLE)
                val output = privilegeManager.shellAsRoot(
                    adb,
                    root.rootBackend,
                    "sh $STAGE/disable-hook.sh",
                )
                check("DISABLE_OK" in output) {
                    "${root.rootBackend.displayName} не подтвердил отключение: " +
                        output.trim()
                }
                shellOk(adb, "rm -rf $STAGE")
            }
            nativeMessage = " и native-hook"
        }

        setDesiredState(false)
        context.stopService(Intent(context, HookBootstrapService::class.java))
        return "Guard$nativeMessage отключён"
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

    private fun ensureProtectionStoreInitialized(
        observed: Collection<ComponentName>,
        protectObserved: Boolean,
    ) {
        if (protectionStore.isInitialized()) return
        val legacy = preferences.getString(KEY_ACCESSIBILITY_SERVICES, null)
            ?.let(::parseComponents)
            .orEmpty()
        if (legacy.isNotEmpty()) {
            protectionStore.initializeFromLegacy(legacy)
        } else {
            protectionStore.initialize(observed, protectObserved)
        }
    }

    private fun readEnabledComponents(): LinkedHashSet<ComponentName> {
        val raw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return parseComponents(raw)
    }

    private fun parseComponents(raw: String): LinkedHashSet<ComponentName> =
        raw.split(':')
            .mapNotNull(ComponentName::unflattenFromString)
            .toCollection(LinkedHashSet())

    private fun flattenComponents(components: Collection<ComponentName>): String =
        components.joinToString(":") { it.flattenToString() }

    private fun writeAccessibilitySettings(components: Collection<ComponentName>): Boolean {
        val servicesWritten = Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            flattenComponents(components),
        )
        val globalWritten = Settings.Secure.putInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            if (components.isEmpty()) 0 else 1,
        )
        return servicesWritten && globalWritten
    }

    private fun ensureGuardServiceRunning() {
        val intent = Intent(context, HookBootstrapService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
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

    private fun preparePayload(): Payload {
        val directory = File(context.filesDir, "root-payload").apply { mkdirs() }
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
        return Payload(frida, hook, daemon, install, disable)
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
        private const val TARGET_PACKAGE = "com.yandex.tv.services.platform"
        private const val STAGE = "/data/local/tmp/yaos-a11y-stage"
        private const val RUNTIME = "/data/local/yaos-a11y/runtime"
        private const val MODE_FILE = 420
        private const val MODE_EXECUTABLE = 493
        private const val FRIDA_SHA256 =
            "e865f8746cee97761af50a31528315baf14cc047eedd35242f30a744b91d25ea"
        private val EXECUTOR = Executors.newSingleThreadExecutor()
        private val OPERATION_LOCK = Any()
    }
}
