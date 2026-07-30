package dev.d4n13l3k00.yaosa11y.core.privilege

import android.content.Context
import dadb.Dadb
import dev.d4n13l3k00.yaosa11y.core.adb.AdbGateway
import dev.d4n13l3k00.yaosa11y.core.adb.ShellPolicy
import java.io.File
import java.io.FileOutputStream

enum class RootBackend(val displayName: String) {
    ADB_ROOT("ADB root"),
    SU("Magisk / su"),
    CVTE_AT_SUDO("CVTE at_sudo"),
}

interface PrivilegeBackend {
    val id: RootBackend
    fun execute(adb: Dadb, command: String): String
}

class AdbRootBackend(private val gateway: AdbGateway) : PrivilegeBackend {
    override val id = RootBackend.ADB_ROOT
    override fun execute(adb: Dadb, command: String): String = gateway.shell(adb, command)
}

class SuPrivilegeBackend(private val gateway: AdbGateway) : PrivilegeBackend {
    override val id = RootBackend.SU
    override fun execute(adb: Dadb, command: String): String =
        gateway.shell(adb, "su -c ${ShellPolicy.quote(command)}")
}

class CvteAtSudoBackend(
    context: Context,
    private val gateway: AdbGateway,
) : PrivilegeBackend {
    override val id = RootBackend.CVTE_AT_SUDO
    private val appContext = context.applicationContext

    override fun execute(adb: Dadb, command: String): String {
        val dex = prepareDex()
        gateway.push(adb, dex, REMOTE_DEX, MODE_FILE)
        val wrapped = "CLASSPATH=$REMOTE_DEX app_process /system/bin " +
            "--nice-name=com.cvte.tv.api.impl AtSudoClient ${ShellPolicy.quote(command)}"
        return gateway.shell(adb, wrapped)
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
        add(RootBackend.SU)
        if (allowAdbRestart) add(RootBackend.ADB_ROOT)
        if (supportsCvte) add(RootBackend.CVTE_AT_SUDO)
    }.distinct()
}
