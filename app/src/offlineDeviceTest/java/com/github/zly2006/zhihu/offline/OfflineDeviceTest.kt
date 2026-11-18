package com.github.zly2006.zhihu.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineDeviceTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val answerUrl = "https://www.zhihu.com/api/v4/answers/42000001?include=content,excerpt"
    private val questionUrl = "https://www.zhihu.com/api/v4/questions/7000001"
    private val commentsUrl = "https://www.zhihu.com/api/v4/comment_v5/answers/42000001/root_comment?order_by=score"
    private val imageUrl = "https://pic1.zhimg.com/offline-device-fixture.png"
    private val options = OfflineOptions(questions = 1, answers = 2, articles = 1)

    private fun connected(): Boolean = hasOfflineNetwork(context)

    @Suppress("DEPRECATION")
    private fun networkDescription(): String {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return "active=${manager.activeNetwork}; networks=" + manager.allNetworks.joinToString { network ->
            val capabilities = manager.getNetworkCapabilities(network)
            "$network(vpn=${capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)},wifi=${capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)},internet=${capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)},validated=${capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)})"
        }
    }

    private fun backend(db: OfflineDatabase): OfflineBackend = object : OfflineBackend {
        override fun connected() = this@OfflineDeviceTest.connected()

        override suspend fun read(url: Url, profile: String) = withContext(Dispatchers.IO) { db.read(url, profile) }

        override suspend fun save(response: OfflineResponse, prefetch: Boolean) = withContext(Dispatchers.IO) { db.save(response, setOf(imageUrl), options) }

        override suspend fun invalidate(url: Url, profile: String) = withContext(Dispatchers.IO) { db.invalidate(url, profile) }
    }

    @Test fun prepare() = runBlocking {
        assertTrue("准备阶段设备应联网", connected())
        val db = OfflineDatabase(context, "device-fixture")
        try {
            db.clear()
            val client = HttpClient(
                MockEngine { request ->
                    val body = when {
                        request.url.encodedPath.contains("comment_v5") -> """{"data":[{"id":9900001,"type":"comment","content":"离线评论"}],"paging":{"next":"","is_end":true}}"""
                        request.url.encodedPath.contains("questions") -> """{"id":7000001,"type":"question","title":"实机缓存问题"}"""
                        else -> """{"id":42000001,"type":"answer","content":"实机完整回答","excerpt":"摘要","question":{"id":7000001,"type":"question","title":"实机缓存问题"},"author":{"id":"fixture","name":"测试作者","url":"https://www.zhihu.com/people/fixture","user_type":"people","headline":"","avatar_url":""}}"""
                    }
                    respond(body)
                },
            ) { install(OfflineContentPlugin) { backend = backend(db) } }
            try {
                assertTrue(client.get(answerUrl).bodyAsText().contains("实机完整回答"))
                client.get(questionUrl).bodyAsText()
                client.get(commentsUrl).bodyAsText()
            } finally {
                client.close()
            }
            // One-pixel PNG; stored and read as binary, without opening or capturing an image.
            val png = android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=", android.util.Base64.DEFAULT)
            db.saveResource(imageUrl, png, "image/png")
            db.reading("answer", "42000001", 321, false)
            assertTrue(db.hasDetail("answer:42000001"))
            assertFalse(db.wasRead("answer:42000001"))
            assertNotNull(db.resource(imageUrl))
            println("OFFLINE_DEVICE_PREPARED responses=${db.stats().responses} bytes=${db.stats().bytes}")
        } finally {
            db.close()
        }
    }

    @Test fun replayAfterProcessRestartWithoutNetwork() = runBlocking {
        assertFalse("回放阶段应已关闭网络: ${networkDescription()}", connected())
        val db = OfflineDatabase(context, "device-fixture")
        var networkRequests = 0
        try {
            val client = HttpClient(
                MockEngine {
                    networkRequests++
                    error("断网阶段不应进入网络引擎")
                },
            ) {
                install(OfflineContentPlugin) { backend = backend(db) }
            }
            try {
                assertTrue(client.get(answerUrl).bodyAsText().contains("实机完整回答"))
                assertTrue(client.get(questionUrl).bodyAsText().contains("实机缓存问题"))
                assertTrue(client.get(commentsUrl).bodyAsText().contains("离线评论"))
            } finally {
                client.close()
            }
            assertEquals(0, networkRequests)
            assertEquals(321, db.position("answer:42000001"))
            val resource = requireNotNull(db.resource(imageUrl))
            assertTrue(resource.first.readBytes().size > 20)
            val home = Json.parseToJsonElement(db.homePage(Url("https://api.zhihu.com/topstory/recommend"), "mobile")).jsonObject
            assertEquals(1, home["data"]!!.jsonArray.size)
            assertNotNull(
                com.github.zly2006.zhihu.viewmodel.za
                    .parseMobileHomeFeedDisplayItem(home["data"]!!.jsonArray.single().jsonObject),
            )
            println("OFFLINE_DEVICE_REPLAYED networkRequests=$networkRequests position=${db.position("answer:42000001")}")
        } finally {
            db.close()
        }
    }

    @Test fun updateAfterNetworkReturns() = runBlocking {
        assertTrue("更新阶段设备应恢复联网", connected())
        val db = OfflineDatabase(context, "device-fixture")
        var networkRequests = 0
        try {
            val client = HttpClient(
                MockEngine {
                    networkRequests++
                    respond("""{"id":42000001,"type":"answer","content":"联网后的新正文","question":{"id":7000001,"type":"question","title":"实机缓存问题"}}""")
                },
            ) { install(OfflineContentPlugin) { backend = backend(db) } }
            try {
                assertTrue(client.get(answerUrl).bodyAsText().contains("联网后的新正文"))
            } finally {
                client.close()
            }
            assertEquals(1, networkRequests)
            assertTrue(requireNotNull(db.read(Url(answerUrl), "web")).body.contains("联网后的新正文"))
            assertEquals(321, db.position("answer:42000001"))
            println("OFFLINE_DEVICE_UPDATED networkRequests=$networkRequests")
        } finally {
            db.clear()
            db.close()
        }
    }

    @Test fun automaticPrefetchUsesConfiguredBudgetsWithoutOpeningPages() = runBlocking {
        assertTrue(connected())
        val settings = com.github.zly2006.zhihu.platform
            .androidSettingsStore(context)
        settings.putBoolean(OFFLINE_ENABLED, true)
        settings.putBoolean(OFFLINE_WIFI_ONLY, false)
        settings.putBoolean("loginForRecommendation", false)
        settings.putString("recommendationMode", "server")
        settings.putInt(OFFLINE_QUESTIONS, 1)
        settings.putInt(OFFLINE_ANSWERS, 2)
        settings.putInt(OFFLINE_ARTICLES, 1)
        settings.putInt(OFFLINE_COMMENTS, 2)
        settings.putInt(OFFLINE_REPLIES, 1)

        fun answer(id: String) = """{"id":$id,"type":"answer","content":"自动预取正文$id","excerpt":"预取摘要","question":{"id":7000001,"type":"question","title":"自动预取问题"},"author":{"id":"fixture","name":"测试作者","url":"https://www.zhihu.com/people/fixture","user_type":"people","headline":"","avatar_url":""}}"""
        val article = """{"id":8100001,"type":"article","title":"自动预取文章","content":"文章正文","author":{"id":"fixture","name":"测试作者","url":"https://www.zhihu.com/people/fixture","user_type":"people","headline":"","avatar_url":""}}"""
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val body = when {
                path == "/topstory/recommend" -> {
                    """{"data":[{"type":"feed","target":${answer("42000001")}},{"type":"feed","target":$article}],"paging":{"is_end":true,"next":""}}"""
                }

                path.endsWith("/feeds") -> {
                    """{"data":[{"type":"feed","target":${answer("42000001")}},{"type":"feed","target":${answer("42000002")}}],"paging":{"is_end":true,"next":""}}"""
                }

                path.contains("comment_v5") -> {
                    val id = Regex("[0-9]+")
                        .findAll(path)
                        .last()
                        .value
                        .toLong() * 10
                    """{"data":[{"id":$id,"type":"comment","content":"自动预取评论","child_comment_count":1},{"id":${id + 1},"type":"comment","content":"第二条评论"}],"paging":{"is_end":true,"next":""}}"""
                }

                path.contains("/answers/") -> {
                    answer(path.substringAfterLast('/'))
                }

                path.contains("/articles/") -> {
                    article
                }

                path.contains("/questions/") -> {
                    """{"id":7000001,"type":"question","title":"自动预取问题"}"""
                }

                else -> {
                    error("预取不应调用此接口：$path")
                }
            }
            respond(body)
        }
        com.github.zly2006.zhihu.account.accountHttpClientEngineForTesting = engine
        val db = OfflineDatabase(context, "guest")
        try {
            db.clear()
            OfflineRuntime.initialize(context)
            withTimeout(60_000) {
                while (!OfflineRuntime.status.value.contains("待处理 0")) delay(200)
            }
            val stats = db.stats()
            assertEquals(1, stats.questions)
            assertEquals(2, stats.answers)
            assertEquals(1, stats.articles)
            assertTrue(stats.responses >= 10)
            val comments = db.read(Url(commentsUrl), "web")
            assertNotNull(comments)
            assertEquals(
                2,
                Json
                    .parseToJsonElement(requireNotNull(comments).body)
                    .jsonObject["data"]!!
                    .jsonArray.size,
            )
            println("OFFLINE_DEVICE_AUTOMATIC questions=${stats.questions} answers=${stats.answers} articles=${stats.articles} pending=${stats.pending} responses=${stats.responses}")
        } finally {
            settings.putBoolean(OFFLINE_ENABLED, false)
            OfflineRuntime.pauseAndReschedule()
            db.close()
            com.github.zly2006.zhihu.account.accountHttpClientEngineForTesting = null
        }
    }

    @Test fun automaticCacheReplaysThroughRealAndroidClientWhileOffline() = runBlocking {
        assertFalse(connected())
        val settings = com.github.zly2006.zhihu.platform
            .androidSettingsStore(context)
        settings.putBoolean(OFFLINE_ENABLED, false)
        com.github.zly2006.zhihu.account.accountHttpClientEngineForTesting = null
        com.github.zly2006.zhihu.account
            .androidZhihuAccountStore(context)
        OfflineRuntime.initialize(context)
        val env = com.github.zly2006.zhihu.viewmodel
            .SharedAndroidPaginationEnvironment(context, true)
        try {
            val answer = env.get(answerUrl)
            assertTrue(
                answer.call.request.attributes
                    .getOrNull(OfflineSource) == true,
            )
            assertTrue(answer.bodyAsText().contains("自动预取正文"))
            val feeds = env.fetchJson("https://www.zhihu.com/api/v4/questions/7000001/feeds?limit=20&order=default", "")
            assertEquals(2, requireNotNull(feeds)["data"]!!.jsonArray.size)
            val comments = env.fetchJson(commentsUrl, "")
            assertEquals(2, requireNotNull(comments)["data"]!!.jsonArray.size)
            val home = env.get("https://api.zhihu.com/topstory/recommend")
            assertTrue(
                home.call.request.attributes
                    .getOrNull(OfflineSource) == true,
            )
            assertEquals(
                2,
                Json
                    .parseToJsonElement(home.bodyAsText())
                    .jsonObject["data"]!!
                    .jsonArray.size,
            )
            val mobileHome = env.get("https://api.zhihu.com/topstory/recommend", com.github.zly2006.zhihu.account.ZhihuRequestProfile.Mobile)
            val cards = Json.parseToJsonElement(mobileHome.bodyAsText()).jsonObject["data"]!!.jsonArray
            assertEquals(2, cards.size)
            cards.forEach {
                assertNotNull(
                    com.github.zly2006.zhihu.viewmodel.za
                        .parseMobileHomeFeedDisplayItem(it.jsonObject),
                )
            }
            println("OFFLINE_DEVICE_REAL_CLIENT answer=true questionFeeds=2 comments=2 webHome=2 mobileHome=2")
        } finally {
            env.close()
        }
    }
}
