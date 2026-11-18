/*
 * Zhihu++ - Free & Ad-Free Zhihu client for all platforms.
 * Copyright (C) 2024-2026, zly2006 <i@zly2006.me>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation (version 3 only).
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.github.zly2006.zhihu.updater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import com.github.zly2006.zhihu.platform.androidSettingsStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

object UpdateManager {
    private const val AUTO_CHECK_INTERVAL_MILLIS = 24 * 60 * 60 * 1000L
    private const val PREF_SKIPPED_VERSION = "einkSkippedUpdateVersion"
    private const val PREF_LAST_UPDATE_CHECK = "einkLastUpdateCheck"
    private val operation = Mutex()

    // 更新访问不复用知乎登录客户端，不向 GitHub 发送账号 Cookie 或私人令牌。
    private val client by lazy {
        HttpClient(Android) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(HttpTimeout) {
                requestTimeoutMillis = 30000
                connectTimeoutMillis = 15000
                socketTimeoutMillis = 15000
            }
        }
    }

    sealed class UpdateState {
        object NoUpdate : UpdateState()
        object Checking : UpdateState()
        object Latest : UpdateState()

        data class UpdateAvailable(val update: EinkUpdateRelease) : UpdateState() {
            val version: SemanticVersion get() = SemanticVersion.fromString(update.versionName)
            val isNightly: Boolean get() = true
            val releaseNotes: String? get() = update.release.body?.let(::extractGithubReleaseNotes)
            val downloadUrl: String get() = update.apk.browserDownloadUrl
            val cnDownloadUrl: String? get() = null
        }

        object Downloading : UpdateState()
        data class Downloaded(val file: File, val update: EinkUpdateRelease, val sha256: String) : UpdateState()
        data class Error(val message: String) : UpdateState()
    }

    val updateState = MutableStateFlow<UpdateState>(UpdateState.NoUpdate)

    fun skipVersion(context: Context, version: String) {
        androidSettingsStore(context).putString(PREF_SKIPPED_VERSION, version)
    }

    suspend fun autoCheckForUpdate(context: Context): Boolean = check(context, automatic = true)

    suspend fun checkForUpdate(context: Context) {
        check(context, automatic = false)
    }

    private suspend fun check(context: Context, automatic: Boolean): Boolean {
        if (!operation.tryLock()) return false
        val previous = updateState.value
        try {
            if (previous is UpdateState.Downloaded) return false
            val settings = androidSettingsStore(context)
            if (automatic) {
                if (!settings.getBoolean(EINK_AUTO_UPDATE_KEY, true)) return false
                val elapsed = System.currentTimeMillis() - settings.getLong(PREF_LAST_UPDATE_CHECK, 0)
                if (elapsed in 0 until AUTO_CHECK_INTERVAL_MILLIS) return false
            }
            updateState.value = UpdateState.Checking
            settings.putLong(PREF_LAST_UPDATE_CHECK, System.currentTimeMillis())
            val latest = fetchLatestEinkRelease(client)
            val currentCode = PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
            Log.i("EinkUpdate", "source=$EINK_REPOSITORY currentCode=$currentCode latestCode=${latest?.versionCode}")
            val skipped = settings.getStringOrNull(PREF_SKIPPED_VERSION)
            updateState.value = when {
                latest == null -> UpdateState.Error("暂无已发布的测试版本")
                latest.versionCode > currentCode && (!automatic || skipped != latest.versionName) -> UpdateState.UpdateAvailable(latest)
                else -> UpdateState.Latest
            }
            return updateState.value is UpdateState.UpdateAvailable
        } catch (e: CancellationException) {
            updateState.value = previous
            throw e
        } catch (e: Exception) {
            Log.w("EinkUpdate", "检查更新失败", e)
            updateState.value = UpdateState.Error(e.message ?: "检查更新失败，请稍后重试")
            return false
        } finally {
            operation.unlock()
        }
    }

    suspend fun downloadUpdate(context: Context, downloadUrl: String) {
        if (!operation.tryLock()) return
        val available = updateState.value as? UpdateState.UpdateAvailable
        if (available == null) {
            operation.unlock()
            return
        }
        val temporary = File(context.cacheDir, "eink-update-download.apk")
        try {
            require(downloadUrl == available.downloadUrl) { "更新下载地址不匹配" }
            updateState.value = UpdateState.Downloading
            val downloaded = withContext(Dispatchers.IO) {
                val update = available.update
                val checksumBytes = ByteArrayOutputStream()
                downloadGithubAsset(update.checksums, checksumBytes)
                val expectedHash = updateApkSha256(checksumBytes.toString("UTF-8"), update.apk.name)
                temporary.outputStream().use { downloadGithubAsset(update.apk, it) }
                verifyApk(context, temporary, update, expectedHash)
                val destination = File(context.cacheDir, "eink-update.apk")
                require(temporary.renameTo(destination)) { "无法保存更新包" }
                UpdateState.Downloaded(destination, update, expectedHash)
            }
            updateState.value = downloaded
        } catch (e: CancellationException) {
            updateState.value = available
            throw e
        } catch (e: Exception) {
            updateState.value = UpdateState.Error(e.message ?: "下载或校验更新失败，请重新检查更新")
        } finally {
            temporary.delete()
            operation.unlock()
        }
    }

    suspend fun installUpdate(context: Context, file: File) {
        if (!operation.tryLock()) return
        try {
            val downloaded = updateState.value as? UpdateState.Downloaded ?: return
            require(file == downloaded.file) { "安装文件不匹配" }
            withContext(Dispatchers.IO) { verifyApk(context, file, downloaded.update, downloaded.sha256) }
            withContext(Dispatchers.Main) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    Toast.makeText(context, "请允许安装未知应用，返回后再次点击安装更新", Toast.LENGTH_LONG).show()
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } else {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                        },
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateState.value = UpdateState.Error(e.message ?: "无法安装更新，请重新检查更新")
        } finally {
            operation.unlock()
        }
    }

    private suspend fun downloadGithubAsset(asset: GithubAsset, output: OutputStream) {
        var uri = URI(asset.browserDownloadUrl)
        repeat(6) { attempt ->
            require(uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443)) { "更新下载必须使用 HTTPS" }
            require(uri.host in setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com", "github-releases.githubusercontent.com")) {
                "更新下载跳转到不支持的地址"
            }
            val connection = uri.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("Accept-Encoding", "identity")
            try {
                currentCoroutineContext().ensureActive()
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    require(attempt < 5) { "更新下载重定向次数过多" }
                    uri = uri.resolve(connection.getHeaderField("Location") ?: error("下载跳转缺少地址"))
                } else {
                    require(status == 200) { "更新下载失败（HTTP $status）" }
                    val declared = connection.contentLengthLong
                    require(declared == -1L || declared == asset.size) { "更新文件大小与发布信息不匹配" }
                    var received = 0L
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(32768)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            require(received <= asset.size) { "更新下载超过预期大小" }
                            output.write(buffer, 0, count)
                        }
                    }
                    require(received == asset.size) { "更新文件未下载完整" }
                    return
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(context: Context, file: File, expected: EinkUpdateRelease, expectedHash: String) {
        require(file.isFile && file.length() == expected.apk.size) { "更新包大小不匹配" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32768)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        require(digest.digest().hex() == expectedHash) { "更新包 SHA-256 校验失败" }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val current = context.packageManager.getPackageInfo(context.packageName, flags)
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: error("无法读取更新包")
        validateUpdateApkIdentity(current.identity(), archive.identity(), expected, Build.VERSION.SDK_INT)
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.identity(): UpdateApkIdentity {
        val signers = if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures
        return UpdateApkIdentity(
            packageName = packageName,
            versionName = versionName.orEmpty(),
            versionCode = PackageInfoCompat.getLongVersionCode(this),
            minSdk = applicationInfo?.minSdkVersion ?: Int.MAX_VALUE,
            signerSha256 = signers.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }.toSet(),
        )
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}
