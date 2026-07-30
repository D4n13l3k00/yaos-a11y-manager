package dev.d4n13l3k00.yaosa11y.feature.apps

import android.content.Context
import dev.d4n13l3k00.yaosa11y.core.adb.AdbGateway
import dev.d4n13l3k00.yaosa11y.core.privilege.PrivilegeManager
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lifecycle-aware facade used by the activities and the local web server.
 */
class AppManagerController(context: Context) : Closeable {
    data class Result(val success: Boolean, val message: String)

    enum class Operation {
        FREEZE,
        UNFREEZE,
        UNINSTALL_FOR_USER,
        RESTORE_FOR_USER,
        UNINSTALL_COMPLETELY,
        CLEAR_CACHE,
        CLEAR_DATA,
        FORCE_STOP,
        TRIM_ALL_CACHES,
    }

    private val appContext = context.applicationContext
    private val gateway = AdbGateway()
    private val privilegeManager = PrivilegeManager(appContext, gateway)
    private val packageRepository = PackageRepository(appContext)
    private val packageOperator = PackageOperator(appContext, privilegeManager, gateway)
    private val apkDownloader = ApkDownloader(appContext)
    private val apkInstaller = ApkInstaller(appContext, gateway)
    private val executor = Executors.newSingleThreadExecutor()
    private val closed = AtomicBoolean(false)

    fun loadApps(): List<ManagedApp> = packageRepository.loadApps()

    fun runAsync(
        operation: Operation,
        app: ManagedApp? = null,
        callback: (Result) -> Unit,
    ) = submit(callback) { packageOperator.run(operation, app) }

    fun setPackagesEnabledAsync(
        packageNames: List<String>,
        enabled: Boolean,
        callback: (Result) -> Unit,
    ) = submit(callback) {
        packageOperator.setPackagesEnabled(packageNames, enabled)
    }

    fun downloadAndInstallAsync(
        url: String,
        status: (String) -> Unit = {},
        callback: (Result) -> Unit,
    ) = submit(callback) {
        if (!closed.get()) status("Загрузка APK…")
        val apk = apkDownloader.download(url)
        if (!closed.get()) status("Установка ${apk.name}…")
        apkInstaller.install(apk)
    }

    fun installApkBlocking(file: File): Result =
        asResult { apkInstaller.install(file) }

    fun installUrlBlocking(url: String): Result =
        asResult { apkInstaller.install(apkDownloader.download(url)) }

    private fun submit(callback: (Result) -> Unit, work: () -> String) {
        if (closed.get()) return
        executor.execute {
            val result = asResult(work)
            if (!closed.get()) callback(result)
        }
    }

    private fun asResult(work: () -> String): Result =
        runCatching(work).fold(
            onSuccess = { Result(true, it) },
            onFailure = { Result(false, it.message ?: it.javaClass.simpleName) },
        )

    override fun close() {
        if (closed.compareAndSet(false, true)) executor.shutdownNow()
    }
}
