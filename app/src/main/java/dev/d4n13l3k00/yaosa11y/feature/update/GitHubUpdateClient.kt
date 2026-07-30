package dev.d4n13l3k00.yaosa11y.feature.update

import android.content.Context
import dev.d4n13l3k00.yaosa11y.feature.apps.ApkDownloader
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

data class UpdateRelease(
    val tagName: String,
    val versionName: String,
    val title: String,
    val notes: String,
    val publishedAt: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256: String,
)

class GitHubUpdateClient(context: Context) {
    private data class Asset(
        val name: String,
        val url: String,
        val size: Long,
        val digest: String?,
    )

    private val appContext = context.applicationContext
    private val downloader = ApkDownloader(appContext)
    private val userAgent by lazy {
        "YaOS-A11y-Manager/${installedVersionName()}"
    }

    fun latestRelease(): UpdateRelease {
        val release = JSONObject(readText(LATEST_RELEASE_URL, MAX_METADATA_BYTES))
        val tagName = release.getString("tag_name")
        val versionName = tagName.removePrefix("v")
        check(versionName.isNotBlank()) { "GitHub вернул релиз без версии" }

        val assetsJson = release.getJSONArray("assets")
        val assets = buildList {
            repeat(assetsJson.length()) { index ->
                val asset = assetsJson.getJSONObject(index)
                add(
                    Asset(
                        name = asset.getString("name"),
                        url = asset.getString("browser_download_url"),
                        size = asset.optLong("size", -1L),
                        digest = asset.optString("digest")
                            .takeIf { it.startsWith("sha256:", ignoreCase = true) }
                            ?.substringAfter(':'),
                    ),
                )
            }
        }
        val apk = assets
            .filter { it.name.endsWith(".apk", ignoreCase = true) }
            .sortedByDescending {
                it.name.startsWith("YaOS-A11y-Manager-v", ignoreCase = true)
            }
            .firstOrNull()
            ?: error("В релизе $tagName не найден APK")
        check(apk.size in 1..ApkDownloader.MAX_APK_BYTES) {
            "Некорректный размер APK в релизе: ${apk.size}"
        }
        val checksum = apk.digest ?: checksumFromAsset(assets, apk)
        check(SHA256.matches(checksum)) { "GitHub вернул некорректный SHA-256 APK" }

        return UpdateRelease(
            tagName = tagName,
            versionName = versionName,
            title = release.optString("name").ifBlank { tagName },
            notes = release.optString("body").trim().take(MAX_NOTES_CHARS),
            publishedAt = release.optString("published_at"),
            apkName = apk.name,
            apkUrl = apk.url,
            apkSize = apk.size,
            sha256 = checksum.lowercase(),
        )
    }

    fun download(
        release: UpdateRelease,
        progress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): File = downloader.download(
        rawUrl = release.apkUrl,
        preferredName = release.apkName,
        progress = progress,
    )

    private fun checksumFromAsset(assets: List<Asset>, apk: Asset): String {
        val checksumAsset = assets.firstOrNull {
            it.name.equals("${apk.name}.sha256", ignoreCase = true)
        } ?: assets.firstOrNull {
            it.name.equals("SHA256SUMS.txt", ignoreCase = true)
        } ?: error("В метаданных релиза нет SHA-256 для ${apk.name}")
        val content = readText(checksumAsset.url, MAX_CHECKSUM_BYTES)
        return content.lineSequence()
            .firstOrNull { apk.name in it || checksumAsset.name.endsWith(".sha256", true) }
            ?.let { SHA256.find(it)?.value }
            ?: error("Не удалось прочитать SHA-256 для ${apk.name}")
    }

    private fun readText(rawUrl: String, maxBytes: Int): String {
        val url = URI(rawUrl).toURL()
        check(url.protocol == "https") { "Метаданные обновления доступны только по HTTPS" }
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", GITHUB_API_VERSION)
            connection.setRequestProperty("User-Agent", userAgent)
            connection.connect()
            check(connection.responseCode in 200..299) {
                when (connection.responseCode) {
                    403 -> "GitHub временно ограничил частоту проверок обновлений"
                    404 -> "Релизы приложения пока не опубликованы"
                    else -> "GitHub вернул HTTP ${connection.responseCode}"
                }
            }
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= maxBytes) { "Ответ GitHub слишком большой" }
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }

    @Suppress("DEPRECATION")
    private fun installedVersionName(): String =
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName.orEmpty()

    companion object {
        private const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/D4n13l3k00/yaos-a11y-manager/releases/latest"
        private const val GITHUB_API_VERSION = "2022-11-28"
        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val READ_TIMEOUT_MILLIS = 30_000
        private const val MAX_METADATA_BYTES = 512 * 1024
        private const val MAX_CHECKSUM_BYTES = 64 * 1024
        private const val MAX_NOTES_CHARS = 12_000
        private val SHA256 = Regex("""(?i)\b[0-9a-f]{64}\b""")
    }
}
