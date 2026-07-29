package dev.d4n13l3k00.yaosa11y

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import dadb.Dadb
import dadb.SyncResult
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors
import okio.source

data class ManagedApp(
    val applicationInfo: ApplicationInfo,
    val label: String,
    val packageName: String,
    val versionName: String,
    val system: Boolean,
    val installedForUser: Boolean,
    val enabled: Boolean,
)

class AppManagerController(private val context: Context) {
    data class Result(val success: Boolean, val message: String)
    private val privilegeManager = PrivilegeManager(context)

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

    fun loadApps(): List<ManagedApp> {
        val manager = context.packageManager
        @Suppress("DEPRECATION")
        val applications = manager.getInstalledApplications(
            PackageManager.GET_DISABLED_COMPONENTS or
                PackageManager.GET_UNINSTALLED_PACKAGES,
        )
        return applications.mapNotNull { info ->
            runCatching {
                @Suppress("DEPRECATION")
                val packageInfo = manager.getPackageInfo(
                    info.packageName,
                    PackageManager.GET_UNINSTALLED_PACKAGES,
                )
                val installed = info.flags and ApplicationInfo.FLAG_INSTALLED != 0
                ManagedApp(
                    applicationInfo = info,
                    label = manager.getApplicationLabel(info).toString(),
                    packageName = info.packageName,
                    versionName = packageInfo.versionName.orEmpty(),
                    system = info.flags and
                        (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                    installedForUser = installed,
                    enabled = installed && info.enabled,
                )
            }.getOrNull()
        }.sortedWith(
            compareByDescending<ManagedApp> { it.installedForUser }
                .thenByDescending { !it.system }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label },
        )
    }

    fun runAsync(
        operation: Operation,
        app: ManagedApp? = null,
        callback: (Result) -> Unit,
    ) {
        EXECUTOR.execute {
            val result = runCatching { runOperation(operation, app) }.fold(
                onSuccess = { Result(true, it) },
                onFailure = { Result(false, it.message ?: it.javaClass.simpleName) },
            )
            callback(result)
        }
    }

    fun setPackagesEnabledAsync(
        packageNames: List<String>,
        enabled: Boolean,
        callback: (Result) -> Unit,
    ) {
        EXECUTOR.execute {
            val result = runCatching {
                setPackagesEnabled(packageNames, enabled)
            }.fold(
                onSuccess = { Result(true, it) },
                onFailure = { Result(false, it.message ?: it.javaClass.simpleName) },
            )
            callback(result)
        }
    }

    fun downloadAndInstallAsync(
        url: String,
        status: (String) -> Unit = {},
        callback: (Result) -> Unit,
    ) {
        EXECUTOR.execute {
            val result = runCatching {
                status("Загрузка APK…")
                val apk = downloadApk(url)
                status("Установка ${apk.name}…")
                installApk(apk)
            }.fold(
                onSuccess = { Result(true, it) },
                onFailure = { Result(false, it.message ?: it.javaClass.simpleName) },
            )
            callback(result)
        }
    }

    fun installApkBlocking(file: File): Result =
        runCatching { installApk(file) }.fold(
            onSuccess = { Result(true, it) },
            onFailure = { Result(false, it.message ?: it.javaClass.simpleName) },
        )

    fun installUrlBlocking(url: String): Result =
        runCatching { installApk(downloadApk(url)) }.fold(
            onSuccess = { Result(true, it) },
            onFailure = { Result(false, it.message ?: it.javaClass.simpleName) },
        )

    private fun runOperation(operation: Operation, app: ManagedApp?): String {
        if (operation == Operation.TRIM_ALL_CACHES) {
            openAdb().use { adb ->
                val output = shellOk(adb, "pm trim-caches 999G")
                return output.ifBlank { "Кэш всех приложений очищен" }.trim()
            }
        }

        val target = requireNotNull(app)
        val packageName = validatePackageName(target.packageName)
        check(packageName != context.packageName || operation !in DESTRUCTIVE_SELF_OPERATIONS) {
            "YAOS Manager не может удалить или заморозить сам себя"
        }
        if (operation == Operation.CLEAR_CACHE) {
            clearCache(packageName)
            return operation.successMessage(target.label)
        }

        openAdb().use { adb ->
            val output = when (operation) {
                Operation.FREEZE ->
                    shellOk(adb, "pm disable-user --user 0 $packageName")
                Operation.UNFREEZE ->
                    shellOk(adb, "pm enable --user 0 $packageName")
                Operation.UNINSTALL_FOR_USER ->
                    shellOk(adb, "pm uninstall --user 0 $packageName")
                Operation.RESTORE_FOR_USER ->
                    shellOk(adb, "cmd package install-existing --user 0 $packageName")
                Operation.UNINSTALL_COMPLETELY ->
                    shellOk(adb, "pm uninstall $packageName")
                Operation.CLEAR_DATA ->
                    shellOk(adb, "pm clear --user 0 $packageName")
                Operation.FORCE_STOP ->
                    shellOk(adb, "am force-stop --user 0 $packageName")
                Operation.CLEAR_CACHE -> error("Недостижимая операция")
                Operation.TRIM_ALL_CACHES ->
                    error("Недостижимая операция")
            }
            checkCommandResult(operation, output)
            return operation.successMessage(target.label)
        }
    }

    private fun setPackagesEnabled(packageNames: List<String>, enabled: Boolean): String {
        val targets = packageNames.distinct().map(::validatePackageName)
        check(targets.isNotEmpty()) { "Не выбрано ни одного пакета" }
        check(targets.none { it in BLOCKED_PRESET_PACKAGES }) {
            "Защищённый системный пакет нельзя изменить через пресет"
        }

        return openAdb().use { adb ->
            val action = if (enabled) "включено" else "отключено"
            val lines = ArrayList<String>()
            var successful = 0
            targets.forEach { packageName ->
                val command = if (enabled) {
                    "pm enable --user 0 $packageName"
                } else {
                    "pm disable-user --user 0 $packageName"
                }
                val attempt = runCatching {
                    val output = shellOk(adb, command)
                    checkCommandResult(
                        if (enabled) Operation.UNFREEZE else Operation.FREEZE,
                        output,
                    )
                }
                if (attempt.isSuccess) {
                    successful += 1
                    lines += "OK  $packageName"
                } else {
                    lines += "ERR $packageName: " +
                        (attempt.exceptionOrNull()?.message ?: "неизвестная ошибка")
                }
            }

            val failed = targets.size - successful
            val report = buildString {
                append("Пакетов $action: $successful из ${targets.size}")
                if (failed > 0) append(" • ошибок: $failed")
                appendLine()
                append(lines.joinToString("\n"))
            }
            check(failed == 0) { report }
            report
        }
    }

    private fun clearCache(packageName: String): String {
        val root = privilegeManager.ensureRootBackend()
        check(root.success && root.rootBackend != null) {
            "Для точечной очистки чужого кэша нужен root: ${root.message}"
        }
        val paths = listOf(
            "/data/user/0/$packageName/cache",
            "/data/user/0/$packageName/code_cache",
            "/data/data/$packageName/cache",
            "/data/data/$packageName/code_cache",
        ).joinToString(" ")
        val command =
            "for d in $paths; do " +
                "if [ -d \"\$d\" ]; then find \"\$d\" -mindepth 1 -maxdepth 1 -exec rm -rf {} +; fi; " +
                "done; echo CACHE_OK"
        return privilegeManager.openAdb().use { adb ->
            val output = privilegeManager.shellAsRoot(adb, root.rootBackend, command)
            check("CACHE_OK" in output) {
                "${root.rootBackend.displayName} не подтвердил очистку кэша: ${output.trim()}"
            }
            output
        }
    }

    private fun checkCommandResult(operation: Operation, output: String) {
        val normalized = output.trim()
        check(
            normalized.isBlank() ||
                "Success" in normalized ||
                "success" in normalized ||
                "new state" in normalized ||
                "installed for user" in normalized ||
                "Package " in normalized,
        ) {
            "${operation.displayName}: $normalized"
        }
    }

    private fun downloadApk(rawUrl: String): File {
        var current = URI(rawUrl.trim()).toURL()
        check(current.protocol == "http" || current.protocol == "https") {
            "Поддерживаются только ссылки http:// и https://"
        }
        repeat(MAX_REDIRECTS + 1) { redirect ->
            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", "YAOS-A11Y-Manager/1.1.0")
            connection.connect()
            if (connection.responseCode in 300..399) {
                check(redirect < MAX_REDIRECTS) { "Слишком много перенаправлений" }
                val location = connection.getHeaderField("Location")
                    ?: error("Перенаправление без Location")
                current = URL(current, location)
                check(current.protocol == "http" || current.protocol == "https") {
                    "Недопустимое перенаправление"
                }
                connection.disconnect()
                return@repeat
            }
            check(connection.responseCode in 200..299) {
                "Сервер вернул HTTP ${connection.responseCode}"
            }
            val length = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            check(length <= MAX_APK_BYTES) { "APK больше допустимого размера" }
            val name = current.path.substringAfterLast('/').substringBefore('?')
                .takeIf { it.endsWith(".apk", ignoreCase = true) }
                ?: "download-${System.currentTimeMillis()}.apk"
            val destination = File(context.cacheDir, safeFileName(name))
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= MAX_APK_BYTES) { "APK больше допустимого размера" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            connection.disconnect()
            check(destination.length() > 0) { "Сервер вернул пустой файл" }
            check(destination.inputStream().use { input ->
                val header = ByteArray(2)
                input.read(header) == 2 && header[0] == 'P'.code.toByte() &&
                    header[1] == 'K'.code.toByte()
            }) { "Загруженный файл не похож на APK" }
            return destination
        }
        error("Не удалось загрузить APK")
    }

    private fun installApk(file: File): String {
        check(file.isFile && file.length() > 0) { "APK не найден или пуст" }
        check(file.length() <= MAX_APK_BYTES) { "APK больше допустимого размера" }
        openAdb().use { adb ->
            val remote = "/data/local/tmp/yaos-upload-${System.currentTimeMillis()}.apk"
            try {
                push(adb, file, remote, MODE_FILE)
                val output = shellOk(adb, "pm install -r -d --user 0 $remote")
                check("Success" in output) { "Package Manager: ${output.trim()}" }
                return "APK установлен"
            } finally {
                adb.shell("rm -f $remote")
                if (file.parentFile == context.cacheDir) file.delete()
            }
        }
    }

    private fun validatePackageName(value: String): String {
        check(value.matches(PACKAGE_NAME)) { "Некорректное имя пакета" }
        return value
    }

    private fun shellOk(adb: Dadb, command: String): String {
        val response = adb.shell(command)
        check(response.exitCode == 0) {
            "ADB shell (${response.exitCode}): ${response.allOutput.trim()}"
        }
        return response.allOutput
    }

    private fun push(adb: Dadb, file: File, remotePath: String, mode: Int) {
        val result = file.source().use { source ->
            adb.push(source, remotePath, mode, file.lastModified())
        }
        check(result is SyncResult.Success) {
            "Не удалось передать ${file.name}: ${(result as? SyncResult.Failure)?.reason}"
        }
    }

    private fun openAdb(): Dadb =
        privilegeManager.openAdb(socketTimeout = 180_000)

    private fun safeFileName(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)

    private val Operation.displayName: String
        get() = when (this) {
            Operation.FREEZE -> "Заморозка"
            Operation.UNFREEZE -> "Разморозка"
            Operation.UNINSTALL_FOR_USER -> "Удаление для пользователя"
            Operation.RESTORE_FOR_USER -> "Восстановление"
            Operation.UNINSTALL_COMPLETELY -> "Полное удаление"
            Operation.CLEAR_CACHE -> "Очистка кэша"
            Operation.CLEAR_DATA -> "Очистка данных"
            Operation.FORCE_STOP -> "Остановка"
            Operation.TRIM_ALL_CACHES -> "Общая очистка кэша"
        }

    private fun Operation.successMessage(label: String): String =
        when (this) {
            Operation.FREEZE -> "$label заморожено"
            Operation.UNFREEZE -> "$label разморожено"
            Operation.UNINSTALL_FOR_USER -> "$label удалено для пользователя"
            Operation.RESTORE_FOR_USER -> "$label восстановлено"
            Operation.UNINSTALL_COMPLETELY -> "$label полностью удалено"
            Operation.CLEAR_CACHE -> "Кэш $label очищен"
            Operation.CLEAR_DATA -> "Данные $label очищены"
            Operation.FORCE_STOP -> "$label остановлено"
            Operation.TRIM_ALL_CACHES -> "Кэш всех приложений очищен"
        }

    companion object {
        private const val MODE_FILE = 420
        private const val MAX_REDIRECTS = 5
        private const val MAX_APK_BYTES = 1_500_000_000L
        private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
        private val DESTRUCTIVE_SELF_OPERATIONS = setOf(
            Operation.FREEZE,
            Operation.UNINSTALL_FOR_USER,
            Operation.UNINSTALL_COMPLETELY,
            Operation.CLEAR_DATA,
        )
        private val BLOCKED_PRESET_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.yandex.tv.services.platform",
            "dev.d4n13l3k00.yaosa11y",
        )
        private val EXECUTOR = Executors.newSingleThreadExecutor()
    }
}
