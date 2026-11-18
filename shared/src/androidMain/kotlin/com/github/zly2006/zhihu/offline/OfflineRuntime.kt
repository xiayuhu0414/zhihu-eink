package com.github.zly2006.zhihu.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.github.zly2006.zhihu.account.ZhihuRequestProfile
import com.github.zly2006.zhihu.account.androidZhihuAccountStore
import com.github.zly2006.zhihu.data.zhihuContentDetailInclude
import com.github.zly2006.zhihu.navigation.Article
import com.github.zly2006.zhihu.navigation.ArticleType
import com.github.zly2006.zhihu.navigation.Question
import com.github.zly2006.zhihu.navigation.zhihuQuestionFeedsUrl
import com.github.zly2006.zhihu.platform.androidSettingsStore
import com.github.zly2006.zhihu.viewmodel.SharedAndroidPaginationEnvironment
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

object OfflineRuntime {
    private var applicationContext: Context? = null
    private val stores = mutableMapOf<String, OfflineDatabase>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runLock = Mutex()
    private var foregroundJob: Job? = null

    @Volatile private var runningJob: Job? = null
    private var lastForeground = 0L
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    val status = MutableStateFlow("尚未准备内容")
    private val resourceClient by lazy {
        HttpClient {
            followRedirects = true
            install(HttpTimeout) {
                requestTimeoutMillis = 20_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 10_000
            }
        }
    }
    internal val context: Context? get() = applicationContext

    internal fun options() = OfflineOptions.from(androidSettingsStore(checkNotNull(applicationContext)))

    fun initialize(context: Context) {
        if (applicationContext != null) return
        applicationContext = context.applicationContext
        scope.launch {
            reschedule()
            val manager = context.getSystemService(ConnectivityManager::class.java)
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: android.net.Network, capabilities: NetworkCapabilities) {
                    if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) onForeground()
                }
            }.also { manager.registerDefaultNetworkCallback(it) }
            androidZhihuAccountStore(context).accountsState.map { it.activeAccountId to it.accounts.map { account -> account.id }.toSet() }.distinctUntilChanged().collect { (_, accountIds) ->
                foregroundJob?.cancelAndJoin()
                // Remove partitions of accounts deleted through any account-management path.
                val valid = accountIds + "guest"
                synchronized(stores) {
                    stores.keys.filter { it !in valid }.toList().forEach { id ->
                        stores.remove(id)?.let {
                            it.clear()
                            it.close()
                        }
                    }
                }
                val validDirectories = valid.map(::offlineHash).toSet()
                context.noBackupFilesDir
                    .resolve("offline")
                    .listFiles()
                    ?.filter { it.isDirectory && it.name !in validDirectories }
                    ?.forEach { it.deleteRecursively() }
                onForeground(force = true)
            }
        }
    }

    internal fun accountId(): String {
        val context = checkNotNull(applicationContext)
        val settings = androidSettingsStore(context)
        return if (!settings.getBoolean("loginForRecommendation", true)) {
            "guest"
        } else {
            androidZhihuAccountStore(context).accountsState.value.activeAccountId ?: "guest"
        }
    }

    internal fun store(id: String = accountId()): OfflineDatabase = synchronized(stores) {
        stores.getOrPut(id) { OfflineDatabase(checkNotNull(applicationContext), id) }
    }

    fun connected(): Boolean = applicationContext?.let(::hasOfflineNetwork) ?: true

    private fun allowed(): Boolean {
        if (!connected() || !options().enabled) return false
        val manager = applicationContext?.getSystemService(ConnectivityManager::class.java) ?: return false
        return !options().wifiOnly || !manager.isActiveNetworkMetered
    }

    fun onForeground(force: Boolean = false) {
        if (applicationContext == null) return
        if (!allowed()) {
            status.value = when {
                !options().enabled -> "自动准备已暂停，已有内容可继续使用"
                !connected() -> "等待联网，已有内容可继续使用"
                else -> "等待 Wi-Fi，已有内容可继续使用"
            }
            return
        }
        val now = System.currentTimeMillis()
        if ((!force && now - lastForeground < 15 * 60_000) || foregroundJob?.isActive == true) return
        lastForeground = now
        foregroundJob = scope.launch { runCatching { fill() } }
    }

    fun pauseAndReschedule() {
        val previous = foregroundJob
        runningJob?.cancel()
        reschedule()
        scope.launch {
            previous?.cancelAndJoin()
            onForeground(force = true)
        }
    }

    fun reschedule() {
        val context = applicationContext ?: return
        val manager = WorkManager.getInstance(context)
        if (!options().enabled) {
            manager.cancelUniqueWork("offline-content")
            status.value = "自动准备已暂停，已有内容可继续使用"
            return
        }
        val request = PeriodicWorkRequestBuilder<OfflineContentWorker>(1, TimeUnit.HOURS)
            .setConstraints(
                Constraints
                    .Builder()
                    .setRequiredNetworkType(if (options().wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            ).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        manager.enqueueUniquePeriodicWork("offline-content", ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    internal fun backend(id: String): OfflineBackend = object : OfflineBackend {
        override fun connected() = OfflineRuntime.connected()

        override fun active() = applicationContext != null && accountId() == id

        override suspend fun read(url: Url, profile: String) = withContext(Dispatchers.IO) {
            store(id).read(url, profile) ?: if (isOfflineHomeUrl(url) && store(id).unreadDetails() > 0) OfflineResponse(url.toString(), profile, "{\"data\":[]}", 0) else null
        }

        override suspend fun save(response: OfflineResponse, prefetch: Boolean) = withContext(Dispatchers.IO) {
            if (!active()) return@withContext
            val db = store(id)
            // Preserve unread data when full. Foreground network requests still succeed.
            db.save(response, extractImages(response.body), options())
        }

        override suspend fun project(response: OfflineResponse) = withContext(Dispatchers.IO) {
            val db = store(id)
            if (isOfflineHomeUrl(Url(response.url))) return@withContext db.homePage(Url(response.url), response.profile)
            val original = validOfflineResponse(response.body) ?: return@withContext response.body
            val next = resolveOfflineNext(response.url, original)
            val include = Url(response.url).parameters["include"]
            val nextRequest = next?.let { URLBuilder(it).apply { if (!include.isNullOrEmpty()) parameters["include"] = include }.build() }
            val nextAvailable = nextRequest?.let { db.read(it, response.profile) }?.let { db.sameSnapshot(response, it) } == true
            projectOfflinePage(response.body, { db.hasDetail(it.key) }, nextAvailable)
        }

        override suspend fun invalidate(url: Url, profile: String) = withContext(Dispatchers.IO) { store(id).invalidate(url, profile) }
    }

    internal suspend fun fill() = runLock.withLock {
        if (!allowed()) return@withLock
        runningJob = currentCoroutineContext()[Job]
        val id = accountId()
        val db = store(id)
        val env = SharedAndroidPaginationEnvironment(checkNotNull(applicationContext), true)
        try {
            val options = options()
            val deadline = System.currentTimeMillis() + 8 * 60_000
            db.prepareTargets(options)
            db.markReadQuestions()
            if (db.unreadDetails() > 0) db.clean(options)
            status.value = "正在准备推荐内容"
            try {
                seed(env, db, options, deadline)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (error is io.ktor.client.plugins.ResponseException && error.response.status.value in setOf(401, 403, 429)) throw error
                // Existing persisted tasks remain useful if only the recommendation endpoint is unavailable.
            }
            var completed = 0
            while (allowed() && accountId() == id && System.currentTimeMillis() < deadline) {
                currentCoroutineContext().ensureActive()
                if (db.stats().bytes >= options.capacityMb * 1024L * 1024L) {
                    status.value = "缓存空间已满，暂停补充"
                    break
                }
                val task = db.task(System.currentTimeMillis()) ?: break
                try {
                    executeTask(env, db, task, options)
                    completed++
                    if (completed % 5 == 0) downloadImages(db, id, 4, deadline)
                    status.value = "正在准备内容，已处理 $completed 项"
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    db.retry(task)
                    val code = (error as? io.ktor.client.plugins.ResponseException)?.response?.status?.value
                    if (code == 401 || code == 403 || code == 429) {
                        status.value = "接口暂不可用，稍后继续"
                        break
                    }
                }
                delay(350)
            }
            if (accountId() != id) return@withLock
            downloadImages(db, id, 40, deadline)
            if (completed > 0 && accountId() == id && connected()) db.clean(options)
            val stats = db.stats(includePendingImages = true)
            status.value = if (stats.bytes >= options.capacityMb * 1024L * 1024L) {
                "缓存空间已满，暂停补充"
            } else {
                "问题 ${stats.questions} · 回答 ${stats.answers} · 文章 ${stats.articles} · 图片 ${stats.images}（待补 ${stats.pendingImages}） · 待处理 ${stats.pending} · ${stats.bytes / 1024 / 1024} MB"
            }
        } catch (error: Exception) {
            if (error !is CancellationException) {
                status.value = when (error) {
                    is io.ktor.client.plugins.ResponseException -> "接口暂不可用（${error.response.status.value}），稍后重试"
                    is java.io.IOException -> "网络请求失败，稍后重试"
                    else -> "准备暂停，下次联网继续"
                }
            }
            throw error
        } finally {
            runningJob = null
            env.close()
        }
    }

    private suspend fun fetch(env: SharedAndroidPaginationEnvironment, url: String, include: String = "", profile: ZhihuRequestProfile = ZhihuRequestProfile.Web, itemLimit: Int? = null): JsonObject {
        check(isOfflineContentUrl(Url(url))) { "预取仅允许已审核的内容接口" }
        val response = withTimeoutOrNull(20_000) {
            env.get(url, profile) {
                attributes.put(OfflinePrefetch, true)
                if (itemLimit != null) attributes.put(OfflineItemLimit, itemLimit)
                if (include.isNotEmpty()) this.url.parameters["include"] = include
            }
        } ?: throw java.io.IOException("内容预取超时")
        if (response.status != HttpStatusCode.OK) {
            throw io.ktor.client.plugins
                .ClientRequestException(response, "内容预取失败")
        }
        return validOfflineResponse(response.bodyAsText()) ?: error("接口未返回有效内容")
    }

    private suspend fun seed(env: SharedAndroidPaginationEnvironment, db: OfflineDatabase, options: OfflineOptions, deadline: Long) {
        // Current recommendation mode owns the candidate source; mobile and web caches remain distinct.
        val settings = androidSettingsStore(checkNotNull(applicationContext))
        if (options.questions == 0 && options.articles == 0) return
        val mode = settings.getString("recommendationMode", "mixed")
        val profiles = when (mode) {
            "android" -> listOf(ZhihuRequestProfile.Mobile)
            "mixed" -> listOf(ZhihuRequestProfile.Web, ZhihuRequestProfile.Mobile)
            "local" -> emptyList()
            else -> listOf(ZhihuRequestProfile.Web)
        }
        for (profile in profiles) {
            var next: String? = "https://api.zhihu.com/topstory/recommend"
            val seen = mutableSetOf<String>()
            for (page in 0 until 10) {
                if (!allowed() || next == null || !seen.add(next) || System.currentTimeMillis() >= deadline) break
                val response = fetch(env, next, if (profile == ZhihuRequestProfile.Mobile) "" else OFFLINE_LIST_INCLUDE, profile)
                next = resolveOfflineNext(next, response)
                if (db.selected("question").size >= options.questions && db.selected("article").size >= options.articles) break
            }
        }
        // Refresh selected groups, but task bodies skip recently updated resources.
        db.selected("question").forEach { db.enqueue("question", it, "", 20) }
        db.selected("article").forEach { db.enqueue("article", it, "", 18) }
    }

    private suspend fun executeTask(env: SharedAndroidPaginationEnvironment, db: OfflineDatabase, task: OfflineTask, options: OfflineOptions) {
        fun selected(kind: String, id: String): Boolean = when (kind) {
            "question", "article" -> id in db.selected(kind)
            "answer" -> db.questionForAnswer(id) in db.selected("question")
            else -> false
        }
        val wanted = when (task.kind) {
            "discoverAnswer" -> db.selected("question").size < options.questions && !db.wasRead("answer:${task.id}")
            "question", "article", "answer" -> selected(task.kind, task.id)
            "comments" -> options.comments > 0 && task.id.substringBefore(':').let { selected(it, task.id.substringAfter(':')) }
            "replies" -> options.replies > 0 && db.meta("commentOwner.${task.id.substringBefore(':')}")?.let { selected(it.substringBefore(':'), it.substringAfter(':')) } == true
            else -> false
        }
        if (!wanted) {
            db.finish(task)
            return
        }
        val state = runCatching { Json.parseToJsonElement(task.payload) as? JsonObject }.getOrNull()
        when (task.kind) {
            "question" -> {
                val detail = "https://www.zhihu.com/api/v4/questions/${task.id}"
                if (!db.responseFresh(detail, "web", 6 * 3_600_000L)) fetch(env, detail, zhihuContentDetailInclude(Question(task.id.toLong())))
                val url = state?.get("url")?.jsonPrimitive?.content ?: zhihuQuestionFeedsUrl(task.id, 20, "default")
                val count = state?.get("count")?.jsonPrimitive?.intOrNull ?: 0
                val seen = (state?.get("seen") as? JsonArray).orEmpty().map { it.jsonPrimitive.content }.toMutableSet()
                val response = fetch(env, url, OFFLINE_LIST_INCLUDE, itemLimit = (options.answers - count).coerceAtLeast(0))
                val pageAnswers = offlineNodes(response).filter { it.kind == "answer" }.distinctBy { it.id }
                val answers = pageAnswers.filter { !db.wasRead(it.key) && it.id !in seen }.take((options.answers - count).coerceAtLeast(0))
                seen += answers.map { it.id }
                answers.forEachIndexed { index, answer -> db.enqueue("answer", answer.id, "", if (index == 0 && count == 0) 22 else 10) }
                val next = resolveOfflineNext(url, response)
                val newCount = count + answers.size
                val pages = (state?.get("pages")?.jsonPrimitive?.intOrNull ?: 0) + 1
                if (next != null && next != url && pageAnswers.isNotEmpty() && newCount < options.answers && pages < 100) {
                    db.finish(
                        task,
                        buildJsonObject {
                            put("url", next)
                            put("count", newCount)
                            put("pages", pages)
                            put("seen", JsonArray(seen.map(::JsonPrimitive)))
                        }.toString(),
                    )
                } else {
                    db.finish(task)
                    db.meta("questionPreparedIds.${task.id}", JsonArray(seen.map(::JsonPrimitive)).toString())
                    if (options.comments > 0) db.enqueue("comments", "question:${task.id}", "", 2)
                }
            }

            "answer", "article", "discoverAnswer" -> {
                val kind = if (task.kind == "discoverAnswer") "answer" else task.kind
                val url = "https://www.zhihu.com/api/v4/${kind}s/${task.id}"
                val destination = Article(id = task.id.toLong(), type = if (kind == "answer") ArticleType.Answer else ArticleType.Article)
                if (!db.responseFresh(url, "web", 24 * 3_600_000L)) fetch(env, url, zhihuContentDetailInclude(destination))
                db.finish(task)
                if (options.comments > 0) db.enqueue("comments", "$kind:${task.id}", "", 2)
            }

            "comments", "replies" -> {
                val parts = task.id.split(':')
                val initial = if (task.kind == "comments") {
                    "https://www.zhihu.com/api/v4/comment_v5/${parts[0]}s/${parts[1]}/root_comment?order_by=score"
                } else {
                    "https://www.zhihu.com/api/v4/comment_v5/comment/${parts[0]}/child_comment"
                }
                val url = state?.get("url")?.jsonPrimitive?.content ?: initial
                val count = state?.get("count")?.jsonPrimitive?.intOrNull ?: 0
                val limit = if (task.kind == "comments") options.comments else parts.getOrNull(1)?.toIntOrNull() ?: options.replies
                val response = fetch(env, url, OFFLINE_LIST_INCLUDE, itemLimit = (limit - count).coerceAtLeast(0))
                val comments = (response["data"] as? JsonArray).orEmpty().take((limit - count).coerceAtLeast(0))
                var budget = state?.get("replyRemaining")?.jsonPrimitive?.intOrNull ?: options.replies
                if (task.kind == "comments" && options.replies > 0) {
                    val replyOwners = comments.mapNotNull { it as? JsonObject }.filter { (it["child_comment_count"]?.jsonPrimitive?.intOrNull ?: 0) > 0 }
                    replyOwners.forEach { comment ->
                        if (budget > 0) {
                            val amount = minOf(budget, comment["child_comment_count"]?.jsonPrimitive?.intOrNull ?: 0)
                            db.enqueue("replies", "${comment["id"]!!.jsonPrimitive.content}:$amount", "", 1)
                            budget -= amount
                        }
                    }
                }
                val next = resolveOfflineNext(url, response)
                val newCount = count + comments.size
                if (next != null && next != url && comments.isNotEmpty() && newCount < limit) {
                    db.finish(
                        task,
                        buildJsonObject {
                            put("url", next)
                            put("count", newCount)
                            put("replyRemaining", budget)
                        }.toString(),
                    )
                } else {
                    db.finish(task)
                }
            }

            else -> {
                db.finish(task)
            }
        }
    }

    private suspend fun downloadImages(db: OfflineDatabase, account: String, limit: Int, deadline: Long) {
        for (url in db.missingResources(limit)) {
            if (!allowed() || accountId() != account || System.currentTimeMillis() >= deadline) return
            try {
                val response = resourceClient.get(url)
                val type = response
                    .contentType()
                    ?.withoutParameters()
                    ?.toString()
                    .orEmpty()
                if (response.status == HttpStatusCode.OK && type.startsWith("image/") && (response.contentLength() ?: 0) <= 15 * 1024 * 1024) {
                    val channel = response.bodyAsChannel()
                    val bytes = channel.readRemaining(15 * 1024 * 1024L + 1).readByteArray()
                    channel.cancel(null)
                    if (bytes.size <= 15 * 1024 * 1024 && accountId() == account) {
                        db.saveResource(url, bytes, type, options().capacityMb * 1024L * 1024L)
                    } else {
                        db.resourceFailed(url)
                    }
                } else {
                    db.resourceFailed(url)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                db.resourceFailed(url)
            }
        }
    }

    fun clear(readOnly: Boolean) {
        val id = accountId()
        foregroundJob?.cancel()
        runningJob?.cancel()
        scope.launch {
            runLock.withLock {
                if (accountId() != id) return@withLock
                if (readOnly) {
                    if (connected()) store().clean(options(), force = true)
                } else {
                    store().clear()
                }
                status.value = if (readOnly) "已清理可安全移除的已读内容" else "离线缓存已清空"
            }
        }
    }

    internal fun forget(id: String) {
        scope.launch {
            runLock.withLock {
                val db = synchronized(stores) { stores.remove(id) }
                db?.close()
                applicationContext?.noBackupFilesDir?.resolve("offline/${offlineHash(id)}")?.deleteRecursively()
            }
        }
    }
}

class OfflineContentWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        OfflineRuntime.initialize(applicationContext)
        OfflineRuntime.fill()
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        Result.retry()
    }
}

internal fun extractImages(body: String): Set<String> {
    val found = linkedSetOf<String>()

    fun add(raw: String) {
        val url = raw.replace("&amp;", "&").let { if (it.startsWith("//")) "https:$it" else it }
        val parsed = runCatching { Url(url) }.getOrNull() ?: return
        if (parsed.protocol == URLProtocol.HTTPS && (parsed.host == "zhimg.com" || parsed.host.endsWith(".zhimg.com") || (parsed.host == "www.zhihu.com" && parsed.encodedPath == "/equation")) &&
            !Regex("\\.(mp4|m3u8|webm|m4s|ts|mov)$", RegexOption.IGNORE_CASE).containsMatchIn(parsed.encodedPath)
        ) {
            found += url
        }
    }

    fun visit(value: JsonElement) {
        when (value) {
            is JsonObject -> value.forEach { (key, item) ->
                if (item is JsonPrimitive && item.isString) {
                    val text = item.content
                    if (key in setOf("content", "detail", "excerpt")) {
                        Jsoup.parseBodyFragment(text).select("img").forEach { image ->
                            listOf("src", "data-original", "data-actualsrc").forEach { attribute -> add(image.attr(attribute)) }
                        }
                    } else if (key.contains("avatar") || key in setOf("image_url", "thumbnail", "thumbnail_url", "cover_url") ||
                        (key == "url" && Regex("\\.(jpg|jpeg|png|webp|gif|svg|avif)(?:\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(text))
                    ) {
                        add(text)
                    }
                } else {
                    visit(item)
                }
            }

            is JsonArray -> value.forEach(::visit)

            else -> Unit
        }
    }
    runCatching { visit(Json.parseToJsonElement(body)) }
    return found
}

actual fun HttpClientConfig<*>.installOfflineContentCache(accountId: String) {
    if (OfflineRuntime.context != null) install(OfflineContentPlugin) { backend = OfflineRuntime.backend(accountId) }
}

actual fun forgetOfflineAccount(accountId: String) {
    OfflineRuntime.forget(accountId)
}

actual fun offlineAccountKey(): String = if (OfflineRuntime.context == null) "" else OfflineRuntime.accountId()

actual suspend fun offlineReadingPosition(account: String, kind: String, id: String): Int = withContext(Dispatchers.IO) {
    if (OfflineRuntime.context == null || account != OfflineRuntime.accountId()) 0 else OfflineRuntime.store(account).position("$kind:$id")
}

actual suspend fun recordOfflineReading(account: String, kind: String, id: String, position: Int, read: Boolean) = withContext(Dispatchers.IO) {
    if (OfflineRuntime.context != null && account == OfflineRuntime.accountId()) OfflineRuntime.store(account).reading(kind, id, position, read)
}
