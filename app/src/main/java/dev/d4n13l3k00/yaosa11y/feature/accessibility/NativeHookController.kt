package dev.d4n13l3k00.yaosa11y.feature.accessibility

import android.content.Context
import dadb.Dadb
import dev.d4n13l3k00.yaosa11y.core.adb.AdbGateway
import dev.d4n13l3k00.yaosa11y.core.platform.PlatformProfile
import dev.d4n13l3k00.yaosa11y.core.platform.PlatformProfileResolver
import dev.d4n13l3k00.yaosa11y.core.privilege.PrivilegeManager
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import org.tukaani.xz.XZInputStream

class NativeHookController(
    context: Context,
    private val privilegeManager: PrivilegeManager,
    private val gateway: AdbGateway,
    private val profile: PlatformProfile = PlatformProfileResolver(context).resolve(),
) {
    enum class State {
        ENABLED,
        DISABLED,
        STARTING,
        UNAVAILABLE,
    }

    data class InstallResult(val installed: Boolean, val message: String)

    private val appContext = context.applicationContext

    fun queryState(): State {
        val target = profile.nativeHookTargetPackage ?: return State.UNAVAILABLE
        val runtime = profile.nativeHookRuntimePath
        return runCatching {
            gateway.withConnection { adb ->
                val output = gateway.shell(
                    adb,
                    "if [ -f $runtime/disabled ]; then echo disabled; " +
                        "elif [ -f $runtime/injected.pid ] && " +
                        "[ \"\$(cat $runtime/injected.pid 2>/dev/null)\" = " +
                        "\"\$(pidof $target 2>/dev/null | awk '{print \$1}')\" ]; " +
                        "then echo enabled; elif [ -f $runtime/daemon.pid ]; " +
                        "then echo starting; else echo unavailable; fi",
                ).trim()
                when (output) {
                    "enabled" -> State.ENABLED
                    "disabled" -> State.DISABLED
                    "starting" -> State.STARTING
                    else -> State.UNAVAILABLE
                }
            }
        }.getOrDefault(State.UNAVAILABLE)
    }

    fun install(): InstallResult {
        val target = profile.nativeHookTargetPackage
            ?: return InstallResult(
                false,
                "Native-hook не применим к платформе ${profile.displayName}",
            )
        val root = privilegeManager.ensureRootBackend()
        if (!root.success || root.rootBackend == null) {
            return InstallResult(false, root.message)
        }

        val payload = preparePayload()
        val stage = profile.nativeHookStagePath
        val runtime = profile.nativeHookRuntimePath
        gateway.withConnection { adb ->
            gateway.shell(adb, "rm -rf $stage && mkdir -p $stage")
            val installedFridaHash = privilegeManager.shellAsRoot(
                adb,
                root.rootBackend,
                "sha256sum $runtime/frida-inject 2>/dev/null | awk \"{print \\\$1}\"",
            ).trim()
            if (installedFridaHash != FRIDA_SHA256) {
                gateway.push(adb, payload.fridaInject, "$stage/frida-inject", MODE_EXECUTABLE)
            }
            gateway.push(adb, payload.hookScript, "$stage/watchdog-hook.js", MODE_FILE)
            gateway.push(
                adb,
                payload.daemonScript,
                "$stage/watchdog-hook-daemon.sh",
                MODE_EXECUTABLE,
            )
            gateway.push(adb, payload.installScript, "$stage/install-hook.sh", MODE_EXECUTABLE)
            gateway.push(adb, payload.disableScript, "$stage/disable-hook.sh", MODE_EXECUTABLE)

            val output = privilegeManager.shellAsRoot(
                adb,
                root.rootBackend,
                "sh $stage/install-hook.sh",
            )
            check("INSTALL_OK" in output) {
                "${root.rootBackend.displayName} не подтвердил установку: ${output.trim()}"
            }
            waitForInjection(adb, target, runtime)
            gateway.shell(adb, "rm -rf $stage")
        }
        return InstallResult(
            true,
            "Native-hook запущен через ${root.rootBackend.displayName}",
        )
    }

    fun disable(): String {
        val state = queryState()
        if (state !in setOf(State.ENABLED, State.STARTING)) {
            return "Native-hook уже не активен"
        }
        val root = privilegeManager.ensureRootBackend()
        check(root.success && root.rootBackend != null) {
            "Для остановки активного native-hook нужен root: ${root.message}"
        }
        val payload = preparePayload()
        val stage = profile.nativeHookStagePath
        gateway.withConnection { adb ->
            gateway.shell(adb, "mkdir -p $stage")
            gateway.push(adb, payload.disableScript, "$stage/disable-hook.sh", MODE_EXECUTABLE)
            val output = privilegeManager.shellAsRoot(
                adb,
                root.rootBackend,
                "sh $stage/disable-hook.sh",
            )
            check("DISABLE_OK" in output) {
                "${root.rootBackend.displayName} не подтвердил отключение: ${output.trim()}"
            }
            gateway.shell(adb, "rm -rf $stage")
        }
        return "Native-hook отключён"
    }

    private fun waitForInjection(adb: Dadb, target: String, runtime: String) {
        repeat(30) {
            val response = adb.shell(
                "target=\$(pidof $target 2>/dev/null | awk '{print \$1}'); " +
                    "injected=\$(cat $runtime/injected.pid 2>/dev/null); " +
                    "[ -n \"\$target\" ] && [ \"\$target\" = \"\$injected\" ]",
            )
            if (response.exitCode == 0) return
            Thread.sleep(500)
        }
        val log = adb.shell("tail -n 12 $runtime/hook.log 2>/dev/null").allOutput.trim()
        error("Хук YAOS не запустился${if (log.isEmpty()) "" else ": $log"}")
    }

    private fun preparePayload(): Payload {
        val directory = File(appContext.filesDir, "root-payload").apply { mkdirs() }
        val hook = extract("root/watchdog-hook.js", File(directory, "watchdog-hook.js"))
        val daemon = extract(
            "root/watchdog-hook-daemon.sh",
            File(directory, "watchdog-hook-daemon.sh"),
        )
        val install = extract("root/install-hook.sh", File(directory, "install-hook.sh"))
        val disable = extract("root/disable-hook.sh", File(directory, "disable-hook.sh"))
        val frida = File(directory, "frida-inject")
        if (!frida.isFile || sha256(frida) != FRIDA_SHA256) {
            appContext.assets.open("root/frida-inject.xz").use { compressed ->
                XZInputStream(compressed).use { input ->
                    FileOutputStream(frida).use(input::copyTo)
                }
            }
        }
        check(sha256(frida) == FRIDA_SHA256) {
            "Контрольная сумма frida-inject не совпала"
        }
        return Payload(frida, hook, daemon, install, disable)
    }

    private fun extract(assetPath: String, destination: File): File {
        appContext.assets.open(assetPath).use { input ->
            FileOutputStream(destination).use(input::copyTo)
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
        private const val MODE_FILE = 420
        private const val MODE_EXECUTABLE = 493
        private const val FRIDA_SHA256 =
            "e865f8746cee97761af50a31528315baf14cc047eedd35242f30a744b91d25ea"
    }
}
