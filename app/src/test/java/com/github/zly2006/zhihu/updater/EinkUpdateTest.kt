package com.github.zly2006.zhihu.updater

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class EinkUpdateTest {
    private fun release(code: Int = 4): GithubRelease {
        val version = "0.1.$code"
        val tag = "test-v$version"
        val prefix = "$EINK_RELEASES_URL/download/$tag/"
        return GithubRelease(
            tagName = tag,
            prerelease = true,
            assets = listOf(
                GithubAsset("zhihu-eink-lite-$version.apk", browserDownloadUrl = prefix + "zhihu-eink-lite-$version.apk", size = 100),
                GithubAsset("SHA256SUMS.txt", browserDownloadUrl = prefix + "SHA256SUMS.txt", size = 120),
            ),
        )
    }

    @Test
    fun selectsHighestBuildCodeRegardlessOfReleaseOrder() {
        assertEquals(10, selectLatestEinkRelease(listOf(release(9), release(10), release(3)))?.versionCode)
        assertNull(selectLatestEinkRelease(emptyList()))
    }

    @Test
    fun ignoresDraftStableUpstreamAndInvalidTags() {
        val latest = release(11)
        val ignored = listOf(
            latest.copy(draft = true), latest.copy(prerelease = false), latest.copy(tagName = "nightly"),
            latest.copy(tagName = "v10.0.0"), latest.copy(tagName = "test-v0.1.0"),
            latest.copy(tagName = "test-v0.1.2147483648"), latest.copy(tagName = "test-v0.1.01"),
        )
        ignored.forEach { assertNull(it.toEinkUpdateRelease()) }
        assertEquals(4, selectLatestEinkRelease(ignored + release())?.versionCode)
    }

    @Test
    fun rejectsMissingDuplicateWrongRepositoryAndOversizedAssets() {
        val good = release()
        val apk = good.assets.first()
        assertNull(good.copy(assets = listOf(apk)).toEinkUpdateRelease())
        assertNull(good.copy(assets = good.assets + apk).toEinkUpdateRelease())
        for (invalid in listOf(
            apk.copy(browserDownloadUrl = apk.browserDownloadUrl.replace("xiayuhu0414/zhihu-eink", "zly2006/zhihu-plus-plus")),
            apk.copy(browserDownloadUrl = apk.browserDownloadUrl.replace("https://", "http://")),
            apk.copy(browserDownloadUrl = apk.browserDownloadUrl + "?redirect=other"),
            apk.copy(name = "app-full.apk"), apk.copy(size = 0), apk.copy(size = MAX_UPDATE_APK_BYTES + 1),
        )) {
            assertNull(good.copy(assets = listOf(invalid, good.assets.last())).toEinkUpdateRelease())
        }
        assertNotNull(good.toEinkUpdateRelease())
    }

    @Test
    fun checksumMustDescribeExactlyOneExpectedApk() {
        val hash = "a".repeat(64)
        val apk = release().assets.first().name
        assertEquals(hash, updateApkSha256("$hash  $apk\n${"b".repeat(64)}  source.tar.gz\n", apk))
        assertEquals(hash, updateApkSha256("${hash.uppercase()} *$apk\r\n", apk))
        assertThrows(IllegalArgumentException::class.java) { updateApkSha256("$hash  wrong.apk", apk) }
        assertThrows(IllegalArgumentException::class.java) { updateApkSha256("$hash  $apk\n$hash  $apk", apk) }
        assertThrows(IllegalArgumentException::class.java) { updateApkSha256("invalid  $apk", apk) }
    }

    private val installed = UpdateApkIdentity(EINK_APPLICATION_ID, "0.1.3", 3, 27, setOf("fixed-certificate"))
    private val archive = installed.copy(versionName = "0.1.4", versionCode = 4)

    @Test
    fun samePackageAndSignatureWithHigherCodeCanUpgrade() {
        val expected = requireNotNull(release().toEinkUpdateRelease())
        validateUpdateApkIdentity(installed, archive, expected, 27)
        // 决策依赖 code，不因当前版本名的显示格式而误判。
        validateUpdateApkIdentity(installed.copy(versionName = "9.9.9"), archive, expected, 34)
    }

    @Test
    fun rejectsWrongPackageVersionSignerSystemAndDowngrade() {
        val expected = requireNotNull(release().toEinkUpdateRelease())
        for (bad in listOf(
            archive.copy(packageName = "com.github.zly2006.zhplus.lite"),
            archive.copy(versionCode = 5), archive.copy(versionName = "0.1.5"),
            archive.copy(signerSha256 = emptySet()), archive.copy(signerSha256 = setOf("other-certificate")),
            archive.copy(minSdk = 35),
        )) {
            assertThrows(IllegalArgumentException::class.java) { validateUpdateApkIdentity(installed, bad, expected, 34) }
        }
        assertThrows(IllegalArgumentException::class.java) { validateUpdateApkIdentity(archive, archive, expected, 34) }
        assertThrows(IllegalArgumentException::class.java) { validateUpdateApkIdentity(installed.copy(versionCode = 5), archive, expected, 34) }
    }

    @Test
    fun decodesAnonymousGithubReleaseListWithUnknownFields() {
        val payload = """[{"tag_name":"test-v0.1.4","draft":false,"prerelease":true,"id":123,"assets":[
            {"name":"zhihu-eink-lite-0.1.4.apk","size":100,"browser_download_url":"$EINK_RELEASES_URL/download/test-v0.1.4/zhihu-eink-lite-0.1.4.apk"},
            {"name":"SHA256SUMS.txt","size":120,"browser_download_url":"$EINK_RELEASES_URL/download/test-v0.1.4/SHA256SUMS.txt"}]}]"""
        val parsed = Json { ignoreUnknownKeys = true }.decodeFromString<List<GithubRelease>>(payload)
        assertEquals(4, selectLatestEinkRelease(parsed)?.versionCode)
    }
}
