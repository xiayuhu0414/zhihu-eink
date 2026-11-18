package com.github.zly2006.zhihu.offline

import android.webkit.WebResourceResponse
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Both Compose/Coil and WebView resolve the same durable resource files. */
class OfflineImageInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        if (OfflineRuntime.context == null) return chain.proceed()
        val url = chain.request.data.toString()
        val account = OfflineRuntime.accountId()
        val resource = withContext(Dispatchers.IO) { OfflineRuntime.store(account).resource(url) }
        return if (resource != null) {
            chain
                .withRequest(
                    chain.request
                        .newBuilder()
                        .data(resource.first)
                        .memoryCacheKey("offline:$account:$url")
                        .diskCacheKey("offline:$account:$url")
                        .build(),
                ).proceed()
        } else {
            chain.proceed()
        }
    }
}

fun offlineWebResource(url: String): WebResourceResponse? {
    if (OfflineRuntime.context == null) return null
    return runCatching {
        val resource = OfflineRuntime.store().resource(url) ?: return null
        WebResourceResponse(resource.second, null, resource.first.inputStream())
    }.getOrNull()
}
