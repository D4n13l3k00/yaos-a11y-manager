package dev.d4n13l3k00.yaosa11y.feature.apps

import android.content.Context
import dev.d4n13l3k00.yaosa11y.core.adb.AdbGateway
import java.io.File

class ApkInstaller(
    context: Context,
    private val gateway: AdbGateway,
) {
    private val cacheDirectory = context.applicationContext.cacheDir

    fun install(file: File): String {
        check(file.isFile && file.length() > 0) { "APK не найден или пуст" }
        check(file.length() <= ApkDownloader.MAX_APK_BYTES) {
            "APK больше допустимого размера"
        }
        return gateway.withConnection(socketTimeout = INSTALL_TIMEOUT_MILLIS) { adb ->
            val remote = "/data/local/tmp/yaos-upload-${System.currentTimeMillis()}.apk"
            try {
                gateway.push(adb, file, remote, MODE_FILE)
                val output = gateway.shell(adb, "pm install -r -d --user 0 $remote")
                check("Success" in output) { "Package Manager: ${output.trim()}" }
                "APK установлен"
            } finally {
                adb.shell("rm -f $remote")
                if (file.parentFile == cacheDirectory) file.delete()
            }
        }
    }

    companion object {
        private const val MODE_FILE = 420
        private const val INSTALL_TIMEOUT_MILLIS = 180_000
    }
}
