package dev.d4n13l3k00.yaosa11y.feature.apps

import android.content.Context
import dev.d4n13l3k00.yaosa11y.core.adb.AdbGateway
import dev.d4n13l3k00.yaosa11y.core.adb.ShellPolicy
import dev.d4n13l3k00.yaosa11y.core.privilege.PrivilegeManager

class PackageOperator(
    context: Context,
    private val privilegeManager: PrivilegeManager,
    private val gateway: AdbGateway,
) {
    private val ownPackage = context.applicationContext.packageName

    fun run(
        operation: AppManagerController.Operation,
        app: ManagedApp?,
    ): String {
        if (operation == AppManagerController.Operation.TRIM_ALL_CACHES) {
            return withPackageShell { shell ->
                shell("pm trim-caches 999G")
                    .ifBlank { "Кэш всех приложений очищен" }
                    .trim()
            }
        }

        val target = requireNotNull(app)
        val packageName = ShellPolicy.requirePackageName(target.packageName)
        check(packageName != ownPackage || operation !in DESTRUCTIVE_SELF_OPERATIONS) {
            "YaOS A11y Manager не может удалить или заморозить сам себя"
        }
        if (operation == AppManagerController.Operation.CLEAR_CACHE) {
            clearCache(packageName)
            return operation.successMessage(target.label)
        }

        return withPackageShell { shell ->
            val output = when (operation) {
                AppManagerController.Operation.FREEZE ->
                    shell("pm disable-user --user 0 $packageName")
                AppManagerController.Operation.UNFREEZE ->
                    shell("pm enable --user 0 $packageName")
                AppManagerController.Operation.UNINSTALL_FOR_USER ->
                    shell("pm uninstall --user 0 $packageName")
                AppManagerController.Operation.RESTORE_FOR_USER ->
                    shell("cmd package install-existing --user 0 $packageName")
                AppManagerController.Operation.UNINSTALL_COMPLETELY ->
                    shell("pm uninstall $packageName")
                AppManagerController.Operation.CLEAR_DATA ->
                    shell("pm clear --user 0 $packageName")
                AppManagerController.Operation.FORCE_STOP ->
                    shell("am force-stop --user 0 $packageName")
                AppManagerController.Operation.CLEAR_CACHE,
                AppManagerController.Operation.TRIM_ALL_CACHES,
                -> error("Недостижимая операция")
            }
            PackageCommandPolicy.requireSuccess(operation, output)
            operation.successMessage(target.label)
        }
    }

    fun setPackagesEnabled(packageNames: List<String>, enabled: Boolean): String {
        val targets = packageNames.distinct().map(ShellPolicy::requirePackageName)
        check(targets.isNotEmpty()) { "Не выбрано ни одного пакета" }
        check(targets.none { it in PresetCatalog.blockedPackages }) {
            "Защищённый системный пакет нельзя изменить через пресет"
        }

        return withPackageShell { shell ->
            val lines = ArrayList<String>()
            var successful = 0
            targets.forEach { packageName ->
                val command = if (enabled) {
                    "pm enable --user 0 $packageName"
                } else {
                    "pm disable-user --user 0 $packageName"
                }
                val attempt = runCatching {
                    val output = shell(command)
                    PackageCommandPolicy.requireSuccess(
                        if (enabled) {
                            AppManagerController.Operation.UNFREEZE
                        } else {
                            AppManagerController.Operation.FREEZE
                        },
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
            val action = if (enabled) "включено" else "отключено"
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

    private fun clearCache(packageName: String) {
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
                "if [ -d \"\$d\" ]; then " +
                "find \"\$d\" -mindepth 1 -maxdepth 1 -exec rm -rf {} +; fi; " +
                "done; echo CACHE_OK"
        val output = privilegeManager.shellAsRoot(root.rootBackend, command)
        check("CACHE_OK" in output) {
            "${root.rootBackend.displayName} не подтвердил очистку кэша: ${output.trim()}"
        }
    }

    private fun <T> withPackageShell(block: ((String) -> String) -> T): T {
        if (gateway.probe().available) {
            return gateway.withConnection { adb ->
                block { command -> gateway.shell(adb, command) }
            }
        }

        val root = privilegeManager.ensureRootBackend(allowAdbRestart = false)
        check(root.success && root.rootBackend != null) {
            "Для операции нужен ADB или root: ${root.message}"
        }
        return block { command -> privilegeManager.shellAsRoot(root.rootBackend, command) }
    }

    private fun AppManagerController.Operation.successMessage(label: String): String =
        when (this) {
            AppManagerController.Operation.FREEZE -> "$label заморожено"
            AppManagerController.Operation.UNFREEZE -> "$label разморожено"
            AppManagerController.Operation.UNINSTALL_FOR_USER -> "$label удалено для пользователя"
            AppManagerController.Operation.RESTORE_FOR_USER -> "$label восстановлено"
            AppManagerController.Operation.UNINSTALL_COMPLETELY -> "$label полностью удалено"
            AppManagerController.Operation.CLEAR_CACHE -> "Кэш $label очищен"
            AppManagerController.Operation.CLEAR_DATA -> "Данные $label очищены"
            AppManagerController.Operation.FORCE_STOP -> "$label остановлено"
            AppManagerController.Operation.TRIM_ALL_CACHES -> "Кэш всех приложений очищен"
        }

    companion object {
        private val DESTRUCTIVE_SELF_OPERATIONS = setOf(
            AppManagerController.Operation.FREEZE,
            AppManagerController.Operation.UNINSTALL_FOR_USER,
            AppManagerController.Operation.UNINSTALL_COMPLETELY,
            AppManagerController.Operation.CLEAR_DATA,
        )
    }
}

object PackageCommandPolicy {
    fun isSuccess(output: String): Boolean {
        val normalized = output.trim()
        return normalized.isBlank() ||
            "Success" in normalized ||
            "success" in normalized ||
            "new state" in normalized ||
            "installed for user" in normalized ||
            "Package " in normalized
    }

    fun requireSuccess(operation: AppManagerController.Operation, output: String) {
        check(isSuccess(output)) { "${operation.displayName}: ${output.trim()}" }
    }

    val AppManagerController.Operation.displayName: String
        get() = when (this) {
            AppManagerController.Operation.FREEZE -> "Заморозка"
            AppManagerController.Operation.UNFREEZE -> "Разморозка"
            AppManagerController.Operation.UNINSTALL_FOR_USER -> "Удаление для пользователя"
            AppManagerController.Operation.RESTORE_FOR_USER -> "Восстановление"
            AppManagerController.Operation.UNINSTALL_COMPLETELY -> "Полное удаление"
            AppManagerController.Operation.CLEAR_CACHE -> "Очистка кэша"
            AppManagerController.Operation.CLEAR_DATA -> "Очистка данных"
            AppManagerController.Operation.FORCE_STOP -> "Остановка"
            AppManagerController.Operation.TRIM_ALL_CACHES -> "Общая очистка кэша"
        }
}
