package dev.d4n13l3k00.yaosa11y.feature.update

import android.content.Context

class UpdateStateStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun shouldRefresh(nowMillis: Long = System.currentTimeMillis()): Boolean =
        nowMillis - preferences.getLong(KEY_LAST_CHECKED_AT, 0L) >= CHECK_INTERVAL_MILLIS

    fun recordCheck(latestVersion: String) {
        preferences.edit()
            .putLong(KEY_LAST_CHECKED_AT, System.currentTimeMillis())
            .putString(KEY_LATEST_VERSION, latestVersion)
            .apply()
    }

    fun cachedLatestVersion(): String? =
        preferences.getString(KEY_LATEST_VERSION, null)

    fun shouldShowAvailableNotice(version: String): Boolean =
        preferences.getString(KEY_ACKNOWLEDGED_VERSION, null) != version

    fun acknowledgeAvailableNotice(version: String) {
        preferences.edit().putString(KEY_ACKNOWLEDGED_VERSION, version).apply()
    }

    fun markInstallPending(version: String) {
        preferences.edit().putString(KEY_PENDING_VERSION, version).apply()
    }

    fun clearPendingInstall() {
        preferences.edit().remove(KEY_PENDING_VERSION).apply()
    }

    fun markPackageReplaced(installedVersion: String) {
        preferences.getString(KEY_PENDING_VERSION, null) ?: return
        preferences.edit()
            .remove(KEY_PENDING_VERSION)
            .putString(KEY_COMPLETED_VERSION, installedVersion)
            .apply()
    }

    fun recordInstallerResult(message: String) {
        preferences.edit().putString(KEY_INSTALLER_RESULT, message).apply()
    }

    fun consumeInstallerResult(): String? {
        val message = preferences.getString(KEY_INSTALLER_RESULT, null) ?: return null
        preferences.edit().remove(KEY_INSTALLER_RESULT).apply()
        return message
    }

    fun consumeCompletedVersion(): String? {
        val version = preferences.getString(KEY_COMPLETED_VERSION, null) ?: return null
        preferences.edit().remove(KEY_COMPLETED_VERSION).apply()
        return version
    }

    companion object {
        private const val PREFERENCES = "app_updates"
        private const val KEY_LAST_CHECKED_AT = "last_checked_at"
        private const val KEY_LATEST_VERSION = "latest_version"
        private const val KEY_ACKNOWLEDGED_VERSION = "acknowledged_version"
        private const val KEY_PENDING_VERSION = "pending_version"
        private const val KEY_COMPLETED_VERSION = "completed_version"
        private const val KEY_INSTALLER_RESULT = "installer_result"
        private const val CHECK_INTERVAL_MILLIS = 6 * 60 * 60 * 1000L
    }
}
