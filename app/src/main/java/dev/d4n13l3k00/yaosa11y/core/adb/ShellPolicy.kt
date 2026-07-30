package dev.d4n13l3k00.yaosa11y.core.adb

object ShellPolicy {
    private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")

    fun requirePackageName(value: String): String {
        check(PACKAGE_NAME.matches(value)) { "Некорректное имя пакета" }
        return value
    }

    fun quote(value: String): String =
        "'${value.replace("'", "'\"'\"'")}'"
}
