package com.github.zly2006.zhihu.offline

import com.github.zly2006.zhihu.navigation.Article
import com.github.zly2006.zhihu.navigation.ArticleType
import com.github.zly2006.zhihu.navigation.Question
import com.github.zly2006.zhihu.navigation.resolveContent
import com.github.zly2006.zhihu.platform.SettingsStore
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.decodeURLPart
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

const val OFFLINE_ENABLED = "offline.enabled"
const val OFFLINE_WIFI_ONLY = "offline.wifiOnly"
const val OFFLINE_QUESTIONS = "offline.questions"
const val OFFLINE_ANSWERS = "offline.answersPerQuestion"
const val OFFLINE_ARTICLES = "offline.articles"
const val OFFLINE_COMMENTS = "offline.comments"
const val OFFLINE_REPLIES = "offline.replies"
const val OFFLINE_CAPACITY_MB = "offline.capacityMb"
const val OFFLINE_READ_HOURS = "offline.readHours"
const val OFFLINE_LIST_INCLUDE = "data[*].content,excerpt,headline,target.author.badge_v2"

data class OfflineOptions(
    val enabled: Boolean = true,
    val wifiOnly: Boolean = true,
    val questions: Int = 100,
    val answers: Int = 50,
    val articles: Int = 50,
    val comments: Int = 20,
    val replies: Int = 10,
    val capacityMb: Int = 2048,
    val readHours: Int = 24,
) {
    companion object {
        fun from(settings: SettingsStore) = OfflineOptions(
            enabled = settings.getBoolean(OFFLINE_ENABLED, true),
            wifiOnly = settings.getBoolean(OFFLINE_WIFI_ONLY, true),
            questions = settings.getInt(OFFLINE_QUESTIONS, 100).coerceIn(0, 1000),
            answers = settings.getInt(OFFLINE_ANSWERS, 50).coerceIn(1, 500),
            articles = settings.getInt(OFFLINE_ARTICLES, 50).coerceIn(0, 1000),
            comments = settings.getInt(OFFLINE_COMMENTS, 20).coerceIn(0, 500),
            replies = settings.getInt(OFFLINE_REPLIES, 10).coerceIn(0, 200),
            capacityMb = settings.getInt(OFFLINE_CAPACITY_MB, 2048).coerceIn(128, 16384),
            readHours = settings.getInt(OFFLINE_READ_HOURS, 24).coerceIn(0, 720),
        )
    }
}

/** Only audited content reads. Authentication, mutations, telemetry and video are excluded. */
fun isOfflineContentUrl(url: Url): Boolean {
    if (url.protocol.name != "https" || url.host !in setOf("www.zhihu.com", "api.zhihu.com")) return false
    val path = url.encodedPath
    return path == "/topstory/recommend" || path == "/api/v3/feed/topstory/recommend" ||
        Regex("/api/v4/(questions/[0-9]+(/feeds|/answers)?|answers/[0-9]+|articles/[0-9]+)").matches(path) ||
        Regex("/api/v4/comment_v5/(questions|answers|articles)/[0-9]+/root_comment").matches(path) ||
        Regex("/api/v4/comment_v5/comment/[0-9]+(/child_comment)?").matches(path)
}

/** Preserve all semantic parameters; only parameter order is normalized. Never store credentials. */
fun offlineRequestKey(url: Url, profile: String): String = buildString {
    append("v1|")
        .append(profile)
        .append('|')
        .append(url.protocol.name)
        .append("://")
        .append(url.host)
    append(':').append(url.port).append(url.encodedPath)
    url.parameters.entries().sortedBy { it.key }.forEach { (name, values) ->
        values.forEach { value ->
            append('|')
                .append(name.length)
                .append(':')
                .append(name)
                .append('=')
                .append(value.length)
                .append(':')
                .append(value)
        }
    }
}

fun contentOwner(url: Url): String? {
    val match = Regex("/api/v4/(?:comment_v5/)?(questions|answers|articles)/([0-9]+)").find(url.encodedPath) ?: return null
    return match.groupValues[1].removeSuffix("s") + ":" + match.groupValues[2]
}

@Serializable
data class OfflineNode(
    val kind: String,
    val id: String,
    val questionId: String? = null,
) {
    val key: String get() = "$kind:$id"
}

/** Discover content only, without invoking any UI or read-history side effects. */
fun offlineNodes(json: JsonElement): List<OfflineNode> {
    val found = linkedMapOf<String, OfflineNode>()

    fun visit(value: JsonElement) {
        when (value) {
            is JsonObject -> {
                val kind = value["type"]?.jsonPrimitive?.contentOrNull
                if (kind == "ComponentCard") {
                    val route = (value["action"] as? JsonObject)
                        ?.get("parameter")
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.substringAfter("route_url=")
                        ?.decodeURLPart()
                    val destination = route?.let(::resolveContent)
                    val node = when (destination) {
                        is Article -> OfflineNode(if (destination.type == ArticleType.Answer) "answer" else "article", destination.id.toString(), route.let { Regex("/question/([0-9]+)/answer/").find(it)?.groupValues?.get(1) })
                        is Question -> OfflineNode("question", destination.questionId.toString())
                        else -> null
                    }
                    if (node != null) found[node.key] = node
                }
                val id = value["id"]?.jsonPrimitive?.contentOrNull
                if (kind in setOf("question", "answer", "article") && id?.all(Char::isDigit) == true && id.isNotEmpty()) {
                    val question = (value["question"] as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
                    val node = OfflineNode(kind!!, id, question)
                    found[node.key] = node
                }
                value.values.forEach(::visit)
            }

            is JsonArray -> {
                value.forEach(::visit)
            }

            else -> {
                Unit
            }
        }
    }
    visit(json)
    return found.values.toList()
}

fun isOfflineHomeUrl(url: Url): Boolean = url.encodedPath in setOf("/topstory/recommend", "/api/v3/feed/topstory/recommend") && isOfflineContentUrl(url)

/** Project cached detail JSON into the existing API card contracts; no alternate page is needed. */
fun offlineHomeCard(kind: String, id: String, detail: JsonObject, profile: String): JsonObject {
    val question = detail["question"] as? JsonObject
    val route = when (kind) {
        "answer" -> "https://www.zhihu.com/question/${question?.get("id")?.jsonPrimitive?.content ?: "0"}/answer/$id"
        "article" -> "https://zhuanlan.zhihu.com/p/$id"
        else -> "https://www.zhihu.com/question/$id"
    }
    if (profile != "mobile") {
        fun author(value: JsonObject?): JsonObject = buildJsonObject {
            value?.forEach { (key, item) -> put(key, item) }
            listOf("id", "name", "headline", "avatar_url").forEach { if (value?.get(it) == null) put(it, "") }
            if (value?.get("url") == null) put("url", "https://www.zhihu.com/people/${value?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty()}")
            if (value?.get("user_type") == null && value?.get("userType") == null) put("user_type", "people")
        }
        val normalized = detail.toMutableMap().apply {
            put("type", JsonPrimitive(kind))
            put("id", JsonPrimitive(id.toLong()))
            put("url", JsonPrimitive(route))
            put("author", author(detail["author"] as? JsonObject))
            if (question != null && question["author"] is JsonObject) put("question", JsonObject(question + ("author" to author(question["author"] as? JsonObject))))
        }
        return buildJsonObject {
            put("id", "offline:$kind:$id")
            put("type", "feed")
            put("verb", "recommend")
            put("target", JsonObject(normalized))
        }
    }

    fun text(style: String, value: String) = buildJsonObject {
        put("id", style)
        put("type", "Text")
        put("style", style)
        put("text", value)
    }
    val author = detail["author"] as? JsonObject
    val title = (if (kind == "answer") question?.get("title") else detail["title"])?.jsonPrimitive?.contentOrNull.orEmpty()
    val excerpt = detail["excerpt"]
        ?.jsonPrimitive
        ?.contentOrNull
        .orEmpty()
        .replace(Regex("<[^>]+>"), "")
        .take(500)
    return buildJsonObject {
        put("id", "offline:$kind:$id")
        put("type", "ComponentCard")
        put("action", buildJsonObject { put("parameter", "route_url=$route") })
        put(
            "children",
            buildJsonArray {
                add(text("Text", title))
                add(text("text_pin_summary", excerpt))
                add(
                    buildJsonObject {
                        put("type", "Line")
                        put("style", "LineText")
                        put("elements", buildJsonArray { add(text("Text", "")) })
                    },
                )
                add(
                    buildJsonObject {
                        put("type", "Line")
                        put("style", "LineFooterReaction_feed_v3")
                        put(
                            "elements",
                            buildJsonArray {
                                listOf("Vote" to "voteup_count", "Comment" to "comment_count", "Collect" to "favorite_count").forEach { (reaction, field) ->
                                    add(
                                        buildJsonObject {
                                            put("reaction", reaction)
                                            put("count", detail[field]?.jsonPrimitive?.intOrNull ?: 0)
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
                add(
                    buildJsonObject {
                        put("type", "Line")
                        put("style", "RecommendAuthorLine")
                        put(
                            "elements",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("style", "Avatar_default")
                                        put(
                                            "image",
                                            buildJsonObject {
                                                put(
                                                    "url",
                                                    author
                                                        ?.get("avatar_url")
                                                        ?.jsonPrimitive
                                                        ?.contentOrNull
                                                        .orEmpty(),
                                                )
                                            },
                                        )
                                    },
                                )
                                add(
                                    text(
                                        "Text",
                                        author
                                            ?.get("name")
                                            ?.jsonPrimitive
                                            ?.contentOrNull
                                            .orEmpty(),
                                    ),
                                )
                            },
                        )
                    },
                )
            },
        )
    }
}

fun validOfflineResponse(body: String): JsonObject? = runCatching {
    (Json.parseToJsonElement(body) as? JsonObject)?.takeIf { "error" !in it && ("data" in it || "id" in it) }
}.getOrNull()

fun resolveOfflineNext(base: String, json: JsonObject): String? {
    val paging = json["paging"] as? JsonObject ?: return null
    if (paging["is_end"]?.jsonPrimitive?.booleanOrNull == true) return null
    val next = paging["next"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
    return runCatching {
        val absolute = when {
            next.startsWith("https://") || next.startsWith("http://") -> next
            next.startsWith("//") -> "https:$next"
            next.startsWith("/") -> "https://${Url(base).host}$next"
            next.startsWith("?") -> base.substringBefore('?') + next
            else -> base.substringBeforeLast('/') + "/" + next
        }
        URLBuilder(absolute.replace("http://", "https://")).buildString()
    }.getOrNull()
}

fun limitOfflineItems(body: String, limit: Int): String {
    val response = validOfflineResponse(body) ?: return body
    val data = response["data"] as? JsonArray ?: return body
    return JsonObject(response + ("data" to JsonArray(data.take(limit.coerceAtLeast(0))))).toString()
}

fun projectOfflinePage(body: String, available: (OfflineNode) -> Boolean, nextAvailable: Boolean): String {
    val original = validOfflineResponse(body) ?: return body
    val data = original["data"] as? JsonArray
    val safeData = data?.filter { item -> offlineNodes(item).firstOrNull()?.let(available) != false }
    val paging = original["paging"] as? JsonObject
    val boundary = paging?.get("is_end")?.jsonPrimitive?.booleanOrNull != true &&
        !paging
            ?.get("next")
            ?.jsonPrimitive
            ?.contentOrNull
            .isNullOrEmpty() && !nextAvailable
    return buildJsonObject {
        original.forEach { (key, value) ->
            when {
                key == "data" && safeData != null -> put(key, JsonArray(safeData))
                key == "paging" && value is JsonObject && !nextAvailable -> put(key, JsonObject(value + ("is_end" to JsonPrimitive(true))))
                else -> put(key, value)
            }
        }
        if (boundary) put("offline_cache_boundary", true)
        put("offline_cache", true)
    }.toString()
}
