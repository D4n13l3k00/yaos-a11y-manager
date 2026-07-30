package dev.d4n13l3k00.yaosa11y.feature.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

data class VerifiedUpdate(
    val file: File,
    val versionName: String,
    val versionCode: Long,
    val sha256: String,
)

class UpdateVerifier(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    fun verify(release: UpdateRelease, file: File): VerifiedUpdate {
        check(file.isFile && file.length() == release.apkSize) {
            "Размер загруженного APK не совпадает с релизом"
        }
        val actualDigest = sha256(file)
        check(actualDigest.equals(release.sha256, ignoreCase = true)) {
            "SHA-256 загруженного APK не совпадает с GitHub Release"
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val installed = packageManager.getPackageInfo(appContext.packageName, flags)
        val archive = packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: error("Android не смог прочитать загруженный APK")
        check(archive.packageName == appContext.packageName) {
            "APK предназначен для другого пакета: ${archive.packageName}"
        }
        check(archive.versionName == release.versionName) {
            "Версия APK ${archive.versionName} не совпадает с тегом ${release.tagName}"
        }
        val installedCode = installed.longVersionCodeCompat()
        val archiveCode = archive.longVersionCodeCompat()
        check(archiveCode > installedCode) {
            "Версия APK не новее установленной ($archiveCode <= $installedCode)"
        }

        val installedSigners = installed.signerDigests()
        val archiveSigners = archive.signerDigests()
        check(installedSigners.isNotEmpty() && archiveSigners.isNotEmpty()) {
            "Не удалось прочитать сертификат подписи APK"
        }
        check(installedSigners == archiveSigners) {
            "Сертификат подписи обновления не совпадает с установленным приложением"
        }

        return VerifiedUpdate(
            file = file,
            versionName = archive.versionName.orEmpty(),
            versionCode = archiveCode,
            sha256 = actualDigest,
        )
    }

    private fun PackageInfo.signerDigests(): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            signatures.orEmpty()
        }
        return signatures.mapTo(linkedSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .toHex()
        }
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte) }
}
