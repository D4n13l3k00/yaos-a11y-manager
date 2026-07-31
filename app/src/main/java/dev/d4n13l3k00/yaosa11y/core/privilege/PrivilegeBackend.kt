package dev.d4n13l3k00.yaosa11y.core.privilege

import android.content.Context
import dadb.Dadb
import dev.d4n13l3k00.yaosa11y.core.adb.AdbGateway
import dev.d4n13l3k00.yaosa11y.core.adb.ShellPolicy
import java.io.File
import java.io.FileOutputStream

enum class RootBackend(
    val displayName: String,
    val requiresAdb: Boolean,
) {
    APP_SU("KernelSU / прямой su", false),
    ADB_ROOT("ADB root", true),
    SU("su через ADB shell", true),
    CVTE_AT_SUDO("CVTE at_sudo", true),
}

interface PrivilegeBackend {
    val id: RootBackend
    fun execute(adb: Dadb?, command: String): String
}

class AdbRootBackend(private val gateway: AdbGateway) : PrivilegeBackend {
    override val id = RootBackend.ADB_ROOT
    override fun execute(adb: Dadb?, command: String): String =
        gateway.shell(requireNotNull(adb), command)
}

class AppSuPrivilegeBackend : PrivilegeBackend {
    override val id = RootBackend.APP_SU

    override fun execute(adb: Dadb?, command: String): String {
        val process = ProcessBuilder(DirectSuCommand.arguments(command))
            .redirectErrorStream(true)
            .start()
        val completed = waitFor(process, COMMAND_TIMEOUT_MILLIS)
        if (!completed) {
            process.destroy()
            error("Прямой su не ответил за ${COMMAND_TIMEOUT_MILLIS / 1_000} секунд")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.exitValue() == 0) {
            "Прямой su (${process.exitValue()}): ${output.trim()}"
        }
        return output
    }

    private fun waitFor(process: Process, timeoutMillis: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            try {
                process.exitValue()
                return true
            } catch (_: IllegalThreadStateException) {
                Thread.sleep(PROCESS_POLL_MILLIS)
            }
        }
        return false
    }

    companion object {
        private const val COMMAND_TIMEOUT_MILLIS = 180_000L
        private const val PROCESS_POLL_MILLIS = 100L
    }
}

class SuPrivilegeBackend(private val gateway: AdbGateway) : PrivilegeBackend {
    override val id = RootBackend.SU
    override fun execute(adb: Dadb?, command: String): String =
        gateway.shell(requireNotNull(adb), "su -c ${ShellPolicy.quote(command)}")
}

class CvteAtSudoBackend(
    context: Context,
    private val gateway: AdbGateway,
) : PrivilegeBackend {
    override val id = RootBackend.CVTE_AT_SUDO
    private val appContext = context.applicationContext

    override fun execute(adb: Dadb?, command: String): String {
        val connection = requireNotNull(adb)
        val dex = prepareDex()
        gateway.push(connection, dex, REMOTE_DEX, MODE_FILE)
        val wrapped = "CLASSPATH=$REMOTE_DEX app_process /system/bin " +
            "--nice-name=com.cvte.tv.api.impl AtSudoClient ${ShellPolicy.quote(command)}"
        return gateway.shell(connection, wrapped)
    }

    private fun prepareDex(): File {
        val directory = File(appContext.filesDir, "privilege").apply { mkdirs() }
        return File(directory, "atsudo.dex").also { destination ->
            appContext.assets.open("root/atsudo.dex").use { input ->
                FileOutputStream(destination).use(input::copyTo)
            }
        }
    }

    companion object {
        private const val REMOTE_DEX = "/data/local/tmp/yaos-privilege-atsudo.dex"
        private const val MODE_FILE = 420
    }
}

object RootBackendOrder {
    fun candidates(
        stored: RootBackend?,
        allowAdbRestart: Boolean,
        supportsCvte: Boolean,
    ): List<RootBackend> = buildList {
        stored?.let(::add)
        add(RootBackend.APP_SU)
        add(RootBackend.SU)
        if (allowAdbRestart) add(RootBackend.ADB_ROOT)
        if (supportsCvte) add(RootBackend.CVTE_AT_SUDO)
    }.distinct()
}

object DirectSuCommand {
    fun arguments(command: String): List<String> {
        require(command.isNotBlank()) { "Root-команда не может быть пустой" }
        return listOf("su", "-c", command)
    }
}
