package com.ouor.localchat.llm

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/** A non-2xx answer to a request. [body] is the error body as text. */
internal class HttpStatusException(val code: Int, val body: String?, val retryAfter: String?) :
    RuntimeException("HTTP $code")

/**
 * The `data` of each server-sent event (architecture 4.2). Comment lines are skipped by okhttp-sse.
 * Completes on EOF; fails with [HttpStatusException], an IOException, or IllegalStateException for a
 * non-event-stream body. Cancelling the collector cancels the call, which closes the socket at once.
 */
internal fun OkHttpClient.sseData(request: Request): Flow<String> = callbackFlow {
    val source = EventSources.createFactory(this@sseData).newEventSource(request, object : EventSourceListener() {
        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            // Blocks the reader thread when the collector is slow instead of dropping deltas.
            trySendBlocking(data)
        }

        override fun onClosed(eventSource: EventSource) {
            channel.close()
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            val cause = if (response != null && !response.isSuccessful) {
                HttpStatusException(response.code, runCatching { response.body.string() }.getOrNull(), response.header("Retry-After"))
            } else {
                t ?: IllegalStateException("event stream failed")
            }
            channel.close(cause)
        }
    })
    awaitClose { source.cancel() }
}
