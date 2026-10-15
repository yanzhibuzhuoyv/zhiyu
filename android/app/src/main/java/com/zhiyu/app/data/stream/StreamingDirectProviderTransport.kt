package com.zhiyu.app.data.stream

import com.zhiyu.app.data.DirectProviderTransport

/** Optional bounded SSE transport. Cancellation must release its active HTTPS socket. */
interface StreamingDirectProviderTransport : DirectProviderTransport {
    suspend fun exchangeStreaming(endpoint: String, key: String, json: String,
        onBytes: suspend (ByteArray) -> Unit)
}
