package com.github.zly2006.zhihu.offline

import com.github.zly2006.zhihu.data.Feed
import com.github.zly2006.zhihu.data.ZhihuJson
import com.github.zly2006.zhihu.data.navDestination
import com.github.zly2006.zhihu.viewmodel.za.parseMobileHomeFeedDisplayItem
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class OfflineContentTest {
    private val url = "https://www.zhihu.com/api/v4/answers/42?include=content"

    private class MemoryBackend : OfflineBackend {
        var online = true
        val entries = mutableMapOf<String, OfflineResponse>()

        override fun connected() = online

        override suspend fun read(url: Url, profile: String) = entries[offlineRequestKey(url, profile)]

        override suspend fun save(response: OfflineResponse, prefetch: Boolean) {
            entries[offlineRequestKey(Url(response.url), response.profile)] = response
        }

        override suspend fun invalidate(url: Url, profile: String) {
            entries.remove(offlineRequestKey(url, profile))
        }
    }

    @Test
    fun onlineWritesAndNewClientReplaysWithoutNetwork() = runBlocking {
        val backend = MemoryBackend()
        var requests = 0

        fun client() = HttpClient(
            MockEngine {
                requests++
                respond("""{"id":42,"type":"answer","content":"完整正文"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ) {
            install(OfflineContentPlugin) { this.backend = backend }
        }
        client().use { assertTrue(it.get(url).bodyAsText().contains("完整正文")) }
        assertEquals(1, backend.entries.size)
        backend.online = false
        client().use { assertTrue(it.get(url).bodyAsText().contains("完整正文")) }
        assertEquals(1, requests)
    }

    @Test
    fun networkFailureFallsBackButPrefetchDoesNotPretendToSucceed() = runBlocking {
        val backend = MemoryBackend()
        backend.save(OfflineResponse(url, "web", """{"id":42,"content":"旧正文"}""", 1), false)
        HttpClient(MockEngine { throw IOException("连接中断") }) { install(OfflineContentPlugin) { this.backend = backend } }.use {
            assertTrue(it.get(url).bodyAsText().contains("旧正文"))
            try {
                it.get(url) { attributes.put(OfflinePrefetch, true) }
                fail("预取不能返回旧缓存冒充更新成功")
            } catch (_: IOException) {
            }
        }
    }

    @Test(timeout = 12_000)
    fun slowNetworkFallsBackWithinBudgetAndDoesNotCancelFollowingRequests() = runBlocking {
        val backend = MemoryBackend()
        backend.save(OfflineResponse(url, "web", """{"id":42,"content":"旧正文"}""", 1), false)
        var slow = true
        HttpClient(
            MockEngine {
                if (slow) delay(10_000)
                respond("""{"id":42,"content":"新正文"}""")
            },
        ) { install(OfflineContentPlugin) { this.backend = backend } }.use { client ->
            val before = System.nanoTime()
            assertTrue(client.get(url).bodyAsText().contains("旧正文"))
            assertTrue((System.nanoTime() - before) / 1_000_000 < 8_000)
            slow = false
            assertTrue(client.get(url).bodyAsText().contains("新正文"))
        }
    }

    @Test
    fun errorAndMalformedBodiesNeverOverwriteSuccessAndAuthIsNotHidden() = runBlocking {
        val backend = MemoryBackend()
        val original = OfflineResponse(url, "web", """{"id":42,"content":"可读"}""", 1)
        backend.save(original, false)
        var status = HttpStatusCode.Unauthorized
        var body = """{"error":{"message":"登录失效"}}"""
        HttpClient(MockEngine { respond(body, status) }) { install(OfflineContentPlugin) { this.backend = backend } }.use {
            assertEquals(HttpStatusCode.Unauthorized, it.get(url).status)
            status = HttpStatusCode.OK
            body = "<html>验证码</html>"
            assertEquals(body, it.get(url).bodyAsText())
            assertEquals(original, backend.entries.values.single())
            status = HttpStatusCode.ServiceUnavailable
            assertTrue(it.get(url).bodyAsText().contains("可读"))
        }
    }

    @Test
    fun cancellationPropagatesAndWriteRequestsAreNeverCached() = runBlocking {
        val backend = MemoryBackend()
        HttpClient(MockEngine { respond("""{"id":42}""") }) { install(OfflineContentPlugin) { this.backend = backend } }.use {
            it.post(url)
            it.get("https://www.zhihu.com/api/v4/me")
            assertTrue(backend.entries.isEmpty())
        }
        backend.save(OfflineResponse(url, "web", """{"id":42}""", 1), false)
        HttpClient(MockEngine { throw CancellationException("用户退出页面") }) { install(OfflineContentPlugin) { this.backend = backend } }.use {
            try {
                it.get(url)
                fail("取消必须继续传播")
            } catch (_: CancellationException) {
            }
        }
    }

    @Test
    fun profileSortIncludeAndCursorAreIsolatedButQueryOrderIsStable() {
        val first = Url("https://www.zhihu.com/api/v4/questions/1/feeds?order=default&limit=20")
        val reordered = Url("https://www.zhihu.com/api/v4/questions/1/feeds?limit=20&order=default")
        assertEquals(offlineRequestKey(first, "web"), offlineRequestKey(reordered, "web"))
        assertNotEquals(offlineRequestKey(first, "web"), offlineRequestKey(first, "mobile"))
        listOf("&offset=20", "&include=content", "&order=updated").forEach {
            assertNotEquals(offlineRequestKey(first, "web"), offlineRequestKey(Url("$first$it"), "web"))
        }
        assertFalse(isOfflineContentUrl(Url("https://evil.example/api/v4/answers/42")))
        assertFalse(isOfflineContentUrl(Url("https://www.zhihu.com/api/v4/answers/42/voters")))
        assertFalse(isOfflineContentUrl(Url("https://www.zhihu.com/api/v4/me")))
    }

    @Test
    fun discoversGroupsAndHandlesRelativePagination() {
        val body = Json.parseToJsonElement("""{"data":[{"target":{"type":"answer","id":"42","question":{"type":"question","id":7}}},{"type":"article","id":9},{"type":"zvideo","id":8}]}""")
        assertEquals(setOf("answer:42", "question:7", "article:9"), offlineNodes(body).map { it.key }.toSet())
        assertEquals("7", offlineNodes(body).first { it.kind == "answer" }.questionId)
        assertEquals("https://www.zhihu.com/api/v4/questions/7/feeds?offset=20", resolveOfflineNext("https://www.zhihu.com/api/v4/questions/7/feeds?offset=0", Json.parseToJsonElement("""{"paging":{"next":"?offset=20","is_end":false}}""") as kotlinx.serialization.json.JsonObject))
    }

    @Test fun offlineBoundaryPreservesRemoteCursorAndHidesIncompleteDetails() {
        val body = """{"data":[{"type":"answer","id":1},{"type":"answer","id":2}],"paging":{"next":"https://www.zhihu.com/api/v4/questions/7/feeds?offset=20","is_end":false}}"""
        val projected = Json.parseToJsonElement(projectOfflinePage(body, { it.id == "1" }, false)) as kotlinx.serialization.json.JsonObject
        assertTrue(projected.toString().contains("offline_cache_boundary"))
        assertFalse(projected.toString().contains("\"id\":2"))
        assertTrue(projected.toString().contains("offset=20"))
        assertTrue(projected.toString().contains("\"is_end\":true"))
        assertFalse(projectOfflinePage(body, { true }, true).contains("offline_cache_boundary"))
        assertEquals(1, (Json.parseToJsonElement(limitOfflineItems(body, 1)) as kotlinx.serialization.json.JsonObject)["data"]!!.let { it as kotlinx.serialization.json.JsonArray }.size)
    }

    @Test fun mobileRoutesDiscoverQuestionsAndCachedCardsUseExistingParsers() {
        val detail = Json.parseToJsonElement("""{"id":42,"type":"answer","url":"https://www.zhihu.com/answer/42","content":"正文","excerpt":"摘要","question":{"id":7,"type":"question","title":"问题标题","url":"https://www.zhihu.com/question/7"},"author":{"id":"author","name":"作者","url":"https://www.zhihu.com/people/author","headline":"介绍","avatar_url":"https://pic1.zhimg.com/avatar.jpg"}}""") as kotlinx.serialization.json.JsonObject
        val mobile = offlineHomeCard("answer", "42", detail, "mobile")
        val item = requireNotNull(parseMobileHomeFeedDisplayItem(mobile))
        assertEquals("问题标题", item.title)
        assertNotNull(item.navDestination)
        assertEquals("7", offlineNodes(mobile).single { it.kind == "answer" }.questionId)
        val web = ZhihuJson.decodeJson<Feed>(offlineHomeCard("answer", "42", detail, "web"))
        assertTrue(web is com.github.zly2006.zhihu.data.CommonFeed)
        assertEquals(42L, ((web as com.github.zly2006.zhihu.data.CommonFeed).target as Feed.AnswerTarget).id)
    }

    @Test fun privateLocalPaginationParametersNeverReachOnlineServer() = runBlocking {
        val backend = MemoryBackend()
        var actual: Url? = null
        HttpClient(
            MockEngine { request ->
                actual = request.url
                respond("""{"data":[]}""")
            },
        ) {
            install(OfflineContentPlugin) { this.backend = backend }
        }.use { client -> client.get("https://api.zhihu.com/topstory/recommend?__offline_snapshot=abc&__offline_offset=20&include=content") }
        assertNull(actual?.parameters?.get("__offline_snapshot"))
        assertNull(actual?.parameters?.get("__offline_offset"))
        assertEquals("content", actual?.parameters?.get("include"))
    }
}
