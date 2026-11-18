package com.github.zly2006.zhihu.offline

import io.ktor.client.HttpClientConfig
import io.ktor.client.call.HttpClientCall
import io.ktor.client.call.save
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpResponseData
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.util.AttributeKey
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlin.coroutines.coroutineContext

val OfflineProfile = AttributeKey<String>("OfflineContentProfile")
val OfflinePrefetch = AttributeKey<Boolean>("OfflineContentPrefetch")
val OfflineSource = AttributeKey<Boolean>("OfflineContentSource")
val OfflineItemLimit = AttributeKey<Int>("OfflineContentItemLimit")

@Serializable
data class OfflineResponse(
    val url: String,
    val profile: String,
    val body: String,
    val savedAt: Long,
    val itemLimit: Int? = null,
)

interface OfflineBackend {
    fun connected(): Boolean

    fun active(): Boolean = true

    suspend fun read(url: Url, profile: String): OfflineResponse?

    suspend fun save(response: OfflineResponse, prefetch: Boolean)

    suspend fun project(response: OfflineResponse): String = response.body

    suspend fun invalidate(url: Url, profile: String)
}

class OfflinePluginConfig {
    var backend: OfflineBackend? = null
}

class OfflineCacheMissException : Exception("此内容尚未缓存，请联网后打开")

/** Cache transport responses before decoding, so existing page parsers remain unchanged. */
@OptIn(InternalAPI::class)
val OfflineContentPlugin = createClientPlugin("OfflineContent", ::OfflinePluginConfig) {
    val backend = pluginConfig.backend
    on(Send) { request ->
        val store = backend
        if (store?.connected() == true && request.url.parameters.contains("__offline_snapshot")) {
            request.url.parameters.remove("__offline_snapshot")
            request.url.parameters.remove("__offline_offset")
        }
        val url = request.url.build()
        if (store == null || !store.active() || request.method != HttpMethod.Get || !isOfflineContentUrl(url)) {
            return@on proceed(request)
        }
        val profile = request.attributes.getOrNull(OfflineProfile) ?: "web"
        val prefetch = request.attributes.getOrNull(OfflinePrefetch) == true
        val cached = try {
            store.read(url, profile)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

        suspend fun replay(): HttpClientCall {
            val body = store.project(requireNotNull(cached))
            request.attributes.put(OfflineSource, true)
            return HttpClientCall(
                client,
                request.build(),
                HttpResponseData(HttpStatusCode.OK, GMTDate(), headersOf(HttpHeaders.ContentType, "application/json; charset=utf-8"), HttpProtocolVersion.HTTP_1_1, ByteReadChannel(body.encodeToByteArray()), coroutineContext + Job(coroutineContext[Job])),
            )
        }
        if (!store.connected() && !prefetch) {
            if (cached != null) return@on replay()
            throw OfflineCacheMissException()
        }
        try {
            suspend fun fetchNetwork(): HttpClientCall {
                val call = proceed(request)
                return if (call.response.status == HttpStatusCode.OK) call.save() else call
            }
            // A bounded foreground attempt prevents weak Wi-Fi from hiding usable local data.
            val call = if (cached != null && !prefetch) withTimeoutOrNull(3_000) { fetchNetwork() } else fetchNetwork()
            if (call == null) return@on replay()
            if (call.response.status.value in 500..599 && cached != null && !prefetch) {
                call.cancel()
                return@on replay()
            }
            if (call.response.status == HttpStatusCode.NotFound || call.response.status == HttpStatusCode.Gone) {
                try {
                    store.invalidate(url, profile)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                }
            }
            if (call.response.status != HttpStatusCode.OK) return@on call
            val saved = call
            val body = saved.response.bodyAsText()
            if (body.encodeToByteArray().size <= 1_500_000 && validOfflineResponse(body) != null) {
                try {
                    store.save(OfflineResponse(url.toString(), profile, body, GMTDate().timestamp, request.attributes.getOrNull(OfflineItemLimit)), prefetch)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Disk full or a damaged cache must never break an otherwise successful online read.
                }
            }
            saved
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // HTTP authentication/permission errors are handled by the original account client.
            if (error is io.ktor.client.plugins.ResponseException) throw error
            if (cached != null && !prefetch) replay() else throw error
        }
    }
}

expect fun HttpClientConfig<*>.installOfflineContentCache(accountId: String)

expect fun forgetOfflineAccount(accountId: String)

expect fun offlineAccountKey(): String

expect suspend fun offlineReadingPosition(account: String, kind: String, id: String): Int

expect suspend fun recordOfflineReading(account: String, kind: String, id: String, position: Int, read: Boolean)
