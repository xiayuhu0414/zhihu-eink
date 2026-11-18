package com.github.zly2006.zhihu.offline

import android.app.Application
import io.ktor.http.Url
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class OfflineDatabaseTest {
    private lateinit var db: OfflineDatabase
    private val options = OfflineOptions(questions = 2, articles = 1)
    private val url = "https://www.zhihu.com/api/v4/answers/42?include=content,excerpt"

    @Before fun setup() {
        db = OfflineDatabase(RuntimeEnvironment.getApplication(), "database-test")
        db.clear()
    }

    @After fun teardown() {
        db.clear()
        db.close()
    }

    @Test fun survivesCloseAndAccountPartitionsDoNotMix() {
        db.save(OfflineResponse(url, "web", """{"id":42,"type":"answer","content":"正文","question":{"type":"question","id":7}}""", 123), emptySet(), options)
        db.close()
        db = OfflineDatabase(RuntimeEnvironment.getApplication(), "database-test")
        assertTrue(db.hasDetail("answer:42"))
        assertEquals(123L, db.read(Url(url), "web")?.savedAt)
        assertNotNull(db.read(Url(url.replace("content,excerpt", "content")), "web"))
        assertNull(db.read(Url(url.replace("content,excerpt", "content,topics")), "web"))
        val other = OfflineDatabase(RuntimeEnvironment.getApplication(), "other-test")
        try {
            other.clear()
            assertNull(other.read(Url(url), "web"))
        } finally {
            other.close()
        }
        assertNull(db.read(Url(url), "mobile"))
    }

    @Test fun inventoryIsBoundedAndDiscoveryDoesNotMarkReadOrRequeueSelectedGroups() {
        val body = """{"data":[{"type":"question","id":1},{"type":"question","id":2},{"type":"question","id":3},{"type":"article","id":8},{"type":"article","id":9}]}"""
        db.save(OfflineResponse("https://api.zhihu.com/topstory/recommend", "web", body, 1), emptySet(), options)
        assertEquals(2, db.selected("question").size)
        assertEquals(1, db.selected("article").size)
        assertFalse(db.wasRead("question:1"))
        repeat(3) { db.task(Long.MAX_VALUE)?.let(db::finish) }
        assertNull(db.task(Long.MAX_VALUE))
        db.save(OfflineResponse("https://api.zhihu.com/topstory/recommend", "web", body, 2), emptySet(), options)
        assertNull(db.task(Long.MAX_VALUE))
    }

    @Test fun interruptedTasksAndReadingPositionsSurviveRestart() {
        db.enqueue("answer", "42", "", 1)
        db.reading("answer", "42", 230, true)
        val task = requireNotNull(db.task(System.currentTimeMillis()))
        db.retry(task)
        db.close()
        db = OfflineDatabase(RuntimeEnvironment.getApplication(), "database-test")
        assertEquals(230, db.position("answer:42"))
        assertTrue(db.wasRead("answer:42"))
        assertNull(db.task(System.currentTimeMillis()))
        assertEquals(1, db.task(Long.MAX_VALUE)?.attempts)
    }

    @Test fun sharedImagesSurviveOneResponseRemoval() {
        val image = "https://pic1.zhimg.com/shared.jpg"
        val body = """{"id":42,"type":"answer","content":"正文"}"""
        db.save(OfflineResponse(url, "web", body, 1), setOf(image), options)
        val second = url.replace("42", "43")
        db.save(OfflineResponse(second, "web", body.replace("42", "43"), 1), setOf(image), options)
        db.saveResource(image, byteArrayOf(1, 2, 3), "image/jpeg")
        db.invalidate(Url(url), "web")
        db.clean(options)
        assertNotNull(db.resource(image))
        db.invalidate(Url(second), "web")
        db.clean(options)
        assertNull(db.resource(image))
    }

    @Test fun replacingFirstPageDoesNotMixOldFollowingPages() {
        val first = "https://www.zhihu.com/api/v4/questions/7/feeds?limit=20&order=default"
        val next = "$first&offset=20"
        val body = """{"data":[],"paging":{"next":"$next","is_end":false}}"""
        db.save(OfflineResponse(first, "web", body, 1), emptySet(), options)
        db.save(OfflineResponse(next, "web", """{"data":[],"paging":{"next":"","is_end":true}}""", 2), emptySet(), options)
        assertTrue(db.sameSnapshot(requireNotNull(db.read(Url(first), "web")), requireNotNull(db.read(Url(next), "web"))))
        db.save(OfflineResponse(first, "web", body, 3), emptySet(), options)
        assertFalse(db.sameSnapshot(requireNotNull(db.read(Url(first), "web")), requireNotNull(db.read(Url(next), "web"))))
    }

    @Test fun cleanupRemovesReadListEntriesAndCommentsButKeepsUnreadAndReadLedger() {
        val first = "https://www.zhihu.com/api/v4/answers/42"
        val unread = "https://www.zhihu.com/api/v4/answers/43"
        db.save(OfflineResponse(first, "web", """{"id":42,"type":"answer","content":"已读"}""", 1), emptySet(), options)
        db.save(OfflineResponse(unread, "web", """{"id":43,"type":"answer","content":"未读"}""", 1), emptySet(), options)
        val list = "https://www.zhihu.com/api/v4/questions/7/feeds"
        db.save(OfflineResponse(list, "web", """{"data":[{"id":42,"type":"answer"},{"id":43,"type":"answer"}]}""", 1), emptySet(), options)
        val comments = "https://www.zhihu.com/api/v4/comment_v5/answers/42/root_comment"
        val replies = "https://www.zhihu.com/api/v4/comment_v5/comment/99/child_comment"
        db.save(OfflineResponse(comments, "web", """{"data":[{"id":99,"type":"comment"}]}""", 1), emptySet(), options)
        db.save(OfflineResponse(replies, "web", """{"data":[{"id":100,"type":"comment"}]}""", 1), emptySet(), options)
        db.reading("answer", "42", 500, true)
        db.meta("active.answer:42", "0")
        db.clean(options, force = true)
        assertNull(db.read(Url(first), "web"))
        assertNull(db.read(Url(comments), "web"))
        assertNull(db.read(Url(replies), "web"))
        assertNotNull(db.read(Url(unread), "web"))
        assertTrue(db.wasRead("answer:42"))
        val remaining = requireNotNull(db.read(Url(list), "web")).body
        assertFalse(remaining.contains("42"))
        assertTrue(remaining.contains("43"))
    }

    @Test fun prefetchCapIsAppliedAfterReadFiltering() {
        db.reading("answer", "1", 10, true)
        val list = "https://www.zhihu.com/api/v4/questions/7/feeds"
        db.save(OfflineResponse(list, "web", """{"data":[{"type":"answer","id":1},{"type":"answer","id":2},{"type":"answer","id":3}]}""", 1, itemLimit = 1), emptySet(), options)
        val body = requireNotNull(db.read(Url(list), "web")).body
        assertTrue(body.contains("\"id\":2"))
        assertFalse(body.contains("\"id\":1"))
        assertFalse(body.contains("\"id\":3"))
    }

    @Test fun homeRemainsReachableAfterRecommendationChangesAndAdvancesUnreadAnswers() {
        fun answer(id: Int) = """{"id":$id,"type":"answer","content":"正文","question":{"id":7,"type":"question","title":"问题标题"}}"""
        db.save(OfflineResponse("https://www.zhihu.com/api/v4/answers/42", "web", answer(42), 1), emptySet(), options)
        db.save(OfflineResponse("https://www.zhihu.com/api/v4/answers/43", "web", answer(43), 2), emptySet(), options)
        val home = Url("https://api.zhihu.com/topstory/recommend")
        db.save(OfflineResponse(home.toString(), "web", """{"data":[{"type":"answer","id":99}]}""", 3), emptySet(), options)
        val first = Json.parseToJsonElement(db.homePage(home, "web")).jsonObject["data"]!!.jsonArray
        assertEquals(1, first.size)
        assertEquals(
            "42",
            first
                .single()
                .jsonObject["target"]!!
                .jsonObject["id"]!!
                .jsonPrimitive.content,
        )
        db.reading("answer", "42", 300, true)
        val next = Json.parseToJsonElement(db.homePage(home, "web")).jsonObject["data"]!!.jsonArray
        assertEquals(
            "43",
            next
                .single()
                .jsonObject["target"]!!
                .jsonObject["id"]!!
                .jsonPrimitive.content,
        )
    }

    @Test fun imageFailuresBackOffWithoutBlockingOtherImagesAndCapacityPreservesExistingFiles() {
        val good = "https://pic1.zhimg.com/good.jpg"
        val bad = "https://pic1.zhimg.com/bad.jpg"
        db.save(OfflineResponse(url, "web", """{"type":"answer","id":42,"content":"正文"}""", 1), setOf(good, bad), options)
        db.resourceFailed(bad)
        assertEquals(listOf(good), db.missingResources(10))
        val base = db.stats().bytes
        db.saveResource(good, byteArrayOf(1, 2, 3), "image/jpeg", base + 3)
        db.saveResource(bad, byteArrayOf(4, 5), "image/jpeg", base + 3)
        assertNotNull(db.resource(good))
        assertNull(db.resource(bad))
    }
}
