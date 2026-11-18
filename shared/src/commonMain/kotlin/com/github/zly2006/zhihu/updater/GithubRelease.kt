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

import com.github.zly2006.zhihu.util.raiseForStatus
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val EINK_APPLICATION_ID = "com.github.xiayuhu0414.zhihu.eink"
const val EINK_REPOSITORY = "xiayuhu0414/zhihu-eink"
const val EINK_RELEASES_URL = "https://github.com/$EINK_REPOSITORY/releases"
const val EINK_RELEASES_API_URL = "https://api.github.com/repos/$EINK_REPOSITORY/releases?per_page=100"
const val EINK_AUTO_UPDATE_KEY = "einkAutoCheckUpdates"
const val MAX_UPDATE_APK_BYTES = 128L * 1024 * 1024

@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("html_url") val htmlUrl: String? = null,
    val body: String? = null,
    val assets: List<GithubAsset> = emptyList(),
    val draft: Boolean = false,
    val prerelease: Boolean = false,
)

@Serializable
data class GithubAsset(
    val name: String,
    @SerialName("content_type") val contentType: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0,
)

data class EinkUpdateRelease(
    val versionName: String,
    val versionCode: Int,
    val release: GithubRelease,
    val apk: GithubAsset,
    val checksums: GithubAsset,
)

// 标签、APK 文件名与 CI 构建号一一对应；正式版及其他上游格式不能进入测试通道。
fun GithubRelease.toEinkUpdateRelease(): EinkUpdateRelease? {
    if (draft || !prerelease) return null
    val match = Regex("test-v(0\\.1\\.([1-9][0-9]*))").matchEntire(tagName) ?: return null
    val code = match.groupValues[2].toIntOrNull()?.takeIf { it in 1..2100000000 } ?: return null
    val version = match.groupValues[1]
    val apkName = "zhihu-eink-lite-$version.apk"
    val apk = assets.singleOrNull { it.name == apkName } ?: return null
    val checksums = assets.singleOrNull { it.name == "SHA256SUMS.txt" } ?: return null
    val prefix = "$EINK_RELEASES_URL/download/$tagName/"
    if (apk.browserDownloadUrl != prefix + apkName || apk.size !in 1..MAX_UPDATE_APK_BYTES) return null
    if (checksums.browserDownloadUrl != prefix + checksums.name || checksums.size !in 1..16384) return null
    return EinkUpdateRelease(version, code, this, apk, checksums)
}

fun selectLatestEinkRelease(releases: List<GithubRelease>): EinkUpdateRelease? = releases
    .mapNotNull { it.toEinkUpdateRelease() }
    .maxByOrNull { it.versionCode }

suspend fun fetchLatestEinkRelease(client: HttpClient): EinkUpdateRelease? = selectLatestEinkRelease(
    client.get(EINK_RELEASES_API_URL) {
        header("Accept", "application/vnd.github+json")
        header("X-GitHub-Api-Version", "2022-11-28")
        header("User-Agent", "zhihu-eink")
    }.raiseForStatus().body<List<GithubRelease>>(),
)

fun extractGithubReleaseNotes(body: String): String = body
    .replace("\r\n", "\n")
    .substringAfter("## What's Changed\n")
    .substringBefore("\n**Full Changelog**:")
    .trimEnd('\n')

fun updateApkSha256(checksums: String, apkName: String): String {
    val matches = checksums.lineSequence().mapNotNull { line ->
        val match = Regex("([0-9a-fA-F]{64}) [ *](.+)").matchEntire(line) ?: return@mapNotNull null
        match.groupValues[1].lowercase().takeIf { match.groupValues[2] == apkName }
    }.toList()
    require(matches.size == 1) { "更新校验文件缺少唯一的 APK 摘要" }
    return matches.single()
}

data class UpdateApkIdentity(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val signerSha256: Set<String>,
)

fun validateUpdateApkIdentity(
    current: UpdateApkIdentity,
    archive: UpdateApkIdentity,
    expected: EinkUpdateRelease,
    deviceSdk: Int,
) {
    require(current.packageName == EINK_APPLICATION_ID && archive.packageName == current.packageName) { "更新包的应用包名不匹配" }
    require(archive.versionCode == expected.versionCode.toLong() && archive.versionName == expected.versionName) { "更新包版本与发布信息不匹配" }
    require(archive.versionCode > current.versionCode) { "更新包不是更高的构建版本" }
    require(archive.minSdk <= deviceSdk) { "更新包不支持当前 Android 版本" }
    require(current.signerSha256.isNotEmpty() && archive.signerSha256 == current.signerSha256) { "更新包签名与当前安装不一致，请使用相同签名的版本" }
}
