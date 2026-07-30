package dev.d4n13l3k00.yaosa11y.feature.apps

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class ApkDownloader(context: Context) {
    private val appContext = context.applicationContext
    private val userAgent by lazy {
        @Suppress("DEPRECATION")
        val version = appContext.packageManager
            .getPackageInfo(appContext.packageName, 0)
            .versionName
            .orEmpty()
        "YaOS-A11y-Manager/$version"
    }

    fun download(rawUrl: String): File =
        download(rawUrl, preferredName = null)

    fun download(
        rawUrl: String,
        preferredName: String? = null,
        progress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): File {
        var current = URI(rawUrl.trim()).toURL()
        requireHttp(current)
        repeat(MAX_REDIRECTS + 1) { redirect ->
            val connection = current.openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                connection.setRequestProperty("User-Agent", userAgent)
                connection.connect()
                if (connection.responseCode in 300..399) {
                    check(redirect < MAX_REDIRECTS) { "Слишком много перенаправлений" }
                    val location = connection.getHeaderField("Location")
                        ?: error("Перенаправление без Location")
                    current = URL(current, location)
                    requireHttp(current)
                    return@repeat
                }
                check(connection.responseCode in 200..299) {
                    "Сервер вернул HTTP ${connection.responseCode}"
                }
                val length =
                    connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                check(length <= MAX_APK_BYTES) { "APK больше допустимого размера" }
                val name = preferredName
                    ?.takeIf { it.endsWith(".apk", ignoreCase = true) }
                    ?: current.path.substringAfterLast('/').substringBefore('?')
                    .takeIf { it.endsWith(".apk", ignoreCase = true) }
                    ?: "download-${System.currentTimeMillis()}.apk"
                val destination = File(appContext.cacheDir, safeFileName(name))
                connection.inputStream.use { input ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= MAX_APK_BYTES) {
                                "APK больше допустимого размера"
                            }
                            output.write(buffer, 0, count)
                            progress(total, length)
                        }
                    }
                }
                validateApk(destination)
                return destination
            } finally {
                connection.disconnect()
            }
        }
        error("Не удалось загрузить APK")
    }

    private fun requireHttp(url: URL) {
        check(url.protocol == "http" || url.protocol == "https") {
            "Поддерживаются только ссылки http:// и https://"
        }
    }

    private fun validateApk(file: File) {
        check(file.length() in 1..MAX_APK_BYTES) { "Сервер вернул пустой или слишком большой файл" }
        check(file.inputStream().use { input ->
            val header = ByteArray(2)
            input.read(header) == 2 &&
                header[0] == 'P'.code.toByte() &&
                header[1] == 'K'.code.toByte()
        }) { "Загруженный файл не похож на APK" }
    }

    private fun safeFileName(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)

    companion object {
        const val MAX_APK_BYTES = 1_500_000_000L
        private const val MAX_REDIRECTS = 5
    }
}
