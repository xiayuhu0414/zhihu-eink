package com.github.zly2006.zhihu.offline

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal fun offlineHash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

data class OfflineTask(
    val key: String,
    val kind: String,
    val id: String,
    val payload: String,
    val attempts: Int,
)

data class OfflineStats(
    val questions: Int,
    val answers: Int,
    val articles: Int,
    val responses: Int,
    val images: Int,
    val pending: Int,
    val bytes: Long,
    val pendingImages: Int,
)

/** Separate database: upgrading the offline cache never migrates or deletes reading/history databases. */
class OfflineDatabase(
    context: Context,
    accountId: String,
) : SQLiteOpenHelper(
        context,
        File(context.noBackupFilesDir, "offline/${offlineHash(accountId)}/cache.db").apply { parentFile?.mkdirs() }.absolutePath,
        null,
        1,
    ) {
    val directory = File(context.noBackupFilesDir, "offline/${offlineHash(accountId)}/resources").apply { mkdirs() }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE responses (cache_key TEXT PRIMARY KEY, url TEXT NOT NULL, profile TEXT NOT NULL, route TEXT NOT NULL, owner TEXT, body TEXT NOT NULL, bytes INTEGER NOT NULL, is_list INTEGER NOT NULL, saved_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX response_owner ON responses(owner)")
        db.execSQL("CREATE INDEX response_route ON responses(profile,route)")
        db.execSQL("CREATE INDEX response_bytes ON responses(bytes)")
        db.execSQL("CREATE TABLE nodes (node_key TEXT PRIMARY KEY, kind TEXT NOT NULL, id TEXT NOT NULL, question_id TEXT, created_at INTEGER NOT NULL, complete_at INTEGER, read_at INTEGER, position INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX node_kind ON nodes(kind,created_at)")
        db.execSQL("CREATE TABLE dependencies (cache_key TEXT NOT NULL, node_key TEXT NOT NULL, PRIMARY KEY(cache_key,node_key))")
        db.execSQL("CREATE TABLE tasks (task_key TEXT PRIMARY KEY, kind TEXT NOT NULL, id TEXT NOT NULL, payload TEXT NOT NULL, priority INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, available_at INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE resources (url TEXT PRIMARY KEY, name TEXT NOT NULL, mime TEXT NOT NULL, size INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX resource_bytes ON resources(size)")
        db.execSQL("CREATE TABLE resource_refs (cache_key TEXT NOT NULL, url TEXT NOT NULL, PRIMARY KEY(cache_key,url))")
        db.execSQL("CREATE INDEX resource_ref_url ON resource_refs(url)")
        db.execSQL("CREATE TABLE resource_failures (url TEXT PRIMARY KEY, attempts INTEGER NOT NULL, available_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE metadata (name TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        db.execSQL("CREATE INDEX IF NOT EXISTS node_question_inventory ON nodes(question_id,complete_at,read_at)")
    }

    @Synchronized
    fun read(url: Url, profile: String): OfflineResponse? {
        val key = offlineRequestKey(url, profile)
        readableDatabase.rawQuery("SELECT url,profile,body,saved_at FROM responses WHERE cache_key=?", arrayOf(key)).use {
            if (it.moveToFirst()) return OfflineResponse(it.getString(0), it.getString(1), it.getString(2), it.getLong(3))
        }
        // A response with additional include fields can satisfy a narrower request. Never reverse this relation.
        val required = url.parameters["include"]
            .orEmpty()
            .split(',')
            .filter(String::isNotBlank)
            .toSet()
        readableDatabase.rawQuery("SELECT url,profile,body,saved_at FROM responses WHERE profile=? AND route=? ORDER BY saved_at DESC", arrayOf(profile, url.host + url.encodedPath)).use { cursor ->
            while (cursor.moveToNext()) {
                val candidate = Url(cursor.getString(0))
                if (sameRequestExceptInclude(url, candidate) && candidate.parameters["include"]
                        .orEmpty()
                        .split(',')
                        .containsAll(required)
                ) {
                    return OfflineResponse(cursor.getString(0), profile, cursor.getString(2), cursor.getLong(3))
                }
            }
        }
        return null
    }

    private fun sameRequestExceptInclude(a: Url, b: Url): Boolean {
        val firstFeedPage = a.encodedPath.endsWith("/feeds") && a.parameters["offset"].isNullOrEmpty() && b.parameters["offset"].isNullOrEmpty()

        fun parameters(url: Url) = url.parameters
            .entries()
            .filter {
                it.key != "include" && !(firstFeedPage && (it.key == "limit" || (it.key == "order" && it.value == listOf("default"))))
            }.associate { it.key to it.value }
        return a.host == b.host && a.encodedPath == b.encodedPath && parameters(a) == parameters(b)
    }

    @Synchronized
    fun save(response: OfflineResponse, images: Set<String>, options: OfflineOptions) {
        val url = Url(response.url)
        val key = offlineRequestKey(url, response.profile)
        val original = validOfflineResponse(response.body) ?: return
        val data = original["data"] as? JsonArray
        val body = if (data == null) {
            original
        } else {
            JsonObject(
                original + (
                    "data" to JsonArray(
                        data
                            .filter { item -> offlineNodes(item).firstOrNull()?.let { !wasRead(it.key) } != false }
                            .let { if (response.itemLimit == null) it else it.take(response.itemLimit.coerceAtLeast(0)) },
                    )
                ),
            )
        }
        val nodes = offlineNodes(body)
        val oldBytes = readableDatabase.rawQuery("SELECT bytes FROM responses WHERE cache_key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        if (stats().bytes - oldBytes + body.toString().toByteArray().size > options.capacityMb * 1024L * 1024L) return
        val owner = contentOwner(url) ?: Regex("/comment_v5/comment/([0-9]+)")
            .find(url.encodedPath)
            ?.groupValues
            ?.get(1)
            ?.let { meta("commentOwner.$it") }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict(
                "responses",
                null,
                ContentValues().apply {
                    put("cache_key", key)
                    put("url", response.url)
                    put("profile", response.profile)
                    put("route", url.host + url.encodedPath)
                    put("bytes", body.toString().toByteArray().size)
                    put("is_list", if (body["data"] is JsonArray && nodes.isNotEmpty()) 1 else 0)
                    put("owner", owner)
                    put("body", body.toString())
                    put("saved_at", response.savedAt)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            if (body["data"] is JsonArray) {
                val version = meta("expected.${offlineHash(key)}") ?: UUID.randomUUID().toString()
                meta("version.${offlineHash(key)}", version)
                resolveOfflineNext(response.url, body)?.let { next ->
                    val nextUrl = URLBuilder(next).apply { url.parameters["include"]?.let { parameters["include"] = it } }.build()
                    meta("expected.${offlineHash(offlineRequestKey(nextUrl, response.profile))}", version)
                }
            }
            db.delete("dependencies", "cache_key=?", arrayOf(key))
            for (node in nodes) {
                db.insertWithOnConflict(
                    "nodes",
                    null,
                    ContentValues().apply {
                        put("node_key", node.key)
                        put("kind", node.kind)
                        put("id", node.id)
                        put("question_id", node.questionId)
                        put("created_at", response.savedAt)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
                db.insertWithOnConflict(
                    "dependencies",
                    null,
                    ContentValues().apply {
                        put("cache_key", key)
                        put("node_key", node.key)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
                if (node.questionId != null) db.execSQL("UPDATE nodes SET question_id=? WHERE node_key=?", arrayOf(node.questionId, node.key))
            }
            if (owner != null && Regex("/api/v4/(questions|answers|articles)/[0-9]+").matches(url.encodedPath) &&
                (owner.startsWith("question:") || body["content"] is JsonPrimitive)
            ) {
                db.execSQL("UPDATE nodes SET complete_at=? WHERE node_key=?", arrayOf<Any>(response.savedAt, owner))
            }
            db.delete("resource_refs", "cache_key=?", arrayOf(key))
            (if (body == original) images else extractImages(body.toString())).forEach { image ->
                db.insertWithOnConflict(
                    "resource_refs",
                    null,
                    ContentValues().apply {
                        put("cache_key", key)
                        put("url", image)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (owner != null && url.encodedPath.contains("comment_v5")) {
            fun rememberComment(value: JsonElement) {
                when (value) {
                    is JsonObject -> {
                        value["id"]?.jsonPrimitive?.contentOrNull?.let { meta("commentOwner.$it", owner) }
                        (value["child_comments"] as? JsonArray)?.forEach(::rememberComment)
                    }

                    else -> {
                        Unit
                    }
                }
            }
            (body["data"] as? JsonArray)?.forEach(::rememberComment)
        }
        // Discovery is bounded by inventory limits, independent of how many items an API returns.
        nodes.filter { it.kind == "question" || it.kind == "article" }.forEach { admit(it, options) }
        nodes.filter { it.kind == "answer" && it.questionId != null }.forEach { admit(OfflineNode("question", it.questionId!!), options) }
        nodes.filter { it.kind == "answer" && it.questionId == null && !wasRead(it.key) }.take((options.questions - selected("question").size).coerceAtLeast(0)).forEach { enqueue("discoverAnswer", it.id, "", 21) }
        meta("lastSaved", response.savedAt.toString())
    }

    @Synchronized
    fun admit(node: OfflineNode, options: OfflineOptions) {
        val limit = if (node.kind == "question") options.questions else options.articles
        val selected = selected(node.kind)
        if (node.id !in selected && selected.size >= limit) return
        if (limit == 0 || wasRead(node.key)) return
        val isNew = node.id !in selected
        writableDatabase.insertWithOnConflict(
            "metadata",
            null,
            ContentValues().apply {
                put("name", "selected.${node.key}")
                put("value", "1")
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        if (isNew) enqueue(node.kind, node.id, "", if (node.kind == "question") 20 else 18)
    }

    @Synchronized
    fun selected(kind: String): List<String> = buildList {
        readableDatabase.rawQuery("SELECT name FROM metadata WHERE name LIKE ?", arrayOf("selected.$kind:%")).use { while (it.moveToNext()) add(it.getString(0).substringAfter(':')) }
    }

    @Synchronized
    fun prepareTargets(options: OfflineOptions) {
        listOf("question" to options.questions, "article" to options.articles).forEach { (kind, limit) ->
            selected(kind).drop(limit).forEach { id ->
                writableDatabase.delete("metadata", "name=?", arrayOf("selected.$kind:$id"))
                writableDatabase.delete("tasks", "task_key=?", arrayOf("$kind:$id"))
            }
        }
    }

    @Synchronized
    fun questionForAnswer(id: String): String? = readableDatabase.rawQuery("SELECT question_id FROM nodes WHERE node_key=?", arrayOf("answer:$id")).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }

    @Synchronized
    fun unreadAnswerIds(questionId: String): List<String> = buildList {
        readableDatabase.rawQuery("SELECT id FROM nodes WHERE kind='answer' AND question_id=? AND complete_at IS NOT NULL AND read_at IS NULL ORDER BY created_at,id", arrayOf(questionId)).use {
            while (it.moveToNext()) add(it.getString(0))
        }
    }

    private fun preparedAnswerIds(questionId: String): List<String> = meta("questionPreparedIds.$questionId")
        ?.let {
            runCatching { Json.parseToJsonElement(it).jsonArray.map { id -> id.jsonPrimitive.content } }.getOrNull()
        }.orEmpty()

    @Synchronized
    fun questionPrepared(questionId: String, options: OfflineOptions): Boolean {
        if (!hasDetail("question:$questionId")) return false
        if (unreadAnswerIds(questionId).size >= options.answers) return true
        // An exhausted question can legitimately contain fewer answers than the configured target.
        return meta("questionAnswersExhausted.$questionId") == "true" &&
            preparedAnswerIds(questionId).all { wasRead("answer:$it") || hasDetail("answer:$it") }
    }

    @Synchronized
    fun needsComments(owner: String, options: OfflineOptions): Boolean {
        if (options.comments == 0) return false
        val present = readableDatabase.rawQuery("SELECT 1 FROM responses WHERE owner=? AND route LIKE '%/root_comment' LIMIT 1", arrayOf(owner)).use { it.moveToFirst() }
        return !present || (meta("commentsPrepared.$owner")?.toIntOrNull() ?: 0) < options.comments ||
            (meta("repliesPrepared.$owner")?.toIntOrNull() ?: 0) < options.replies
    }

    @Synchronized
    fun enqueueMissingComments(owner: String, options: OfflineOptions) {
        if (needsComments(owner, options)) enqueue("comments", owner, "", 2)
    }

    /** Queue shortages only; having an old timestamp never forces an otherwise complete group to refresh. */
    @Synchronized
    fun queueMissingPreparation(options: OfflineOptions): Boolean {
        val questions = selected("question")
        val articles = selected("article")
        questions.forEach { id ->
            if (!questionPrepared(id, options)) enqueue("question", id, "", 20)
            preparedAnswerIds(id).filter { !wasRead("answer:$it") && !hasDetail("answer:$it") }.take(options.answers).forEach {
                enqueue("answer", it, "", 10)
            }
            if (hasDetail("question:$id")) enqueueMissingComments("question:$id", options)
            unreadAnswerIds(id).take(options.answers).forEach { enqueueMissingComments("answer:$it", options) }
        }
        articles.forEach { id ->
            if (!hasDetail("article:$id")) {
                enqueue("article", id, "", 18)
            } else {
                enqueueMissingComments("article:$id", options)
            }
        }
        return questions.size < options.questions || articles.size < options.articles
    }

    @Synchronized
    fun enqueue(kind: String, id: String, payload: String, priority: Int) {
        val key = "$kind:$id"
        writableDatabase.insertWithOnConflict(
            "tasks",
            null,
            ContentValues().apply {
                put("task_key", key)
                put("kind", kind)
                put("id", id)
                put("payload", payload)
                put("priority", priority)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    @Synchronized
    fun task(now: Long): OfflineTask? = readableDatabase.rawQuery("SELECT task_key,kind,id,payload,attempts FROM tasks WHERE available_at<=? ORDER BY priority DESC,rowid LIMIT 1", arrayOf(now.toString())).use {
        if (it.moveToFirst()) OfflineTask(it.getString(0), it.getString(1), it.getString(2), it.getString(3), it.getInt(4)) else null
    }

    @Synchronized
    fun finish(task: OfflineTask, nextPayload: String? = null) {
        if (nextPayload == null) {
            writableDatabase.delete("tasks", "task_key=?", arrayOf(task.key))
        } else {
            writableDatabase.update(
                "tasks",
                ContentValues().apply {
                    put("payload", nextPayload)
                    put("attempts", 0)
                },
                "task_key=?",
                arrayOf(task.key),
            )
        }
    }

    @Synchronized
    fun retry(task: OfflineTask) {
        writableDatabase.update(
            "tasks",
            ContentValues().apply {
                put("attempts", task.attempts + 1)
                put("available_at", System.currentTimeMillis() + (60_000L shl task.attempts.coerceAtMost(8)))
            },
            "task_key=?",
            arrayOf(task.key),
        )
    }

    @Synchronized
    fun responseFresh(url: String, profile: String, age: Long): Boolean = read(Url(url), profile)?.let { System.currentTimeMillis() - it.savedAt < age } == true

    @Synchronized
    fun meta(name: String): String? = readableDatabase.rawQuery("SELECT value FROM metadata WHERE name=?", arrayOf(name)).use { if (it.moveToFirst()) it.getString(0) else null }

    @Synchronized
    fun meta(name: String, value: String) {
        writableDatabase.insertWithOnConflict(
            "metadata",
            null,
            ContentValues().apply {
                put("name", name)
                put("value", value)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    @Synchronized
    fun position(key: String): Int = readableDatabase.rawQuery("SELECT position FROM nodes WHERE node_key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    @Synchronized
    fun hasDetail(key: String): Boolean = readableDatabase.rawQuery("SELECT complete_at FROM nodes WHERE node_key=?", arrayOf(key)).use { it.moveToFirst() && !it.isNull(0) }

    @Synchronized
    fun sameSnapshot(first: OfflineResponse, next: OfflineResponse): Boolean {
        fun version(response: OfflineResponse) = meta("version.${offlineHash(offlineRequestKey(Url(response.url), response.profile))}")
        return version(first)?.let { it == version(next) } == true
    }

    /** Build a stable home page from ready API details, even after recommendation cursors change. */
    @Synchronized
    fun homePage(url: Url, profile: String): String {
        val token = url.parameters["__offline_snapshot"] ?: UUID.randomUUID().toString()
        val metadataKey = "homeSnapshot.$profile.$token"
        var keys = meta(metadataKey)?.let { runCatching { Json.parseToJsonElement(it) as? JsonArray }.getOrNull() }
        if (keys == null) {
            val groups = linkedMapOf<String, String>()
            readableDatabase.rawQuery("SELECT node_key,question_id,kind FROM nodes WHERE complete_at IS NOT NULL AND read_at IS NULL ORDER BY CASE kind WHEN 'answer' THEN 0 WHEN 'article' THEN 1 ELSE 2 END,created_at,node_key", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val key = cursor.getString(0)
                    val group = if (cursor.getString(2) == "answer" && !cursor.isNull(1)) "question:${cursor.getString(1)}" else key
                    groups.putIfAbsent(group, key)
                }
            }
            keys = JsonArray(groups.values.map(::JsonPrimitive))
            meta(metadataKey, keys.toString())
            writableDatabase.execSQL("DELETE FROM metadata WHERE name LIKE 'homeSnapshot.%' AND rowid NOT IN (SELECT rowid FROM metadata WHERE name LIKE 'homeSnapshot.%' ORDER BY rowid DESC LIMIT 30)")
        }
        val offset = url.parameters["__offline_offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val page = keys.drop(offset).take(20)
        val cards = page.mapNotNull { entry ->
            val key = entry.jsonPrimitive.content
            if (wasRead(key)) return@mapNotNull null
            readableDatabase.rawQuery("SELECT body FROM responses WHERE owner=? AND is_list=0 AND route NOT LIKE '%comment_v5%' ORDER BY saved_at DESC LIMIT 1", arrayOf(key)).use { cursor ->
                if (!cursor.moveToFirst()) null else validOfflineResponse(cursor.getString(0))?.let { offlineHomeCard(key.substringBefore(':'), key.substringAfter(':'), it, profile) }
            }
        }
        val end = offset + page.size >= keys.size
        val next = if (end) {
            ""
        } else {
            URLBuilder(url)
                .apply {
                    parameters["__offline_snapshot"] = token
                    parameters["__offline_offset"] = (offset + page.size).toString()
                }.buildString()
        }
        return buildJsonObject {
            put("data", JsonArray(cards))
            put("offline_cache", true)
            put(
                "paging",
                buildJsonObject {
                    put("is_end", end)
                    put("is_start", offset == 0)
                    put("next", next)
                    put("previous", "")
                    put("totals", keys.size)
                },
            )
            if (end) put("offline_cache_boundary", true)
        }.toString()
    }

    @Synchronized
    fun unreadDetails(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM nodes WHERE kind IN ('answer','article') AND complete_at IS NOT NULL AND read_at IS NULL", null).use {
        it.moveToFirst()
        it.getInt(0)
    }

    @Synchronized
    fun markReadQuestions() {
        selected("article").filter { wasRead("article:$it") }.forEach {
            writableDatabase.delete("metadata", "name=?", arrayOf("selected.article:$it"))
        }
        for (id in selected("question")) {
            val expected = meta("questionPreparedIds.$id")?.let { runCatching { Json.parseToJsonElement(it) as? JsonArray }.getOrNull() } ?: continue
            if (expected.isEmpty()) continue
            if (expected.all { wasRead("answer:${it.jsonPrimitive.content}") }) {
                writableDatabase.execSQL("UPDATE nodes SET read_at=COALESCE(read_at,?) WHERE node_key=?", arrayOf<Any>(System.currentTimeMillis(), "question:$id"))
                writableDatabase.delete("metadata", "name=?", arrayOf("selected.question:$id"))
            }
        }
    }

    @Synchronized
    fun wasRead(key: String): Boolean = readableDatabase.rawQuery("SELECT read_at FROM nodes WHERE node_key=?", arrayOf(key)).use { it.moveToFirst() && !it.isNull(0) }

    @Synchronized
    fun reading(kind: String, id: String, position: Int, read: Boolean) {
        val key = "$kind:$id"
        writableDatabase.insertWithOnConflict(
            "nodes",
            null,
            ContentValues().apply {
                put("node_key", key)
                put("kind", kind)
                put("id", id)
                put("created_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        writableDatabase.execSQL("UPDATE nodes SET position=?,read_at=CASE WHEN ?=1 THEN COALESCE(read_at,?) ELSE read_at END WHERE node_key=?", arrayOf<Any>(position, if (read) 1 else 0, System.currentTimeMillis(), key))
        meta("active.$key", System.currentTimeMillis().toString())
    }

    @Synchronized
    fun resource(url: String): Pair<File, String>? = readableDatabase.rawQuery("SELECT name,mime FROM resources WHERE url=?", arrayOf(url)).use {
        if (!it.moveToFirst()) null else File(directory, it.getString(0)).takeIf(File::isFile)?.let { file -> file to it.getString(1) }
    }

    @Synchronized
    fun saveResource(url: String, bytes: ByteArray, mime: String, capacity: Long = Long.MAX_VALUE) {
        val existing = readableDatabase.rawQuery("SELECT size FROM resources WHERE url=?", arrayOf(url)).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        if (stats().bytes - existing + bytes.size > capacity) return
        val name = offlineHash(url)
        val temporary = File(directory, "$name.tmp")
        temporary.writeBytes(bytes)
        check(temporary.renameTo(File(directory, name)))
        writableDatabase.insertWithOnConflict(
            "resources",
            null,
            ContentValues().apply {
                put("url", url)
                put("name", name)
                put("mime", mime)
                put("size", bytes.size)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        writableDatabase.delete("resource_failures", "url=?", arrayOf(url))
    }

    @Synchronized
    fun missingResources(limit: Int): List<String> = buildList {
        readableDatabase.rawQuery("SELECT DISTINCT r.url FROM resource_refs r LEFT JOIN resources s ON s.url=r.url LEFT JOIN resource_failures f ON f.url=r.url WHERE s.url IS NULL AND (f.url IS NULL OR f.available_at<=?) LIMIT ?", arrayOf(System.currentTimeMillis().toString(), limit.toString())).use { while (it.moveToNext()) add(it.getString(0)) }
    }

    @Synchronized
    fun resourceFailed(url: String) {
        val attempts = readableDatabase.rawQuery("SELECT attempts FROM resource_failures WHERE url=?", arrayOf(url)).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        writableDatabase.insertWithOnConflict(
            "resource_failures",
            null,
            ContentValues().apply {
                put("url", url)
                put("attempts", attempts + 1)
                put("available_at", System.currentTimeMillis() + (60_000L shl attempts.coerceAtMost(10)))
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    @Synchronized
    fun stats(includePendingImages: Boolean = false): OfflineStats {
        fun count(sql: String): Long = readableDatabase.rawQuery(sql, null).use {
            it.moveToFirst()
            it.getLong(0)
        }
        return OfflineStats(
            count("SELECT COUNT(*) FROM nodes WHERE kind='question' AND complete_at IS NOT NULL").toInt(),
            count("SELECT COUNT(*) FROM nodes WHERE kind='answer' AND complete_at IS NOT NULL").toInt(),
            count("SELECT COUNT(*) FROM nodes WHERE kind='article' AND complete_at IS NOT NULL").toInt(),
            count("SELECT COUNT(*) FROM responses").toInt(),
            count("SELECT COUNT(*) FROM resources").toInt(),
            count("SELECT COUNT(*) FROM tasks").toInt(),
            count("SELECT COALESCE(SUM(bytes),0) FROM responses") + count("SELECT COALESCE(SUM(size),0) FROM resources"),
            if (includePendingImages) count("SELECT COUNT(DISTINCT r.url) FROM resource_refs r LEFT JOIN resources s ON s.url=r.url WHERE s.url IS NULL").toInt() else 0,
        )
    }

    @Synchronized
    fun invalidate(url: Url, profile: String) {
        val owner = contentOwner(url)
        if (owner != null && Regex("/api/v4/(questions|answers|articles)/[0-9]+").matches(url.encodedPath)) {
            val keys = mutableListOf<String>()
            readableDatabase.rawQuery("SELECT cache_key FROM responses WHERE owner=?", arrayOf(owner)).use { while (it.moveToNext()) keys += it.getString(0) }
            keys.forEach(::deleteResponse)
            writableDatabase.execSQL("UPDATE nodes SET complete_at=NULL WHERE node_key=?", arrayOf(owner))
        } else {
            deleteResponse(offlineRequestKey(url, profile))
        }
    }

    private fun deleteResponse(key: String) {
        writableDatabase.delete("responses", "cache_key=?", arrayOf(key))
        writableDatabase.delete("dependencies", "cache_key=?", arrayOf(key))
        writableDatabase.delete("resource_refs", "cache_key=?", arrayOf(key))
    }

    /** Never remove a detail while a persisted list still references it. Lists expire as a unit. */
    @Synchronized
    fun clean(options: OfflineOptions, force: Boolean = false): Int {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val cutoff = now - if (force) 0L else options.readHours * 3_600_000L
        val removable = mutableListOf<String>()
        db.rawQuery("SELECT node_key FROM nodes WHERE read_at IS NOT NULL AND read_at<=?", arrayOf(cutoff.toString())).use {
            while (it.moveToNext()) {
                val key = it.getString(0)
                if (now - (meta("active.$key")?.toLongOrNull() ?: 0L) > 5 * 60_000) removable += key
            }
        }
        var deleted = 0
        db.beginTransaction()
        try {
            // Remove read entries from stored lists first. The UI keeps its already-rendered snapshot.
            val lists = mutableListOf<Pair<String, JsonObject>>()
            if (removable.isNotEmpty()) {
                db.rawQuery("SELECT cache_key,body FROM responses WHERE is_list=1", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val original = validOfflineResponse(cursor.getString(1)) ?: continue
                        val data = original["data"] as? JsonArray ?: continue
                        val remaining = data.filter { item -> offlineNodes(item).firstOrNull()?.key !in removable }
                        if (remaining.size != data.size) lists += cursor.getString(0) to JsonObject(original + ("data" to JsonArray(remaining)))
                    }
                }
            }
            lists.forEach { (key, body) ->
                db.update(
                    "responses",
                    ContentValues().apply {
                        put("body", body.toString())
                        put("bytes", body.toString().toByteArray().size)
                    },
                    "cache_key=?",
                    arrayOf(key),
                )
                db.delete("dependencies", "cache_key=?", arrayOf(key))
                offlineNodes(body).forEach { node ->
                    db.insertWithOnConflict(
                        "dependencies",
                        null,
                        ContentValues().apply {
                            put("cache_key", key)
                            put("node_key", node.key)
                        },
                        SQLiteDatabase.CONFLICT_IGNORE,
                    )
                }
                db.delete("resource_refs", "cache_key=?", arrayOf(key))
                extractImages(body.toString()).forEach { image ->
                    db.insertWithOnConflict(
                        "resource_refs",
                        null,
                        ContentValues().apply {
                            put("cache_key", key)
                            put("url", image)
                        },
                        SQLiteDatabase.CONFLICT_IGNORE,
                    )
                }
            }
            for (node in removable.sortedBy { if (it.startsWith("question:")) 1 else 0 }) {
                // Once no list references the item, its detail and comments can be reclaimed safely.
                val references = db.rawQuery("SELECT COUNT(*) FROM dependencies d JOIN responses r ON r.cache_key=d.cache_key WHERE d.node_key=? AND (r.owner IS NULL OR r.owner<>?)", arrayOf(node, node)).use {
                    it.moveToFirst()
                    it.getInt(0)
                }
                if (references > 0) continue
                val keys = mutableListOf<String>()
                db.rawQuery("SELECT cache_key FROM responses WHERE owner=?", arrayOf(node)).use { while (it.moveToNext()) keys += it.getString(0) }
                keys.forEach(::deleteResponse)
                db.execSQL("UPDATE nodes SET complete_at=NULL WHERE node_key=?", arrayOf(node))
                db.delete("metadata", "name=?", arrayOf("selected.$node"))
                db.delete("tasks", "task_key=?", arrayOf(node))
                deleted += keys.size
            }
            db.delete("nodes", "read_at<? AND complete_at IS NULL AND node_key NOT IN (SELECT node_key FROM dependencies)", arrayOf((now - 30L * 86_400_000).toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        val unused = mutableListOf<Pair<String, String>>()
        db.rawQuery("SELECT url,name FROM resources WHERE url NOT IN (SELECT url FROM resource_refs)", null).use { while (it.moveToNext()) unused += it.getString(0) to it.getString(1) }
        unused.forEach { (url, name) ->
            File(directory, name).delete()
            db.delete("resources", "url=?", arrayOf(url))
        }
        return deleted
    }

    @Synchronized
    fun clear() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            listOf("responses", "dependencies", "nodes", "tasks", "resources", "resource_refs", "resource_failures", "metadata").forEach { db.delete(it, null, null) }
            directory.listFiles()?.forEach(File::delete)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
