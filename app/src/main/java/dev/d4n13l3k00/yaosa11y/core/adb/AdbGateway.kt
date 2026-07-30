package dev.d4n13l3k00.yaosa11y.core.adb

import dadb.Dadb
import dadb.SyncResult
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import okio.source

/**
 * The single entry point for the in-process ADB client.
 *
 * DADB uses one local adbd endpoint. Serializing sessions prevents the guard,
 * package manager and web installer from racing each other on firmwares with a
 * fragile single-client adbd implementation.
 */
class AdbGateway {
    fun <T> exclusive(block: () -> T): T = CONNECTION_LOCK.withLock(block)

    fun <T> withConnection(
        connectTimeout: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
        socketTimeout: Int = DEFAULT_SOCKET_TIMEOUT_MILLIS,
        block: (Dadb) -> T,
    ): T = exclusive {
        Dadb.create(
            host = LOCAL_HOST,
            port = LOCAL_PORT,
            keyPair = null,
            connectTimeout = connectTimeout,
            socketTimeout = socketTimeout,
        ).use(block)
    }

    fun shell(adb: Dadb, command: String): String {
        val response = adb.shell(command)
        check(response.exitCode == 0) {
            "ADB shell (${response.exitCode}): ${response.allOutput.trim()}"
        }
        return response.allOutput
    }

    fun push(adb: Dadb, file: File, remotePath: String, mode: Int) {
        val result = file.source().use { source ->
            adb.push(source, remotePath, mode, file.lastModified())
        }
        check(result is SyncResult.Success) {
            val reason = (result as? SyncResult.Failure)?.reason ?: "unknown"
            "Не удалось передать ${file.name}: $reason"
        }
    }

    fun probe(): Probe = runCatching {
        withConnection(
            connectTimeout = PROBE_CONNECT_TIMEOUT_MILLIS,
            socketTimeout = PROBE_SOCKET_TIMEOUT_MILLIS,
        ) { adb ->
            val output = shell(adb, "echo $PROBE_TOKEN")
            check(output.lineSequence().any { it.trim() == PROBE_TOKEN }) {
                "shell не вернул контрольную строку"
            }
        }
        Probe(true, "ADB shell доступен")
    }.getOrElse { error ->
        Probe(
            false,
            error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName,
        )
    }

    data class Probe(val available: Boolean, val message: String)

    companion object {
        const val LOCAL_HOST = "127.0.0.1"
        const val LOCAL_PORT = 5555
        private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 5_000
        private const val DEFAULT_SOCKET_TIMEOUT_MILLIS = 30_000
        private const val PROBE_CONNECT_TIMEOUT_MILLIS = 1_000
        private const val PROBE_SOCKET_TIMEOUT_MILLIS = 2_000
        private const val PROBE_TOKEN = "YAOS_ADB_SHELL_OK"
        private val CONNECTION_LOCK = ReentrantLock(true)
    }
}
